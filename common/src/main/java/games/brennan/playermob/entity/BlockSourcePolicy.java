package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.Set;

/**
 * Decides <em>where the bridge blocks come from</em> and <em>where the next one
 * goes</em> when a {@link PlayerMobEntity} is recovering back onto a Dungeon
 * Train carriage (see {@code TrainRecoveryGoal}).
 *
 * <p>Stateless and {@code Mob}-agnostic — every method is static and operates
 * purely on {@link Container}/{@link BlockState}/{@link BlockPos}/{@link AABB},
 * so the interesting logic is unit-testable without a live server (mirrors
 * {@link ItemPickupPolicy}). The side effects it informs — actually breaking,
 * placing, and crafting blocks — live on the goal/entity because they need a
 * {@code ServerLevel} and mutate the mob.</p>
 *
 * <h2>Acquisition order</h2>
 * <ol>
 *   <li><b>Backpack</b> — {@link #firstPlaceableSlot} returns a placeable
 *       {@link net.minecraft.world.item.BlockItem} stack the mob already carries
 *       (reuses {@link ItemPickupPolicy#isBuildingBlock}).</li>
 *   <li><b>Harvest nearby</b> — {@link #isEasyBlock} marks cheap, placeable
 *       blocks worth breaking for materials: hand-breakable dirt/sand/gravel/clay
 *       and {@link BlockTags#LOGS}, plus stone-class blocks <em>only</em> when the
 *       mob holds a pickaxe. {@link #isProtectedTrackBlock} excludes the train's
 *       own bed/rails so recovery never griefs the track.</li>
 *   <li><b>Craft</b> — logs harvested in step 2 become planks via the shared
 *       {@code CraftingLadder} (handled in the goal, not here).</li>
 * </ol>
 */
public final class BlockSourcePolicy {

    private BlockSourcePolicy() {}

    // ---- Backpack placeable blocks ---------------------------------------

    /** True if the backpack holds any placeable building block. */
    public static boolean hasPlaceableBlock(Container backpack) {
        return firstPlaceableSlot(backpack) >= 0;
    }

    /**
     * Slot index of the first placeable building block in {@code backpack}, or
     * {@code -1} if it holds none. "Placeable" reuses
     * {@link ItemPickupPolicy#isBuildingBlock} (backed by a {@code BlockItem}).
     */
    public static int firstPlaceableSlot(Container backpack) {
        for (int i = 0; i < backpack.getContainerSize(); i++) {
            if (ItemPickupPolicy.isBuildingBlock(backpack.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    // ---- Logs → planks (recovery's craft step) ---------------------------

    /**
     * Each base log/stem the mob can turn into 4 matching planks without a table.
     * Recovery prefers crafting harvested logs into planks (a 4× yield) before
     * bridging — both more bridge blocks per trip and more player-like. Stripped
     * logs and wood/hyphae aren't mapped: they're placed directly as-is (they're
     * still {@link net.minecraft.world.item.BlockItem}s) rather than crafted.
     */
    private static final Map<Item, Item> LOG_TO_PLANKS = Map.ofEntries(
        Map.entry(Items.OAK_LOG, Items.OAK_PLANKS),
        Map.entry(Items.SPRUCE_LOG, Items.SPRUCE_PLANKS),
        Map.entry(Items.BIRCH_LOG, Items.BIRCH_PLANKS),
        Map.entry(Items.JUNGLE_LOG, Items.JUNGLE_PLANKS),
        Map.entry(Items.ACACIA_LOG, Items.ACACIA_PLANKS),
        Map.entry(Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS),
        Map.entry(Items.MANGROVE_LOG, Items.MANGROVE_PLANKS),
        Map.entry(Items.CHERRY_LOG, Items.CHERRY_PLANKS),
        Map.entry(Items.CRIMSON_STEM, Items.CRIMSON_PLANKS),
        Map.entry(Items.WARPED_STEM, Items.WARPED_PLANKS));

    /** True if {@code stack} is a log/stem {@link #planksFromLog} can convert. */
    public static boolean isCraftableLog(ItemStack stack) {
        return LOG_TO_PLANKS.containsKey(stack.getItem());
    }

    /**
     * The 4 planks a single log/stem crafts into (no table needed), or
     * {@link ItemStack#EMPTY} if {@code stack} isn't a mappable log — mirrors the
     * vanilla 1-log → 4-planks recipe per wood type.
     */
    public static ItemStack planksFromLog(ItemStack stack) {
        Item planks = LOG_TO_PLANKS.get(stack.getItem());
        return planks == null ? ItemStack.EMPTY : new ItemStack(planks, 4);
    }

    // ---- Gather candidates -----------------------------------------------

    /**
     * Cheap blocks the mob can break by hand and place straight back down. Kept
     * deliberately small — recovery wants a quick block, not a quarry. Logs are
     * covered separately via {@link BlockTags#LOGS} (so every wood type counts)
     * and double as the crafting feedstock for planks.
     */
    private static final Set<Block> HAND_BREAKABLE = Set.of(
        Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.PODZOL,
        Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.SAND, Blocks.RED_SAND,
        Blocks.GRAVEL, Blocks.CLAY, Blocks.MUD);

    /**
     * Placeable stone-class blocks — only worth targeting when the mob holds a
     * pickaxe (otherwise they drop nothing). See {@link #isEasyBlock}.
     */
    private static final Set<Block> STONE_CLASS = Set.of(
        Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
        Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF,
        Blocks.NETHERRACK, Blocks.SANDSTONE, Blocks.RED_SANDSTONE);

    /** Hand-breakable, directly-placeable blocks (dirt/sand/gravel/clay family + any log). */
    public static boolean isHandBreakable(BlockState state) {
        return HAND_BREAKABLE.contains(state.getBlock()) || state.is(BlockTags.LOGS);
    }

    /** Stone-class placeable blocks (need a pickaxe to drop). */
    public static boolean isStoneClass(BlockState state) {
        return STONE_CLASS.contains(state.getBlock());
    }

    /**
     * Whether {@code state} is worth harvesting for a bridge block: always for
     * hand-breakable blocks, and for stone-class blocks only when the mob holds
     * a pickaxe ({@code hasPickaxe}).
     */
    public static boolean isEasyBlock(BlockState state, boolean hasPickaxe) {
        return isHandBreakable(state) || (hasPickaxe && isStoneClass(state));
    }

    /**
     * The train's own structure — the stone-brick bed and any rail — which
     * recovery must never break (gather) or overwrite (place). Keeps a recovering
     * mob from chewing up the track it's trying to climb back onto.
     */
    public static boolean isProtectedTrackBlock(BlockState state) {
        return state.is(BlockTags.RAILS)
            || state.is(Blocks.STONE_BRICKS)
            || state.is(Blocks.STONE_BRICK_STAIRS)
            || state.is(Blocks.STONE_BRICK_SLAB);
    }

    // ---- Staircase math --------------------------------------------------

    /**
     * The next block position to place so the mob climbs one step toward the
     * carriage, or {@code null} if its footing is already level with the
     * carriage's base layer (the final block-high gap is jumped, not bridged).
     *
     * <p>Heuristic "staircase up": the mob stands with feet at {@code foot}
     * (walking surface {@code foot.getY()}); placing a block in the adjacent
     * column toward the carriage at {@code foot.getY()} yields a surface one
     * block higher to step onto. Re-called each tick as the mob climbs, so the
     * placement rises with it. The carriage is moving (~0.1 block/tick at the
     * 2 m/s default), so the caller re-resolves {@code carriageBox} every tick.
     * The caller validates the returned position (replaceable, not inside the
     * carriage box or the protected track, unoccupied) before placing.</p>
     */
    public static BlockPos nextBridgePos(BlockPos foot, AABB carriageBox) {
        int lipY = Mth.floor(carriageBox.minY);
        if (foot.getY() >= lipY) {
            return null;
        }
        return foot.relative(horizontalDirToward(foot, carriageBox));
    }

    /** The dominant horizontal {@link Direction} from {@code foot} toward {@code box}'s centre. */
    static Direction horizontalDirToward(BlockPos foot, AABB box) {
        double dx = (box.minX + box.maxX) / 2.0 - (foot.getX() + 0.5);
        double dz = (box.minZ + box.maxZ) / 2.0 - (foot.getZ() + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
