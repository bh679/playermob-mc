package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Path-aware door handling: decide whether a door, <em>in its current state</em>, blocks
 * the way the mob is actually going — and, if it does, toggle it to the state that clears
 * the path. The replacement for the proximity-driven "open any nearby closed door every
 * tick" reflex, which opened doors the mob wasn't using and fought the close-when-stuck
 * recovery (an opened door's swing can block a mob turning at the doorway).
 *
 * <p><b>Geometry.</b> A door panel always occupies one of two perpendicular edges of its
 * cell: <em>closed</em> it lies across the doorway, so it blocks
 * travel along its {@link DoorBlock#FACING} axis; <em>open</em> it has swung 90° onto the
 * perpendicular edge, so it blocks travel along the <em>other</em> horizontal axis. So a
 * door obstructs the mob exactly when the mob's travel axis equals the door's currently
 * blocked axis — and since toggling open↔closed swaps which axis is blocked, toggling an
 * obstructing door always clears it.</p>
 *
 * <p><b>Travel axis.</b> Taken from the mob's per-tick displacement in the same coordinate
 * frame the doors live in — world space off a train, the carriage's sub-level space on a
 * moving Dungeon Train carriage (Dungeon Train locks carriage rotation to identity, so the
 * axis is the same in both frames). Measuring displacement in the door frame strips out the
 * carriage's carry-along drift, exactly as {@link DoorStuckMonitor} does for its progress
 * check — so a mob standing still on a moving carriage reads as "not heading anywhere"
 * rather than always heading along the track. The last confident axis is latched so it
 * survives the moment the mob stalls against a panel.</p>
 *
 * <p>The {@link #obstructs(boolean, boolean, boolean)} core is pure (no Minecraft types),
 * so the load-bearing geometry is unit-tested directly, mirroring {@link DoorStuckMonitor}.
 * The MC adapters and the live scan/toggle are exercised by the in-game Gate 2 test.
 * Vanilla types only, so it compiles on every loader. Server-thread only.</p>
 */
public final class DoorObstruction {

    private DoorObstruction() {}

    /**
     * Per-tick displacement (in the door frame) at or above which the mob's heading is
     * (re)latched. Comfortably above idle physics jitter (~0.02, see
     * {@link DoorStuckMonitor#PROGRESS_EPS_SQR}) yet below a walking mob's ~0.1–0.2/tick.
     */
    private static final double MOVE_EPS = 0.025;

    /**
     * How much one horizontal axis's displacement must exceed the other to latch a heading.
     * Near-diagonal movement (ratio below this) keeps the previously latched axis rather
     * than coin-flipping, so the assessment never toggles on an ambiguous heading.
     */
    private static final double DOMINANCE = 1.5;

    /** A door at {@code pos} whose current {@code state} obstructs the queried travel axis. */
    public record Obstruction(BlockPos pos, BlockState state) {}

    /** A hand-openable door (wooden or copper) — iron doors are redstone-only and excluded. */
    public static final Predicate<BlockState> HAND_DOOR = s -> !s.is(Blocks.IRON_DOOR);

    /** A currently-open hand-openable door — the close-when-stuck recovery's target. */
    public static final Predicate<BlockState> OPEN_HAND_DOOR =
        s -> !s.is(Blocks.IRON_DOOR) && s.getValue(DoorBlock.OPEN);

    /** A currently-closed iron door — opened (not toggled) via its redstone control. */
    public static final Predicate<BlockState> CLOSED_IRON_DOOR =
        s -> s.is(Blocks.IRON_DOOR) && !s.getValue(DoorBlock.OPEN);

    /**
     * Pure core: does a door block travel along a given axis? A closed door blocks its
     * facing axis; an open door blocks the perpendicular axis. Axes are expressed as
     * "is it the X axis?" booleans so this stays free of Minecraft types and unit-testable.
     *
     * @param facingIsX whether the door's facing axis is X (else Z)
     * @param open      whether the door is currently open
     * @param travelIsX whether the mob's travel axis is X (else Z)
     * @return {@code true} if the door, as it is, blocks travel along the mob's axis
     */
    public static boolean obstructs(boolean facingIsX, boolean open, boolean travelIsX) {
        boolean blockedIsX = open ? !facingIsX : facingIsX;
        return travelIsX == blockedIsX;
    }

    /** MC adapter for {@link #obstructs(boolean, boolean, boolean)} reading a door's state. */
    public static boolean obstructs(BlockState doorState, Direction.Axis travelAxis) {
        Direction facing = doorState.getValue(DoorBlock.FACING);
        boolean open = doorState.getValue(DoorBlock.OPEN);
        return obstructs(facing.getAxis() == Direction.Axis.X, open, travelAxis == Direction.Axis.X);
    }

    /**
     * The nearest door within {@code reach} of {@code base} (scanning both door halves) that
     * passes {@code accept} <em>and</em> obstructs {@code travelAxis}, or {@code null} if
     * none. {@code accept} is only ever tested on {@link DoorBlock} states, so it may read
     * door properties safely. Same cube the proximity reflex used, in whatever coordinate
     * frame {@code level}'s blocks are addressed in.
     */
    public static Obstruction nearestObstructing(Level level, BlockPos base, int reach,
                                                 Direction.Axis travelAxis, Predicate<BlockState> accept) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Obstruction best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof DoorBlock)
                            || !accept.test(state)
                            || !obstructs(state, travelAxis)) {
                        continue;
                    }
                    long distSq = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = new Obstruction(cursor.immutable(), state);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Toggle a hand-openable door open↔closed — which swaps the axis its panel blocks, so a
     * door that was obstructing the mob's travel axis no longer is. No-op on a non-door.
     *
     * @param actor the mob credited with the interaction (passed to {@link DoorBlock#setOpen})
     */
    public static void toggle(Entity actor, Level level, BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(actor, level, state, pos, !door.isOpen(state));
        }
    }

    /**
     * The mob's current travel axis, from its per-tick displacement {@code (x, z)} measured
     * in the door frame the caller chose. Latches the dominant axis while the mob is clearly
     * moving and holds the last latched axis while it stalls; {@code null} until a confident
     * heading has been seen (the first sample only primes, and an ambiguous/near-diagonal
     * heading with nothing latched yet stays null). One {@code Heading} per mob, server-thread
     * only, weak keys so entries vanish with the mob (like the gaze/stuck maps).
     *
     * <p>Must be called every tick the mob is door-pathing so the displacement is continuous
     * and a heading is latched before the mob reaches (and stalls against) a door.</p>
     */
    public static Direction.Axis travelAxis(Entity mob, double x, double z) {
        Heading h = HEADING.computeIfAbsent(mob, k -> new Heading());
        if (!h.primed) {
            h.primed = true;
            h.lastX = x;
            h.lastZ = z;
            return null;
        }
        double ax = Math.abs(x - h.lastX);
        double az = Math.abs(z - h.lastZ);
        h.lastX = x;
        h.lastZ = z;
        double max = Math.max(ax, az);
        double min = Math.min(ax, az);
        if (max >= MOVE_EPS && max >= min * DOMINANCE) {
            h.axis = ax >= az ? Direction.Axis.X : Direction.Axis.Z;
        }
        return h.axis;
    }

    /** Latched heading state for one mob: last sampled position and last confident axis. */
    private static final class Heading {
        private boolean primed;
        private double lastX;
        private double lastZ;
        private Direction.Axis axis; // last confident axis, or null until one is latched
    }

    private static final Map<Entity, Heading> HEADING = new WeakHashMap<>();
}
