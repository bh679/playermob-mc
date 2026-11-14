package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides the next step a {@link PlayerMobEntity} takes to reach a combat target it can't path to —
 * the way a player would: pillar up under itself, build a staircase toward a ledge, tunnel through a
 * wall, or dig down into a hole (see {@code ReachTargetGoal}).
 *
 * <p>Stateless and frame-agnostic: positions are block cells in whatever frame the mob's surrounding
 * blocks live in (the world, or a Dungeon-Train carriage's sub-level), and the world is read through
 * the {@link Cells} view, so every choice is unit-testable without a live server (mirrors
 * {@link BlockSourcePolicy}). Carrying a step out — pacing a break, placing a block, jumping — is the
 * goal's job.</p>
 */
public final class ReachPolicy {

    private ReachPolicy() {}

    /**
     * Target at least this many blocks lower before the mob digs down. (It builds up as soon as the
     * target is out of swing reach above, {@link #MELEE_RISE}.)
     */
    public static final int CLIMB_RISE = 2;
    /** Horizontal distance (blocks) within which the mob builds straight up / digs straight down. */
    public static final double OVERHEAD_RANGE = 1.5;
    /** Horizontal distance within which a target under {@link #MELEE_RISE} blocks of height is in reach. */
    public static final double MELEE_RANGE = 2.0;
    /** Height difference (blocks) a melee swing still covers. */
    public static final double MELEE_RISE = 1.5;
    /** How high a jump lifts the mob's feet (vanilla's ~1.25, less a margin). */
    public static final double JUMP_HEIGHT = 1.15;
    /**
     * Blocks at least this hard need the right tool to break, as for a player — obsidian, ancient debris,
     * reinforced deepslate (hardness 50+) with a bare hand or a stone pickaxe would take minutes.
     */
    public static final float TOOL_ONLY_HARDNESS = 20.0F;

    /** What to do next. */
    public enum Kind {
        /** Jump and put a block under itself. */
        PILLAR,
        /** Put a block at {@link Step#cell()} (a stair tread, or the floor of a bridge). */
        PLACE,
        /** Jump forward onto the block at {@link Step#cell()}. */
        STEP_UP,
        /** Break the block at {@link Step#cell()}. */
        MINE,
        /** Walk into the open cell at {@link Step#cell()}. */
        WALK,
        /** Nothing it can do from here (no blocks to build with, or the way is unbreakable). */
        STUCK,
        /** Close enough to fight: hand back to the attack goal. */
        IN_REACH
    }

    /** A step and the cell it acts on ({@code null} for {@link Kind#PILLAR}, {@link Kind#STUCK}, {@link Kind#IN_REACH}). */
    public record Step(Kind kind, BlockPos cell) {
        static final Step STUCK = new Step(Kind.STUCK, null);
        static final Step IN_REACH = new Step(Kind.IN_REACH, null);
        static final Step PILLAR = new Step(Kind.PILLAR, null);
    }

    /** The mob's view of the blocks around it, in its block frame. */
    public interface Cells {
        /** True if {@code pos} has collision (something to stand on, or a wall in the way). */
        boolean solid(BlockPos pos);

        /** True if the mob may and can break the block at {@code pos} (see {@link #isMineable}). */
        boolean mineable(BlockPos pos);
    }

    /**
     * Whether a player-like mob may break {@code state}: not air or fluid, not unbreakable, not a block
     * entity (chests, furnaces, spawners — their contents would be lost), not the train's own track
     * ({@link BlockSourcePolicy#isProtectedTrackBlock}), and — for the very hard blocks — only with a tool
     * that can actually harvest it.
     *
     * @param hardness       {@code state.getDestroySpeed(level, pos)}
     * @param hasBlockEntity whether the block carries a block entity
     * @param correctTool    whether the best tool the mob owns harvests this block
     */
    public static boolean isMineable(BlockState state, float hardness, boolean hasBlockEntity, boolean correctTool) {
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (hardness < 0.0F || hasBlockEntity) return false;
        if (BlockSourcePolicy.isProtectedTrackBlock(state)) return false;
        return hardness < TOOL_ONLY_HARDNESS || correctTool;
    }

    /** True if a melee swing from {@code (dx, dy, dz)} away already connects. */
    public static boolean inReach(double dx, double dy, double dz) {
        return Math.abs(dy) < MELEE_RISE && dx * dx + dz * dz <= MELEE_RANGE * MELEE_RANGE;
    }

    /**
     * The next step from {@code feet} (the cell the mob's legs are in) toward a target whose feet are at
     * {@code (tx, ty, tz)}, all in the same block frame.
     *
     * @param hasBlocks  whether the mob carries a block it can put down
     * @param canDigDown whether digging straight down is allowed here (never on a train: the floor
     *                   holds the carriage up)
     */
    public static Step next(BlockPos feet, double fx, double fy, double fz,
                            double tx, double ty, double tz,
                            Cells cells, boolean hasBlocks, boolean canDigDown) {
        double dx = tx - fx;
        double dz = tz - fz;
        double dy = ty - fy;
        if (inReach(dx, dy, dz)) {
            return Step.IN_REACH;
        }
        double horiz = Math.sqrt(dx * dx + dz * dz);
        BlockPos head = feet.above();
        BlockPos aboveHead = feet.above(2);
        Direction dir = Math.abs(dx) >= Math.abs(dz)
            ? (dx >= 0.0 ? Direction.EAST : Direction.WEST)
            : (dz >= 0.0 ? Direction.SOUTH : Direction.NORTH);

        if (dy >= MELEE_RISE) {
            // Headroom first: neither a pillar nor a stair step works without room to jump.
            if (cells.solid(aboveHead)) {
                return mineOrStuck(cells, aboveHead);
            }
            // Pillaring means jumping clear of the leg cell; standing in a partial floor (a slab deck) a jump
            // can't, so build a stair step instead.
            if (horiz <= OVERHEAD_RANGE && fy + JUMP_HEIGHT >= feet.getY() + 1) {
                return hasBlocks ? Step.PILLAR : Step.STUCK;
            }
            return climbToward(feet.relative(dir), cells, hasBlocks);
        }

        if (dy <= -CLIMB_RISE && horiz <= OVERHEAD_RANGE) {
            if (!canDigDown) return Step.STUCK;
            if (!cells.solid(feet.below())) return new Step(Kind.WALK, feet.below());   // open drop: go down it
            return mineOrStuck(cells, feet.below());
        }

        // Roughly level (or the target is below but off to one side): tunnel / bridge toward it.
        BlockPos front = feet.relative(dir);
        BlockPos frontHead = front.above();
        if (cells.solid(frontHead)) {
            return mineOrStuck(cells, frontHead);
        }
        if (cells.solid(front)) {
            // A one-block step with room above it is a step up, not a wall.
            if (dy >= 1.0 && !cells.solid(front.above(2)) && !cells.solid(aboveHead)) {
                return new Step(Kind.STEP_UP, front);
            }
            return mineOrStuck(cells, front);
        }
        if (!cells.solid(front.below()) && dy > -CLIMB_RISE) {
            // A gap at the mob's level: lay the floor of a bridge before stepping out over it.
            return hasBlocks ? new Step(Kind.PLACE, front.below()) : Step.STUCK;
        }
        return new Step(Kind.WALK, front);
    }

    /**
     * One stair step toward a higher target: clear the step's headroom, then lay the tread (and the
     * block under it if the tread would float), then jump onto it.
     */
    private static Step climbToward(BlockPos front, Cells cells, boolean hasBlocks) {
        if (cells.solid(front.above(2))) return mineOrStuck(cells, front.above(2));
        if (cells.solid(front.above())) return mineOrStuck(cells, front.above());
        if (cells.solid(front)) return new Step(Kind.STEP_UP, front);
        if (!hasBlocks) return Step.STUCK;
        if (!cells.solid(front.below())) return new Step(Kind.PLACE, front.below());
        return new Step(Kind.PLACE, front);
    }

    private static Step mineOrStuck(Cells cells, BlockPos pos) {
        return cells.mineable(pos) ? new Step(Kind.MINE, pos) : Step.STUCK;
    }
}
