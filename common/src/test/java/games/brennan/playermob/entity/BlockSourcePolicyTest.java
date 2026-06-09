package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link BlockSourcePolicy} — the recovery bridge's
 * block-source + staircase math.
 *
 * <p>Same {@link Bootstrap} dance as {@code ItemPickupPolicyTest} (vanilla
 * registries must boot before any {@link Items}/{@link Blocks} reference). The
 * staircase arithmetic ({@link BlockSourcePolicy#nextBridgePos} /
 * {@code horizontalDirToward}) is pure {@code BlockPos}/{@code AABB} math. The
 * tag-driven branches ({@link net.minecraft.tags.BlockTags#LOGS} in
 * {@code isHandBreakable}, {@code RAILS} in {@code isProtectedTrackBlock}) need a
 * bound datapack and are covered by the in-game Gate 2 smoke test, not here —
 * this suite exercises the explicit-{@code Blocks} and tool-gate branches.</p>
 */
class BlockSourcePolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- Backpack placeable blocks ---------------------------------------

    @Test
    void findsFirstPlaceableBlockSlot() {
        SimpleContainer backpack = new SimpleContainer(4);
        backpack.setItem(0, new ItemStack(Items.STICK));        // not a block
        backpack.setItem(1, new ItemStack(Items.DIAMOND));      // not a block
        backpack.setItem(2, new ItemStack(Items.COBBLESTONE));  // BlockItem
        assertEquals(2, BlockSourcePolicy.firstPlaceableSlot(backpack), "first BlockItem slot");
        assertTrue(BlockSourcePolicy.hasPlaceableBlock(backpack));
    }

    @Test
    void bridgeBlockCountTreatsLogsAsFourPlanks() {
        SimpleContainer b = new SimpleContainer(4);
        b.setItem(0, new ItemStack(Items.COBBLESTONE, 3));  // 3 placeable blocks
        b.setItem(1, new ItemStack(Items.OAK_LOG, 2));      // 2 logs → 8 planks
        b.setItem(2, new ItemStack(Items.STICK, 5));        // not a block → 0
        assertEquals(11, BlockSourcePolicy.bridgeBlockCount(b));
    }

    @Test
    void emptyBackpackHasNoPlaceableBlock() {
        SimpleContainer backpack = new SimpleContainer(4);
        backpack.setItem(0, new ItemStack(Items.STICK));
        assertEquals(-1, BlockSourcePolicy.firstPlaceableSlot(backpack));
        assertFalse(BlockSourcePolicy.hasPlaceableBlock(backpack));
    }

    // ---- Logs → planks ---------------------------------------------------

    @Test
    void craftableLogConvertsToFourMatchingPlanks() {
        assertTrue(BlockSourcePolicy.isCraftableLog(new ItemStack(Items.SPRUCE_LOG)));
        assertFalse(BlockSourcePolicy.isCraftableLog(new ItemStack(Items.OAK_PLANKS)), "planks aren't logs");
        assertFalse(BlockSourcePolicy.isCraftableLog(new ItemStack(Items.STICK)));

        ItemStack planks = BlockSourcePolicy.planksFromLog(new ItemStack(Items.SPRUCE_LOG));
        assertEquals(Items.SPRUCE_PLANKS, planks.getItem(), "spruce log → spruce planks");
        assertEquals(4, planks.getCount(), "1 log → 4 planks");
        assertTrue(BlockSourcePolicy.planksFromLog(new ItemStack(Items.DIRT)).isEmpty(), "non-log → empty");
    }

    // ---- Gather candidate predicates -------------------------------------

    @Test
    void handBreakableCoversDirtSandGravel() {
        assertTrue(BlockSourcePolicy.isHandBreakable(Blocks.DIRT.defaultBlockState()));
        assertTrue(BlockSourcePolicy.isHandBreakable(Blocks.SAND.defaultBlockState()));
        assertTrue(BlockSourcePolicy.isHandBreakable(Blocks.GRAVEL.defaultBlockState()));
        assertTrue(BlockSourcePolicy.isHandBreakable(Blocks.CLAY.defaultBlockState()));
        assertFalse(BlockSourcePolicy.isHandBreakable(Blocks.STONE.defaultBlockState()),
            "stone is not hand-breakable");
    }

    @Test
    void stoneClassCoversStoneVariants() {
        assertTrue(BlockSourcePolicy.isStoneClass(Blocks.STONE.defaultBlockState()));
        assertTrue(BlockSourcePolicy.isStoneClass(Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(BlockSourcePolicy.isStoneClass(Blocks.DEEPSLATE.defaultBlockState()));
        assertFalse(BlockSourcePolicy.isStoneClass(Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void easyBlockGatesStoneOnPickaxe() {
        // Dirt is easy regardless of tools.
        assertTrue(BlockSourcePolicy.isEasyBlock(Blocks.DIRT.defaultBlockState(), false));
        assertTrue(BlockSourcePolicy.isEasyBlock(Blocks.DIRT.defaultBlockState(), true));
        // Stone is easy only with a pickaxe.
        assertFalse(BlockSourcePolicy.isEasyBlock(Blocks.STONE.defaultBlockState(), false),
            "no pickaxe → stone not worth gathering");
        assertTrue(BlockSourcePolicy.isEasyBlock(Blocks.STONE.defaultBlockState(), true),
            "pickaxe → stone is a valid source");
    }

    @Test
    void protectedTrackBlocksAreExcluded() {
        assertTrue(BlockSourcePolicy.isProtectedTrackBlock(Blocks.STONE_BRICKS.defaultBlockState()),
            "the bed is stone brick — never break/overwrite it");
        assertTrue(BlockSourcePolicy.isProtectedTrackBlock(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        assertFalse(BlockSourcePolicy.isProtectedTrackBlock(Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void clearableObstructionExcludesSolidBuildingBlocks() {
        // Must NOT flag solid building blocks — else the mob breaks the very stair/pillar it
        // just placed (the place-then-rebreak loop). The clearable cases are foliage (LEAVES /
        // REPLACEABLE tags), covered by the in-game smoke test since tags need a bound datapack.
        assertFalse(BlockSourcePolicy.isClearableObstruction(Blocks.DIRT.defaultBlockState()),
            "dirt is footing/building material, not a soft obstruction");
        assertFalse(BlockSourcePolicy.isClearableObstruction(Blocks.SAND.defaultBlockState()));
        assertFalse(BlockSourcePolicy.isClearableObstruction(Blocks.STONE.defaultBlockState()));
        assertFalse(BlockSourcePolicy.isClearableObstruction(Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test
    void gravityBlocksDetected() {
        assertTrue(BlockSourcePolicy.isGravityBlock(Blocks.SAND.defaultBlockState()), "sand falls");
        assertTrue(BlockSourcePolicy.isGravityBlock(Blocks.GRAVEL.defaultBlockState()), "gravel falls");
        assertFalse(BlockSourcePolicy.isGravityBlock(Blocks.DIRT.defaultBlockState()), "dirt doesn't fall");
        assertFalse(BlockSourcePolicy.isGravityBlock(Blocks.COBBLESTONE.defaultBlockState()));
    }

    // ---- Staircase math --------------------------------------------------

    @Test
    void nextBridgePosStepsTowardCarriageWhenBelow() {
        // Carriage box to the +X of the mob; floor (minY) at 65, mob feet at 63.
        AABB carriage = new AABB(10, 65, 0, 19, 70, 9);
        BlockPos foot = new BlockPos(5, 63, 4);
        // Below the lip → place one block east (toward the carriage) at foot level.
        assertEquals(new BlockPos(6, 63, 4), BlockSourcePolicy.nextBridgePos(foot, carriage));
    }

    @Test
    void nextBridgePosNullOnceLevelWithCarriageBase() {
        AABB carriage = new AABB(10, 65, 0, 19, 70, 9);
        // Feet already at the carriage base layer → jump the last step, no placement.
        assertNull(BlockSourcePolicy.nextBridgePos(new BlockPos(5, 65, 4), carriage));
        assertNull(BlockSourcePolicy.nextBridgePos(new BlockPos(5, 67, 4), carriage));
    }

    @Test
    void horizontalDirTowardPicksDominantAxis() {
        BlockPos foot = new BlockPos(5, 63, 4);
        assertEquals(Direction.EAST,
            BlockSourcePolicy.horizontalDirToward(foot, new AABB(10, 65, 0, 19, 70, 9)));
        assertEquals(Direction.WEST,
            BlockSourcePolicy.horizontalDirToward(foot, new AABB(-19, 65, 0, -10, 70, 9)));
        assertEquals(Direction.SOUTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(4, 63, 5), new AABB(0, 65, 10, 9, 70, 19)));
        assertEquals(Direction.NORTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(4, 63, 5), new AABB(0, 65, -19, 9, 70, -10)));
    }
}
