package games.brennan.playermob.neoforge.compat;

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

    /** How far around the mob to look for a door it's standing against. */
    static final int DOOR_REACH = 2;

    /** Ticks a just-toggled hand door is left alone by the reflex before it may be toggled again. */
    static final int DOOR_TOGGLE_COOLDOWN = 20;

    /** How close another PlayerMob must be for a mob to hold a door open rather than close it on them. */
    static final double DOOR_COMPANION_REACH = 2.5;

    /** Ticks the path-aware reflex stays silent after a probe, so the mob can attempt the walk. ~2 s. */
    static final int PROBE_SUPPRESS_TICKS = 40;

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
     * @return {@code true} if a hand door was handled (or the reflex is deliberately silent);
     *         {@code false} lets the caller fall through to the iron-door control path
     */
    static boolean tick(PlayerMobEntity mob, ServerLevel level, Vec3 sub, Vec3 subEye) {
        State st = STATE.computeIfAbsent(mob, k -> new State());
        st.axis = st.heading.tick(sub.x, sub.z, mob.isMarchingCarriages());
        tickTimers(st);

        // "Trying to move": pathing, and not busy with something that legitimately stands still.
        boolean tryingToMove = !mob.getNavigation().isDone()
            && !mob.isOperatingDoor()
            && !mob.isDigging()
            && mob.getTarget() == null;
        boolean wedged = st.stuck.tick(sub.x, sub.z, tryingToMove,
            mob.reactTicks(DoorStuckMonitor.STUCK_TICKS),
            mob.reactTicks(DoorStuckMonitor.COOLDOWN_TICKS));

        Scan scan = scanHandDoors(level, mob, sub);
        if (wedged && probe(mob, level, st, sub, subEye, scan)) {
            return true;
        }
        settleProbe(st, sub);
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
        return true;
    }

    /** Once the mob has walked clear of where it probed, the probe succeeded: forget it and unpin. */
    private static void settleProbe(State st, Vec3 sub) {
        if (st.probeDoor == StuckDoorPolicy.NO_DOOR) {
            return;
        }
        double dx = sub.x - st.probeX;
        double dz = sub.z - st.probeZ;
        if (dx * dx + dz * dz >= PROBE_CLEAR_DIST_SQR) {
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
