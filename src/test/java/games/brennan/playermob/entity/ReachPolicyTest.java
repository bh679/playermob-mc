package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link ReachPolicy} — which step an echo takes toward a target it can't path
 * to. The world is a set of solid cells on a flat floor at y=63 (the mob stands at y=64); a second set
 * marks the cells it may not break.
 */
class ReachPolicyTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A flat floor at y=63 plus {@code extra} solid cells; {@code unbreakable} cells can't be mined. */
    private static final class World implements ReachPolicy.Cells {
        final Set<BlockPos> solid = new HashSet<>();
        final Set<BlockPos> unbreakable = new HashSet<>();
        boolean floor = true;

        @Override
        public boolean solid(BlockPos pos) {
            return solid.contains(pos) || (floor && pos.getY() == 63);
        }

        @Override
        public boolean mineable(BlockPos pos) {
            return solid(pos) && !unbreakable.contains(pos);
        }
    }

    private static ReachPolicy.Step next(World w, double tx, double ty, double tz, boolean hasBlocks, boolean canDigDown) {
        return ReachPolicy.next(FEET, 0.5, 64.0, 0.5, tx, ty, tz, w, hasBlocks, canDigDown);
    }

    // ---- Reach ------------------------------------------------------------

    @Test
    void adjacentTargetIsInReach() {
        assertEquals(ReachPolicy.Kind.IN_REACH, next(new World(), 2.0, 64.0, 0.5, true, true).kind());
        assertEquals(ReachPolicy.Kind.IN_REACH, next(new World(), 1.5, 65.0, 0.5, true, true).kind());
    }

    // ---- Up ---------------------------------------------------------------

    @Test
    void targetOverheadPillars() {
        assertEquals(ReachPolicy.Kind.PILLAR, next(new World(), 1.5, 67.0, 0.5, true, true).kind());
    }

    @Test
    void cannotPillarWithoutBlocks() {
        assertEquals(ReachPolicy.Kind.STUCK, next(new World(), 1.5, 67.0, 0.5, false, true).kind());
    }

    @Test
    void ceilingIsMinedBeforePillaring() {
        World w = new World();
        w.solid.add(FEET.above(2));
        ReachPolicy.Step step = next(w, 0.5, 68.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.MINE, step.kind());
        assertEquals(FEET.above(2), step.cell());
    }

    @Test
    void unbreakableCeilingIsStuck() {
        World w = new World();
        w.solid.add(FEET.above(2));
        w.unbreakable.add(FEET.above(2));
        assertEquals(ReachPolicy.Kind.STUCK, next(w, 0.5, 68.0, 0.5, true, true).kind());
    }

    @Test
    void distantHighTargetGetsAStairTread() {
        ReachPolicy.Step step = next(new World(), 6.5, 68.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.PLACE, step.kind());
        assertEquals(FEET.east(), step.cell());
    }

    @Test
    void floatingTreadGetsAFootingFirst() {
        World w = new World();
        w.floor = false;
        w.solid.add(FEET.below());   // only the block the mob stands on
        ReachPolicy.Step step = next(w, 6.5, 68.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.PLACE, step.kind());
        assertEquals(FEET.east().below(), step.cell());
    }

    @Test
    void builtTreadIsSteppedOnto() {
        World w = new World();
        w.solid.add(FEET.east());
        ReachPolicy.Step step = next(w, 6.5, 68.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.STEP_UP, step.kind());
        assertEquals(FEET.east(), step.cell());
    }

    // ---- Level ------------------------------------------------------------

    @Test
    void wallIsTunnelledHeadFirst() {
        World w = new World();
        w.solid.add(FEET.east());
        w.solid.add(FEET.east().above());
        ReachPolicy.Step step = next(w, 6.5, 64.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.MINE, step.kind());
        assertEquals(FEET.east().above(), step.cell());
    }

    @Test
    void openWayIsWalked() {
        ReachPolicy.Step step = next(new World(), 6.5, 64.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.WALK, step.kind());
        assertEquals(FEET.east(), step.cell());
    }

    @Test
    void gapIsBridged() {
        World w = new World();
        w.floor = false;
        w.solid.add(FEET.below());
        ReachPolicy.Step step = next(w, 6.5, 64.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.PLACE, step.kind());
        assertEquals(FEET.east().below(), step.cell());
    }

    // ---- Down -------------------------------------------------------------

    @Test
    void targetBelowDigsDown() {
        ReachPolicy.Step step = next(new World(), 0.5, 61.0, 0.5, true, true);
        assertEquals(ReachPolicy.Kind.MINE, step.kind());
        assertEquals(FEET.below(), step.cell());
    }

    @Test
    void neverDigsDownWhereNotAllowed() {
        assertEquals(ReachPolicy.Kind.STUCK, next(new World(), 0.5, 61.0, 0.5, true, false).kind());
    }

    // ---- What may be broken -----------------------------------------------

    @Test
    void mineableRules() {
        assertTrue(ReachPolicy.isMineable(Blocks.DIRT.defaultBlockState(), 0.5F, false, false));
        assertTrue(ReachPolicy.isMineable(Blocks.STONE.defaultBlockState(), 1.5F, false, false));
        assertFalse(ReachPolicy.isMineable(Blocks.AIR.defaultBlockState(), 0.0F, false, false));
        assertFalse(ReachPolicy.isMineable(Blocks.WATER.defaultBlockState(), 100.0F, false, false));
        assertFalse(ReachPolicy.isMineable(Blocks.BEDROCK.defaultBlockState(), -1.0F, false, true));
        assertFalse(ReachPolicy.isMineable(Blocks.CHEST.defaultBlockState(), 2.5F, true, true));
        assertFalse(ReachPolicy.isMineable(Blocks.STONE_BRICKS.defaultBlockState(), 1.5F, false, true));
        assertFalse(ReachPolicy.isMineable(Blocks.OBSIDIAN.defaultBlockState(), 50.0F, false, false));
        assertTrue(ReachPolicy.isMineable(Blocks.OBSIDIAN.defaultBlockState(), 50.0F, false, true));
    }
}
