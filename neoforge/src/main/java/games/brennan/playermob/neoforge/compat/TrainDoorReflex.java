package games.brennan.playermob.neoforge.compat;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.DoorHeading;
import games.brennan.playermob.entity.DoorObstruction;
import games.brennan.playermob.entity.DoorStuckMonitor;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.StuckDoorPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The per-tick hand-door reflex for a PlayerMob riding a Dungeon Train carriage: keep the
 * doors along its way in the state that lets it through, and — when it is wedged anyway —
 * <em>probe</em>: try a nearby door and give it a moment to walk.
 *
 * <p>Two layers, each tick, in the carriage's own (sub-level) frame:</p>
 * <ol>
 *   <li><b>Path-aware reflex.</b> The mob's heading ({@link DoorHeading}: decayed displacement,
 *       falling back to the train's axis while it marches between carriages) picks the axis it
 *       is travelling along; the nearest hand door that obstructs that axis ({@link
 *       DoorObstruction}) is toggled to the state that clears it. A door that isn't in the way
 *       is left alone. Anti-flap: a just-toggled door is left alone for {@link
 *       #DOOR_TOGGLE_COOLDOWN}, and a door is never closed on a companion standing beside.</li>
 *   <li><b>Stuck probe.</b> If the mob has still made no headway for a while ({@link
 *       DoorStuckMonitor}, fed sub-frame coordinates so the carriage's carry-along drift is
 *       stripped out), the reflex stops proving and starts trying: {@link StuckDoorPolicy}
 *       picks a door (train-axis obstruction first, else nearest; a second strike on the same
 *       door toggles it back; a third moves on), it is toggled regardless of cooldown or
 *       companions, the reflex is silenced for {@link #PROBE_SUPPRESS_TICKS} so the mob can
 *       attempt the walk, and that door is <em>pinned</em> for {@link #PROBE_PIN_TICKS} so the
 *       reflex can't undo the attempt. Walking {@link #PROBE_CLEAR_DIST} blocks clears the probe;
 *       otherwise the monitor re-fires and the next strike tries the other state.</li>
 * </ol>
 *
 * <p>Imports no Dungeon Train types (the caller converts to the sub-level frame), so it needs
 * no multi-version stub. Server thread only; per-mob state in a weak map, like the dig reflex.</p>
 */
final class TrainDoorReflex {

    private TrainDoorReflex() {}

    /** Door-reflex trace (gated on {@code debugSpawnLog}, the mod's Dungeon-Train diagnostics switch). */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far around the mob to look for a door it's standing against. */
    static final int DOOR_REACH = 2;

    /** Ticks a just-toggled hand door is left alone by the reflex before it may be toggled again. */
    static final int DOOR_TOGGLE_COOLDOWN = 20;

    /** How close another PlayerMob must be for a mob to hold a door open rather than close it on them. */
    static final double DOOR_COMPANION_REACH = 2.5;

    /** Ticks the path-aware reflex stays silent after a probe, so the mob can attempt the walk. ~2 s. */
    static final int PROBE_SUPPRESS_TICKS = 40;

    /**
     * Base stuck-monitor cooldown while a probe is outstanding — the next strike waits this long
     * (growing with each strike on the same door, see {@link StuckDoorPolicy#retryCooldown}) plus
     * the stuck window, so the ladder steps every ~4.5 s at first rather than flapping a door every 3.
     */
    static final int PROBE_RETRY_TICKS = 60;

    /** Ticks the reflex may not touch the door a probe just set. ~5 s — outlasts the stuck re-fire. */
    static final int PROBE_PIN_TICKS = 100;

    /** Sub-frame distance from the probe point that counts as "got through" and clears the probe. */
    static final double PROBE_CLEAR_DIST = 1.5;
    private static final double PROBE_CLEAR_DIST_SQR = PROBE_CLEAR_DIST * PROBE_CLEAR_DIST;

    /** Per-mob reflex state. Weak keys so entries vanish with the mob. */
    private static final class State {
        final DoorHeading heading = new DoorHeading();
        final DoorStuckMonitor stuck = new DoorStuckMonitor();
        DoorHeading.Axis axis = DoorHeading.Axis.NONE;
        long cooldownDoor = StuckDoorPolicy.NO_DOOR;   // door the reflex last toggled
        int cooldownTicks;
        long probeDoor = StuckDoorPolicy.NO_DOOR;      // door the last probe set (NO_DOOR ⇒ none outstanding)
        int probeStrikes;                              // consecutive probes spent on probeDoor
        int suppressTicks;                             // reflex fully silent
        int pinTicks;                                  // reflex may not touch probeDoor
        double probeX;
        double probeZ;
    }

    private static final Map<Entity, State> STATE = new WeakHashMap<>();

    /** A door scan: the base it was measured from and the doors found. */
    private record Scan(BlockPos base, List<DoorObstruction.Obstruction> doors) {}

    /**
     * Run the reflex for one tick.
     *
     * @param mob    the riding mob
     * @param level  the level whose blocks the carriage lives in
     * @param sub    the mob's position in the carriage's sub-level frame
     * @param subEye the mob's eye position in the same frame
     * @param mayProbe whether the stuck probe is allowed here — {@code false} at the forward
     *                 group boundary, where the only door ahead opens onto the inter-group gap
     *                 and there is nothing to walk through (the mob is waiting to leap, not wedged)
     * @return {@code true} if a hand door was handled (or the reflex is deliberately silent);
     *         {@code false} lets the caller fall through to the iron-door control path
     */
    static boolean tick(PlayerMobEntity mob, ServerLevel level, Vec3 sub, Vec3 subEye, boolean mayProbe) {
        State st = STATE.computeIfAbsent(mob, k -> new State());
        st.axis = st.heading.tick(sub.x, sub.z, mob.isMarchingCarriages());
        tickTimers(st);

        // "Trying to move": a march goal is driving (even if its path search failed and the
        // navigation reports done — that is exactly the wedged case), or any other goal is
        // pathing; and not busy with something that legitimately stands still.
        boolean tryingToMove = (mob.isMarchingCarriages() || !mob.getNavigation().isDone())
            && !mob.isOperatingDoor()
            && !mob.isDigging()
            && mob.getTarget() == null;
        // While a probe is outstanding the next strike waits longer each time — a wedge the door
        // can't explain shouldn't keep flapping it every few seconds.
        boolean probing = st.probeDoor != StuckDoorPolicy.NO_DOOR;
        int cooldown = probing
            ? StuckDoorPolicy.retryCooldown(PROBE_RETRY_TICKS, st.probeStrikes)
            : DoorStuckMonitor.COOLDOWN_TICKS;
        boolean wedged = st.stuck.tick(sub.x, sub.z, tryingToMove,
            mob.reactTicks(DoorStuckMonitor.STUCK_TICKS), mob.reactTicks(cooldown));

        Scan scan = scanHandDoors(level, mob, sub);
        if (wedged) {
            trace("[DoorStuck] mob={} sub=({}, {}, {}) w={} heading={} marching={} navDone={} mayProbe={} doors={} around={}",
                mob.getId(), fmt(sub.x), fmt(sub.y), fmt(sub.z), fmt(mob.getBbWidth()), st.axis,
                mob.isMarchingCarriages(), mob.getNavigation().isDone(), mayProbe, describe(scan),
                describeAround(level, BlockPos.containing(sub.x, sub.y, sub.z)));
        }
        if (wedged && mayProbe && probe(mob, level, st, sub, subEye, scan)) {
            return true;
        }
        settleProbe(mob, st, sub);
        if (st.suppressTicks > 0) {
            return true;
        }
        return reflex(mob, level, st, subEye, scan);
    }

    /** The mob's current travel axis as the reflex sees it, or {@code null} if none is known. */
    static Direction.Axis currentAxis(Entity mob) {
        State st = STATE.get(mob);
        return st == null ? null : toAxis(st.axis);
    }

    private static void tickTimers(State st) {
        if (st.cooldownTicks > 0) {
            st.cooldownTicks--;
        }
        if (st.suppressTicks > 0) {
            st.suppressTicks--;
        }
        if (st.pinTicks > 0) {
            st.pinTicks--;
        }
    }

    /**
     * Hand doors around the mob: at its sub-level position first, then at its apparent world
     * position in case a build projects carriage blocks there.
     */
    private static Scan scanHandDoors(ServerLevel level, PlayerMobEntity mob, Vec3 sub) {
        BlockPos subPos = BlockPos.containing(sub.x, sub.y, sub.z);
        List<DoorObstruction.Obstruction> doors =
            DoorObstruction.nearbyDoors(level, subPos, DOOR_REACH, DoorObstruction.HAND_DOOR);
        if (!doors.isEmpty()) {
            return new Scan(subPos, doors);
        }
        BlockPos worldPos = mob.blockPosition();
        return new Scan(worldPos, DoorObstruction.nearbyDoors(level, worldPos, DOOR_REACH, DoorObstruction.HAND_DOOR));
    }

    /** The stuck probe: pick a door, toggle it, silence the reflex, pin the door. */
    private static boolean probe(PlayerMobEntity mob, ServerLevel level, State st,
                                 Vec3 sub, Vec3 subEye, Scan scan) {
        List<StuckDoorPolicy.DoorCandidate> candidates = new ArrayList<>(scan.doors().size());
        for (DoorObstruction.Obstruction door : scan.doors()) {
            candidates.add(DoorObstruction.toCandidate(door, scan.base()));
        }
        StuckDoorPolicy.Choice choice = StuckDoorPolicy.pick(candidates, st.probeDoor, st.probeStrikes);
        if (choice == null) {
            return false;
        }
        BlockPos pos = BlockPos.of(choice.key());
        if (!operate(mob, level, pos, choice.desiredOpen(), subEye)) {
            return false; // an operation is already in flight — the monitor will re-fire after its cooldown
        }
        if (choice.key() == st.probeDoor) {
            st.probeStrikes++;
        } else {
            st.probeDoor = choice.key();
            st.probeStrikes = 1;
        }
        st.suppressTicks = PROBE_SUPPRESS_TICKS;
        st.pinTicks = PROBE_PIN_TICKS;
        st.probeX = sub.x;
        st.probeZ = sub.z;
        trace("[DoorProbe] mob={} door={} set open={} strike={} heading={} of {} doors",
            mob.getId(), pos.toShortString(), choice.desiredOpen(), st.probeStrikes, st.axis, candidates.size());
        return true;
    }

    private static void trace(String message, Object... args) {
        if (PlayerMobConfig.debugSpawnLog()) {
            LOGGER.info(message, args);
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Non-air blocks in the 3×2×3 around the mob's feet (relative offsets), for the wedged trace. */
    private static String describeAround(ServerLevel level, BlockPos feet) {
        StringBuilder sb = new StringBuilder();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    var state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append('(').append(dx).append(',').append(dy).append(',').append(dz).append(')')
                      .append(state.getBlock().getDescriptionId().replace("block.minecraft.", ""));
                }
            }
        }
        return sb.isEmpty() ? "clear" : sb.toString();
    }

    /** Compact "pos:facing/open" list of the scanned doors, for the wedged trace. */
    private static String describe(Scan scan) {
        StringBuilder sb = new StringBuilder();
        for (DoorObstruction.Obstruction d : scan.doors()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(d.pos().toShortString())
              .append(':').append(d.state().getValue(DoorBlock.FACING).getAxis())
              .append('/').append(d.state().getValue(DoorBlock.OPEN) ? "open" : "closed");
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    /** Once the mob has walked clear of where it probed, the probe succeeded: forget it and unpin. */
    private static void settleProbe(PlayerMobEntity mob, State st, Vec3 sub) {
        if (st.probeDoor == StuckDoorPolicy.NO_DOOR) {
            return;
        }
        double dx = sub.x - st.probeX;
        double dz = sub.z - st.probeZ;
        if (dx * dx + dz * dz >= PROBE_CLEAR_DIST_SQR) {
            trace("[DoorProbe] mob={} cleared door={} after {} strike(s)",
                mob.getId(), BlockPos.of(st.probeDoor).toShortString(), st.probeStrikes);
            st.probeDoor = StuckDoorPolicy.NO_DOOR;
            st.probeStrikes = 0;
            st.pinTicks = 0;
        }
    }

    /**
     * The path-aware reflex: toggle the nearest hand door that obstructs the mob's heading —
     * never the pinned probe door, never the same door twice within the cooldown, never closing
     * on a companion. Returns whether an obstructing hand door was found at all.
     */
    private static boolean reflex(PlayerMobEntity mob, ServerLevel level, State st, Vec3 subEye, Scan scan) {
        Direction.Axis axis = toAxis(st.axis);
        if (axis == null) {
            return false;
        }
        DoorObstruction.Obstruction best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (DoorObstruction.Obstruction door : scan.doors()) {
            if (!DoorObstruction.obstructs(door.state(), axis)
                    || !StuckDoorPolicy.reflexMayTouch(door.pos().asLong(), st.probeDoor, st.pinTicks)) {
                continue;
            }
            double distSq = door.pos().distSqr(scan.base());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = door;
            }
        }
        if (best == null) {
            return false;
        }
        long key = best.pos().asLong();
        boolean desiredOpen = !best.state().getValue(DoorBlock.OPEN);
        boolean coolingSameDoor = st.cooldownDoor == key && st.cooldownTicks > 0;
        // Don't slam a door shut on a companion: two mobs otherwise flap a shared door, each closing
        // it to clear its own path while the other reopens it. Whoever's left closes it once alone.
        boolean holdOpenForCompanion = !desiredOpen && anotherPlayerMobBeside(level, mob);
        if (!coolingSameDoor && !holdOpenForCompanion && operate(mob, level, best.pos(), desiredOpen, subEye)) {
            st.cooldownDoor = key;
            st.cooldownTicks = DOOR_TOGGLE_COOLDOWN;
            trace("[DoorReflex] mob={} door={} set open={} heading={} marching={}",
                mob.getId(), best.pos().toShortString(), desiredOpen, st.axis, mob.isMarchingCarriages());
        }
        return true;
    }

    /**
     * Defer the open/close into the mob's deliberate door-operation window (face, swing, operate;
     * interrupting combat/movement). The eye→door offset is taken in the sub-level frame, which
     * equals the world-frame offset since carriage rotation is locked to identity.
     */
    private static boolean operate(PlayerMobEntity mob, ServerLevel level, BlockPos pos, boolean open, Vec3 subEye) {
        return mob.beginDoorOperation(
            pos.getX() + 0.5 - subEye.x,
            pos.getY() + 0.5 - subEye.y,
            pos.getZ() + 0.5 - subEye.z,
            () -> DoorObstruction.setOpen(mob, level, pos, open));
    }

    /** Whether another PlayerMob is right beside {@code self} (within {@link #DOOR_COMPANION_REACH}). */
    private static boolean anotherPlayerMobBeside(ServerLevel level, PlayerMobEntity self) {
        AABB box = self.getBoundingBox().inflate(DOOR_COMPANION_REACH);
        return !level.getEntitiesOfClass(PlayerMobEntity.class, box, other -> other != self).isEmpty();
    }

    private static Direction.Axis toAxis(DoorHeading.Axis axis) {
        return switch (axis) {
            case X -> Direction.Axis.X;
            case Z -> Direction.Axis.Z;
            case NONE -> null;
        };
    }
}
