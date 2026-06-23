package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link MiningMath#breakTicks} — the shared, hardness-and-tool-scaled break
 * duration used by recovery gathering and the Dungeon-Train dig-through reflex.
 *
 * <p>{@link Bootstrap} boots the vanilla registries so {@link Blocks}/{@link Items} resolve; block
 * hardness and tool destroy-speed are constants on the block/item, so an {@link EmptyBlockGetter}
 * suffices (no live level needed).</p>
 */
class MiningMathTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void durationStaysWithinBounds() {
        int dirt = MiningMath.breakTicks(Blocks.DIRT.defaultBlockState(),
            EmptyBlockGetter.INSTANCE, BlockPos.ZERO, ItemStack.EMPTY);
        assertTrue(dirt >= MiningMath.MIN_BREAK_TICKS && dirt <= MiningMath.MAX_BREAK_TICKS,
            "dirt breaks within the clamp bounds");

        // Obsidian (hardness 50) by hand is hopelessly slow → clamps to the ceiling.
        int obsidian = MiningMath.breakTicks(Blocks.OBSIDIAN.defaultBlockState(),
            EmptyBlockGetter.INSTANCE, BlockPos.ZERO, ItemStack.EMPTY);
        assertEquals(MiningMath.MAX_BREAK_TICKS, obsidian, "a very hard block clamps to the ceiling");
    }

    @Test
    void harderBlockTakesLongerByHand() {
        int dirt = MiningMath.breakTicks(Blocks.DIRT.defaultBlockState(),       // hardness 0.5
            EmptyBlockGetter.INSTANCE, BlockPos.ZERO, ItemStack.EMPTY);
        int stone = MiningMath.breakTicks(Blocks.STONE.defaultBlockState(),     // hardness 1.5
            EmptyBlockGetter.INSTANCE, BlockPos.ZERO, ItemStack.EMPTY);
        assertTrue(stone > dirt, "stone is harder than dirt → longer break");
    }

    @Test
    void rightToolSpeedsUpTheBreak() {
        var stone = Blocks.STONE.defaultBlockState();
        int byHand = MiningMath.breakTicks(stone, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, ItemStack.EMPTY);
        int withPick = MiningMath.breakTicks(stone, EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
            new ItemStack(Items.DIAMOND_PICKAXE));
        assertTrue(withPick <= byHand, "a pickaxe never breaks stone slower than a fist");
    }
}
