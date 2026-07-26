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
    void nextBridgePosStepsAcrossTheNearZFaceWhenBelow() {
        // Realistic geometry: the mob is BESIDE the line, outside the carriage's Z-span. (It is never
        // within the span when this runs — tick() forces APPROACH/tickGetOffTracks the moment
        // onTracks() is true, precisely because you cannot board a moving carriage from the static
        // bed.) Carriage floor (minY) at 65, mob feet at 63, mob 2 blocks off the −Z face.
        AABB carriage = new AABB(10, 65, 10, 19, 70, 19);
        BlockPos foot = new BlockPos(5, 63, 8);
        // Below the lip → place one block SOUTH (+Z, across the near face) at foot level.
        // Emphatically NOT east: the carriage is far to the +X here, and stepping along the rails
        // builds a staircase that never approaches the deck. See horizontalDirToward's javadoc.
        assertEquals(new BlockPos(5, 63, 9), BlockSourcePolicy.nextBridgePos(foot, carriage));
    }

    @Test
    void nextBridgePosNullOnceLevelWithCarriageBase() {
        AABB carriage = new AABB(10, 65, 10, 19, 70, 19);
        // Feet already at the carriage base layer → jump the last step, no placement.
        assertNull(BlockSourcePolicy.nextBridgePos(new BlockPos(5, 65, 8), carriage));
        assertNull(BlockSourcePolicy.nextBridgePos(new BlockPos(5, 67, 8), carriage));
    }

    @Test
    void horizontalDirTowardCrossesTheNearZFace() {
        // Standing outside the −Z face → step +Z (SOUTH) onto it; outside +Z → step −Z (NORTH).
        assertEquals(Direction.SOUTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(4, 63, 5), new AABB(0, 65, 10, 9, 70, 19)));
        assertEquals(Direction.NORTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(4, 63, 5), new AABB(0, 65, -19, 9, 70, -10)));
    }

    @Test
    void horizontalDirTowardNeverStepsAlongTheTrack() {
        // THE REGRESSION THIS GUARDS: the old implementation picked the dominant axis toward the box
        // centre. Carriages are long in X and the line runs along X, so a mob off to one side got
        // EAST/WEST — parallel to the rails — and bridged a staircase alongside the track that never
        // approached the deck. Both cases below are strongly X-dominant and must still resolve in Z.
        assertEquals(Direction.SOUTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(5, 63, 4), new AABB(10, 65, 10, 19, 70, 19)),
            "far off in −X and −Z must still cross the near Z face, not run along the rails");
        assertEquals(Direction.NORTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(80, 63, 40), new AABB(0, 65, 0, 40, 70, 9)),
            "far off in +X and +Z must still cross the near Z face");
    }

    @Test
    void horizontalDirTowardInsideTheSpanPicksTheNearerFace() {
        // On the bed (Z within the carriage span) there is no "toward" — return the nearer face so
        // the caller gets a stable answer instead of an arbitrary one that flips as the train slides.
        AABB carriage = new AABB(0, 65, 0, 9, 70, 10);
        assertEquals(Direction.NORTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(4, 63, 1), carriage), "nearer the −Z face");
        assertEquals(Direction.SOUTH,
            BlockSourcePolicy.horizontalDirToward(new BlockPos(4, 63, 8), carriage), "nearer the +Z face");
    }
}
