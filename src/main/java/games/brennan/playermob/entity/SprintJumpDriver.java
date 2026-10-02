package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Makes a sprinting {@link PlayerMobEntity} sprint-jump like a player, where
 * {@link SprintJumpPolicy} says the stretch ahead is worth it. Ticked by
 * {@link PlayerLikeMoveControl} after it has settled the sprint flag.
 *
 * <p>The speed is all vanilla: a sprinting {@code LivingEntity} already gets the 0.2 forward boost
 * in {@code jumpFromGround}, and {@link PlayerMobEntity#getFlyingSpeed()} supplies a player's
 * airborne acceleration. This class only decides <em>when</em> to press jump and keeps the mob on
 * its line while it is in the air.</p>
 *
 * <p><b>Why the line has to be held.</b> Ground navigation stops advancing path nodes while the mob
 * is airborne and low, so {@code MoveControl} would turn it back toward a node it has already
 * flown past — and the next takeoff's boost would then fire in the wrong direction. From takeoff
 * to landing the driver pins the yaw to the run and steps the path forward itself.</p>
 */
final class SprintJumpDriver {

    /** Ticks a latched takeoff may stay grounded before it is abandoned (covers the vanilla jump delay). */
    private static final int TAKEOFF_GRACE_TICKS = 12;

    private final PlayerMobEntity mob;

    private boolean wasSprinting;
    /** Rolled once per sprint — whether this mob jumps on this run at all. */
    private boolean jumpsThisRun;

    private SprintJumpPolicy.Mode latched = SprintJumpPolicy.Mode.NONE;
    private int stepX;
    private int stepZ;
    private boolean leftGround;
    private int groundedTicks;

    SprintJumpDriver(PlayerMobEntity mob) {
        this.mob = mob;
    }

    void tick(boolean sprinting) {
        if (!sprinting) {
            wasSprinting = false;
            latched = SprintJumpPolicy.Mode.NONE;
            return;
        }
        if (!wasSprinting) {
            wasSprinting = true;
            jumpsThisRun = SprintJumpPolicy.rollsRun(mob.reactionSpeed(), mob.getRandom().nextDouble());
        }
        if (!jumpsThisRun || !mob.canSprintJump()) {
            latched = SprintJumpPolicy.Mode.NONE;
            return;
        }
        if (latched != SprintJumpPolicy.Mode.NONE && continueLatched()) {
            return;
        }
        if (mob.onGround()) {
            tryTakeoff();
        }
    }

    /** Keep an in-flight jump on course. False once it has landed (or never left the ground). */
    private boolean continueLatched() {
        if (!mob.onGround()) {
            leftGround = true;
        }
        boolean landed = leftGround && mob.onGround();
        boolean stalled = !leftGround && ++groundedTicks > TAKEOFF_GRACE_TICKS;
        if (landed || stalled) {
            latched = SprintJumpPolicy.Mode.NONE;
            return false;
        }
        holdCourse();
        // OPEN holds the key down, so the next leap fires the tick it lands. HEAD_BUMP lets go in
        // the air: releasing clears the vanilla 10-tick jump delay, which is what lets a hop cut
        // short by the ceiling be followed immediately by the next one.
        if (latched == SprintJumpPolicy.Mode.OPEN) {
            mob.getJumpControl().jump();
        }
        return true;
    }

    private void tryTakeoff() {
        if (mob.isInWater() || mob.isInLava() || mob.onClimbable() || mob.isPassenger()) {
            return;
        }
        // A carriage is narrow, moving and has gaps between groups — never worth a leap.
        if (mob.ticksSinceOnTrain() <= 1) {
            return;
        }
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return;
        }
        BlockPos feet = mob.blockPosition();
        int from = path.getNextNodeIndex();
        while (from < path.getNodeCount() && sameColumn(path.getNodePos(from), feet)) {
            from++;
        }
        int count = Math.min(SprintJumpPolicy.LOOKAHEAD, path.getNodeCount() - from);
        if (count <= 0) {
            return;
        }
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] zs = new int[count];
        for (int i = 0; i < count; i++) {
            BlockPos node = path.getNodePos(from + i);
            xs[i] = node.getX();
            ys[i] = node.getY();
            zs[i] = node.getZ();
        }
        int runLength = SprintJumpPolicy.straightRunLength(feet.getX(), feet.getZ(), feet.getY(), xs, ys, zs);
        if (runLength == 0) {
            return;
        }
        int dx = xs[0] - feet.getX();
        int dz = zs[0] - feet.getZ();
        if (!SprintJumpPolicy.yawAligned(mob.getYRot(), dx, dz)) {
            return;
        }
        Level level = mob.level();
        boolean[] safe = new boolean[runLength];
        boolean[] lowCeiling = new boolean[runLength];
        for (int i = 0; i < runLength; i++) {
            BlockPos cell = new BlockPos(xs[i], ys[i], zs[i]);
            safe[i] = isSafeCell(level, cell, dx, dz);
            lowCeiling[i] = !isClear(level, cell.above(2));
        }
        SprintJumpPolicy.Mode mode = SprintJumpPolicy.classify(mob.reactionSpeed(), runLength, safe, lowCeiling);
        if (mode == SprintJumpPolicy.Mode.NONE) {
            return;
        }
        latched = mode;
        stepX = dx;
        stepZ = dz;
        leftGround = false;
        groundedTicks = 0;
        holdCourse();
        mob.getJumpControl().jump();
    }

    /** Pin the yaw to the run and move the path on past any node already behind the mob. */
    private void holdCourse() {
        mob.setYRot(SprintJumpPolicy.yawOf(stepX, stepZ));
        Path path = mob.getNavigation().getPath();
        if (path == null) {
            return;
        }
        // Never advance off the end: a finished path would stop the navigation mid-leap.
        while (path.getNextNodeIndex() + 1 < path.getNodeCount()) {
            BlockPos next = path.getNodePos(path.getNextNodeIndex());
            double ahead = (next.getX() + 0.5 - mob.getX()) * stepX + (next.getZ() + 0.5 - mob.getZ()) * stepZ;
            if (ahead > 0.0) {
                break;
            }
            path.advance();
        }
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    /**
     * A run cell the mob can land in and not fall off: two clear blocks, solid underfoot, and on each
     * side either a wall or solid ground.
     */
    private static boolean isSafeCell(Level level, BlockPos cell, int dx, int dz) {
        if (!isClear(level, cell) || !isClear(level, cell.above()) || !isSolidTop(level, cell.below())) {
            return false;
        }
        // Perpendicular to the run: (dx, dz) rotated a quarter turn each way.
        return isGuarded(level, cell.offset(-dz, 0, dx)) && isGuarded(level, cell.offset(dz, 0, -dx));
    }

    private static boolean isGuarded(Level level, BlockPos side) {
        return !isClear(level, side) || isSolidTop(level, side.below());
    }

    private static boolean isClear(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private static boolean isSolidTop(Level level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }
}
