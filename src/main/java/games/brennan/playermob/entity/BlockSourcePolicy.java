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
import net.minecraft.world.level.block.FallingBlock;
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

    /**
     * Effective number of bridge blocks {@code backpack} can supply: each placeable
     * building block counts once, and each craftable log counts as 4 (it crafts into
     * 4 planks). Lets recovery work out whether it has gathered enough to climb back
     * on before it stops collecting.
     */
    public static int bridgeBlockCount(Container backpack) {
        int total = 0;
        for (int i = 0; i < backpack.getContainerSize(); i++) {
            ItemStack stack = backpack.getItem(i);
            if (isCraftableLog(stack)) {
                total += stack.getCount() * 4;
            } else if (ItemPickupPolicy.isBuildingBlock(stack)) {
                total += stack.getCount();
            }
        }
        return total;
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
     * A soft, <em>non-structural</em> block worth breaking just to clear the way up: tree
     * leaves and replaceable plants (grass/ferns/vines/snow). A recovering mob that climbs
     * into a tree must punch through the foliage rather than stall against it.
     *
     * <p>Deliberately excludes the hand-breakable solids (dirt/sand/cobble/planks): those are
     * the mob's own bridge/stair blocks and real footing — flagging them would make the mob
     * break the very step it just placed (an infinite place-then-rebreak loop). The caller
     * also excludes the protected track separately.</p>
     */
    public static boolean isClearableObstruction(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE);
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

    /**
     * True if {@code state}'s block falls under gravity (sand, gravel, concrete
     * powder, anvils…). Recovery bridges with these by jump-stacking a pillar
     * <em>under</em> the mob rather than building a staircase out into the air,
     * which a falling block can't form.
     */
    public static boolean isGravityBlock(BlockState state) {
        return state.getBlock() instanceof FallingBlock;
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

    /**
     * The horizontal {@link Direction} a mob beside the track should step to get <em>onto</em>
     * {@code box} — i.e. across the carriage's near Z face.
     *
     * <p><b>Always a Z step, never X.</b> This used to pick the dominant axis toward the box centre,
     * which is wrong for a train: carriages are long in X and the line runs along X, so for any mob
     * not near the carriage's X-centre — the common case — {@code |dx| > |dz|} and it returned
     * EAST/WEST, <em>parallel to the rails</em>. Bridging then built a staircase running alongside
     * the track that never got closer to the deck, and because the train slides in +X the chosen
     * direction flipped sign as it passed, so the mob twitched between two cells and appeared to
     * stall on its own stairs. The way aboard is across the near face, which is purely a Z move —
     * the same reasoning {@code approachPoint} and {@code offBedColumn} already use.</p>
     */
    public static Direction horizontalDirToward(BlockPos foot, AABB box) {
        double z = foot.getZ() + 0.5;
        if (z < box.minZ) return Direction.SOUTH;      // outside the −Z face → step +Z toward it
        if (z > box.maxZ) return Direction.NORTH;      // outside the +Z face → step −Z toward it
        // Already within the carriage's Z-span (on the bed): head for the nearer face so the caller
        // still gets a sensible, stable direction rather than an arbitrary one.
        return z - box.minZ <= box.maxZ - z ? Direction.NORTH : Direction.SOUTH;
    }
}
