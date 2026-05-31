package games.brennan.playermob.entity;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The PlayerMob's curated crafting "ladder" — a small, ordered set of hardcoded
 * recipes the mob walks up while idle (see {@code CraftItemsGoal}).
 *
 * <p>We deliberately do <b>not</b> drive this from the live vanilla
 * {@code RecipeManager}. A curated ladder is bounded, deterministic, and
 * unit-testable as a pure inventory transform — and it tells a clear
 * <i>gather → craft → equip → fight better</i> story instead of letting the mob
 * craft arbitrary junk.</p>
 *
 * <p><b>Grid size matters</b>, just like for a player: 2×2 recipes craft "in
 * hand" with no bench, but 3×3 recipes (the tools) require a <b>crafting
 * table</b>. {@link #nextCraft} takes a {@code tableInReach} flag; tool steps are
 * only offered when a table is available, and the mob makes/places one itself
 * (see {@code CraftItemsGoal}).</p>
 *
 * <p><b>The ladder</b> (evaluated top-to-bottom, first satisfiable step wins,
 * re-evaluated after every craft):</p>
 * <ol>
 *   <li>wooden sword   ← 2 planks + 1 stick      <i>(table; skip if it owns any sword)</i></li>
 *   <li>stone sword    ← 2 cobblestone + 1 stick <i>(table; skip if it owns a stone-tier-or-better sword)</i></li>
 *   <li>wooden pickaxe ← 3 planks + 2 sticks     <i>(table; skip if it owns any pickaxe)</i></li>
 *   <li>stone pickaxe  ← 3 cobblestone + 2 sticks<i>(table; skip if it owns a stone-tier-or-better pickaxe)</i></li>
 *   <li>wooden axe     ← 3 planks + 2 sticks     <i>(table; skip if it owns any axe)</i></li>
 *   <li>stone axe      ← 3 cobblestone + 2 sticks<i>(table; skip if it owns a stone-tier-or-better axe)</i></li>
 *   <li>crafting table ← 4 planks                <i>(in hand; only when a tool is wanted, no table is in reach, and it holds no table)</i></li>
 *   <li>4 sticks       ← 2 planks                <i>(in hand; only if sticks &lt; 2 and planks ≥ 2)</i></li>
 *   <li>4 planks       ← 1 log                   <i>(in hand; only if planks &lt; 5 and it has a log)</i></li>
 * </ol>
 *
 * <p>The ownership guards plus the stick/plank thresholds make the ladder
 * <b>terminate</b>: the mob ends up holding a wooden+stone sword, pickaxe, and
 * axe with a small plank/stick reserve, after which {@link #nextCraft} returns
 * empty and crafting goes idle (mining continues). Coal and raw iron are
 * gathered as loot but are not craftable here — there is no smelting in v1.</p>
 *
 * <p>Inputs are matched against explicit vanilla item sets ({@link #LOGS},
 * {@link #PLANKS}) rather than item tags, so any wood species feeds the ladder
 * <em>and</em> the logic stays unit-testable without a datapack/tag reload (tags
 * aren't bound in a bare test bootstrap). The produced planks are canonical
 * {@link Items#OAK_PLANKS}. Stateless — every method is static. Unit-tested in
 * {@code CraftingLadderTest}.</p>
 */
public final class CraftingLadder {

    private CraftingLadder() {}

    // ---- Input item sets --------------------------------------------------

    /**
     * Every vanilla block that {@code MineBlocksGoal} can harvest and that crafts
     * into planks — i.e. the item forms of {@code minecraft:logs}: logs, wood,
     * stems, hyphae, and all stripped variants. Mirrors what the goal targets via
     * {@code BlockTags.LOGS}; kept as an explicit set so the ladder needs no tag
     * binding.
     */
    private static final Set<Item> LOGS = Set.of(
        Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
        Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
        Items.CRIMSON_STEM, Items.WARPED_STEM,
        Items.STRIPPED_OAK_LOG, Items.STRIPPED_SPRUCE_LOG, Items.STRIPPED_BIRCH_LOG,
        Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_DARK_OAK_LOG,
        Items.STRIPPED_MANGROVE_LOG, Items.STRIPPED_CHERRY_LOG,
        Items.STRIPPED_CRIMSON_STEM, Items.STRIPPED_WARPED_STEM,
        Items.OAK_WOOD, Items.SPRUCE_WOOD, Items.BIRCH_WOOD, Items.JUNGLE_WOOD,
        Items.ACACIA_WOOD, Items.DARK_OAK_WOOD, Items.MANGROVE_WOOD, Items.CHERRY_WOOD,
        Items.CRIMSON_HYPHAE, Items.WARPED_HYPHAE,
        Items.STRIPPED_OAK_WOOD, Items.STRIPPED_SPRUCE_WOOD, Items.STRIPPED_BIRCH_WOOD,
        Items.STRIPPED_JUNGLE_WOOD, Items.STRIPPED_ACACIA_WOOD, Items.STRIPPED_DARK_OAK_WOOD,
        Items.STRIPPED_MANGROVE_WOOD, Items.STRIPPED_CHERRY_WOOD,
        Items.STRIPPED_CRIMSON_HYPHAE, Items.STRIPPED_WARPED_HYPHAE);

    /** Every vanilla plank variant — counts toward the ladder's "planks". */
    private static final Set<Item> PLANKS = Set.of(
        Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
        Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
        Items.CRIMSON_PLANKS, Items.WARPED_PLANKS, Items.BAMBOO_PLANKS);

    // ---- Input matchers ---------------------------------------------------

    private static final Predicate<ItemStack> IS_LOG = s -> LOGS.contains(s.getItem());
    private static final Predicate<ItemStack> IS_PLANKS = s -> PLANKS.contains(s.getItem());
    private static final Predicate<ItemStack> IS_STICK = s -> s.is(Items.STICK);
    private static final Predicate<ItemStack> IS_COBBLE = s -> s.is(Items.COBBLESTONE);

    // ---- Tool ownership sets ---------------------------------------------

    private static final Set<Item> ANY_SWORD = Set.of(
        Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
        Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);
    private static final Set<Item> STONE_PLUS_SWORD = Set.of(
        Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    private static final Set<Item> ANY_PICKAXE = Set.of(
        Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
        Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
    private static final Set<Item> STONE_PLUS_PICKAXE = Set.of(
        Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

    private static final Set<Item> ANY_AXE = Set.of(
        Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
        Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);
    private static final Set<Item> STONE_PLUS_AXE = Set.of(
        Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);

    // ---- Public API -------------------------------------------------------

    /**
     * A single craft the ladder wants to perform: the {@code costs} to remove
     * from the backpack and the {@code output} to produce. {@code needsTable} is
     * true for the 3×3 tool recipes (the caller must be at a crafting table) and
     * also flags which outputs are gear to equip vs. materials to stash.
     */
    public record CraftAction(String name, List<Cost> costs, ItemStack output, boolean needsTable) {}

    /** One ingredient requirement: take {@code count} items matching {@code match}. */
    public record Cost(Predicate<ItemStack> match, int count) {}

    /**
     * Decide the next craft for a mob with the given {@code backpack} contents,
     * already-owned tools ({@code ownedTools} — typically equipped items), and
     * whether a crafting table is currently within reach ({@code tableInReach}).
     *
     * <p>Pure: does not mutate {@code backpack}. Call {@link #apply} to perform
     * the returned action.</p>
     */
    public static Optional<CraftAction> nextCraft(Container backpack, Set<Item> ownedTools, boolean tableInReach) {
        Census c = census(backpack, ownedTools);

        // Tool recipes (3×3 — require a crafting table).
        if (tableInReach) {
            if (!c.ownsSword && c.planks >= 2 && c.sticks >= 1)
                return tool("wooden_sword", Items.WOODEN_SWORD, cost(IS_PLANKS, 2), cost(IS_STICK, 1));
            if (!c.ownsStoneSword && c.cobble >= 2 && c.sticks >= 1)
                return tool("stone_sword", Items.STONE_SWORD, cost(IS_COBBLE, 2), cost(IS_STICK, 1));
            if (!c.ownsPickaxe && c.planks >= 3 && c.sticks >= 2)
                return tool("wooden_pickaxe", Items.WOODEN_PICKAXE, cost(IS_PLANKS, 3), cost(IS_STICK, 2));
            if (!c.ownsStonePickaxe && c.cobble >= 3 && c.sticks >= 2)
                return tool("stone_pickaxe", Items.STONE_PICKAXE, cost(IS_COBBLE, 3), cost(IS_STICK, 2));
            if (!c.ownsAxe && c.planks >= 3 && c.sticks >= 2)
                return tool("wooden_axe", Items.WOODEN_AXE, cost(IS_PLANKS, 3), cost(IS_STICK, 2));
            if (!c.ownsStoneAxe && c.cobble >= 3 && c.sticks >= 2)
                return tool("stone_axe", Items.STONE_AXE, cost(IS_COBBLE, 3), cost(IS_STICK, 2));
        }

        // Crafting table (2×2, in hand) — make one when a tool is wanted but no
        // table is reachable and the mob isn't already carrying one to place.
        if (!tableInReach && !c.holdsTable && c.planks >= 4 && wantsAnyTool(c)) {
            return inHand("crafting_table", new ItemStack(Items.CRAFTING_TABLE), cost(IS_PLANKS, 4));
        }
        // Sticks (keep a minimum reserve).
        if (c.sticks < 2 && c.planks >= 2) {
            return inHand("sticks", new ItemStack(Items.STICK, 4), cost(IS_PLANKS, 2));
        }
        // Planks (top up toward the reserve from logs).
        if (c.planks < 5 && c.logs >= 1) {
            return inHand("planks", new ItemStack(Items.OAK_PLANKS, 4), cost(IS_LOG, 1));
        }
        return Optional.empty();
    }

    /**
     * True if the mob has the materials for, and doesn't already own, some tool —
     * ignoring whether a table is available. Drives {@code CraftItemsGoal}'s
     * decision to place a crafting table it's carrying.
     */
    public static boolean wantsTable(Container backpack, Set<Item> ownedTools) {
        return wantsAnyTool(census(backpack, ownedTools));
    }

    /**
     * Remove {@code action}'s costs from {@code backpack}. The caller decides
     * where the {@link CraftAction#output} goes (equip a tool, or stash a
     * material/table via {@code EquipmentEvaluator.addToContainer}).
     */
    public static void apply(Container backpack, CraftAction action) {
        for (Cost cost : action.costs()) {
            removeMatching(backpack, cost.match(), cost.count());
        }
        backpack.setChanged();
    }

    // ---- Internal ladder model -------------------------------------------

    /** Snapshot of everything the ladder's guards depend on. */
    private record Census(int logs, int planks, int sticks, int cobble,
                          boolean ownsSword, boolean ownsStoneSword,
                          boolean ownsPickaxe, boolean ownsStonePickaxe,
                          boolean ownsAxe, boolean ownsStoneAxe,
                          boolean holdsTable) {}

    private static Census census(Container backpack, Set<Item> owned) {
        return new Census(
            countMatching(backpack, IS_LOG),
            countMatching(backpack, IS_PLANKS),
            countMatching(backpack, IS_STICK),
            countMatching(backpack, IS_COBBLE),
            ownsAny(backpack, owned, ANY_SWORD),
            ownsAny(backpack, owned, STONE_PLUS_SWORD),
            ownsAny(backpack, owned, ANY_PICKAXE),
            ownsAny(backpack, owned, STONE_PLUS_PICKAXE),
            ownsAny(backpack, owned, ANY_AXE),
            ownsAny(backpack, owned, STONE_PLUS_AXE),
            countItem(backpack, Items.CRAFTING_TABLE) > 0);
    }

    /** Mirrors the tool guards in {@link #nextCraft}, ignoring table access. */
    private static boolean wantsAnyTool(Census c) {
        return (!c.ownsSword && c.planks >= 2 && c.sticks >= 1)
            || (!c.ownsStoneSword && c.cobble >= 2 && c.sticks >= 1)
            || (!c.ownsPickaxe && c.planks >= 3 && c.sticks >= 2)
            || (!c.ownsStonePickaxe && c.cobble >= 3 && c.sticks >= 2)
            || (!c.ownsAxe && c.planks >= 3 && c.sticks >= 2)
            || (!c.ownsStoneAxe && c.cobble >= 3 && c.sticks >= 2);
    }

    private static Optional<CraftAction> tool(String name, Item output, Cost... costs) {
        return Optional.of(new CraftAction(name, List.of(costs), new ItemStack(output), true));
    }

    private static Optional<CraftAction> inHand(String name, ItemStack output, Cost... costs) {
        return Optional.of(new CraftAction(name, List.of(costs), output, false));
    }

    private static Cost cost(Predicate<ItemStack> match, int count) {
        return new Cost(match, count);
    }

    // ---- Container helpers ------------------------------------------------

    private static int countMatching(Container c, Predicate<ItemStack> match) {
        int total = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && match.test(s)) total += s.getCount();
        }
        return total;
    }

    private static int countItem(Container c, Item item) {
        int total = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
        }
        return total;
    }

    private static boolean ownsAny(Container c, Set<Item> owned, Set<Item> targets) {
        for (Item t : targets) {
            if (owned.contains(t)) return true;
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && targets.contains(s.getItem())) return true;
        }
        return false;
    }

    /**
     * Take up to {@code count} items matching {@code match} out of the container,
     * walking slots forward and clearing any slot that empties out.
     */
    private static void removeMatching(Container c, Predicate<ItemStack> match, int count) {
        int remaining = count;
        for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !match.test(s)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
            if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
        }
    }
}
