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
 * <p><b>The ladder</b> (evaluated top-to-bottom, first satisfiable step wins,
 * re-evaluated after every craft):</p>
 * <ol>
 *   <li>wooden sword   ← 2 planks + 1 stick      <i>(skip if the mob owns any sword)</i></li>
 *   <li>stone sword    ← 2 cobblestone + 1 stick <i>(skip if it owns a stone-tier-or-better sword)</i></li>
 *   <li>wooden pickaxe ← 3 planks + 2 sticks     <i>(skip if it owns any pickaxe)</i></li>
 *   <li>stone pickaxe  ← 3 cobblestone + 2 sticks<i>(skip if it owns a stone-tier-or-better pickaxe)</i></li>
 *   <li>4 sticks       ← 2 planks                <i>(only if sticks &lt; 2 and planks ≥ 2)</i></li>
 *   <li>4 planks       ← 1 log                   <i>(only if planks &lt; 5 and it has a log)</i></li>
 * </ol>
 *
 * <p>The ownership guards plus the stick/plank thresholds make the ladder
 * <b>terminate</b>: the mob ends up holding a wooden+stone sword and pickaxe
 * with a small plank/stick reserve, after which {@link #nextCraft} returns
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

    /** Every sword tier — guards step 1 (don't craft a wooden sword if any sword is owned). */
    private static final Set<Item> ANY_SWORD = Set.of(
        Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
        Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    /** Stone-tier-or-better swords — guards step 2 (don't downgrade to a stone sword). */
    private static final Set<Item> STONE_PLUS_SWORD = Set.of(
        Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    private static final Set<Item> ANY_PICKAXE = Set.of(
        Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
        Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

    private static final Set<Item> STONE_PLUS_PICKAXE = Set.of(
        Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

    // ---- Public API -------------------------------------------------------

    /**
     * A single craft the ladder wants to perform: the {@code costs} to remove
     * from the backpack and the {@code output} to produce. {@code name} is a
     * stable label for logging/tests.
     */
    public record CraftAction(String name, List<Cost> costs, ItemStack output) {}

    /** One ingredient requirement: take {@code count} items matching {@code match}. */
    public record Cost(Predicate<ItemStack> match, int count) {}

    /**
     * Decide the next craft for a mob with the given {@code backpack} contents
     * and already-owned tools ({@code ownedTools} — typically the mob's
     * currently-equipped items, which can't be read from the backpack).
     *
     * <p>Pure: does not mutate {@code backpack}. Call {@link #apply} to perform
     * the returned action.</p>
     *
     * @return the next {@link CraftAction}, or empty if nothing should be crafted.
     */
    public static Optional<CraftAction> nextCraft(Container backpack, Set<Item> ownedTools) {
        int logs = countMatching(backpack, IS_LOG);
        int planks = countMatching(backpack, IS_PLANKS);
        int sticks = countMatching(backpack, IS_STICK);
        int cobble = countMatching(backpack, IS_COBBLE);

        boolean ownsSword = ownsAny(backpack, ownedTools, ANY_SWORD);
        boolean ownsStoneSword = ownsAny(backpack, ownedTools, STONE_PLUS_SWORD);
        boolean ownsPickaxe = ownsAny(backpack, ownedTools, ANY_PICKAXE);
        boolean ownsStonePickaxe = ownsAny(backpack, ownedTools, STONE_PLUS_PICKAXE);

        // Step 1 — wooden sword.
        if (!ownsSword && planks >= 2 && sticks >= 1) {
            return Optional.of(new CraftAction("wooden_sword",
                List.of(new Cost(IS_PLANKS, 2), new Cost(IS_STICK, 1)),
                new ItemStack(Items.WOODEN_SWORD)));
        }
        // Step 2 — stone sword.
        if (!ownsStoneSword && cobble >= 2 && sticks >= 1) {
            return Optional.of(new CraftAction("stone_sword",
                List.of(new Cost(IS_COBBLE, 2), new Cost(IS_STICK, 1)),
                new ItemStack(Items.STONE_SWORD)));
        }
        // Step 3 — wooden pickaxe.
        if (!ownsPickaxe && planks >= 3 && sticks >= 2) {
            return Optional.of(new CraftAction("wooden_pickaxe",
                List.of(new Cost(IS_PLANKS, 3), new Cost(IS_STICK, 2)),
                new ItemStack(Items.WOODEN_PICKAXE)));
        }
        // Step 4 — stone pickaxe.
        if (!ownsStonePickaxe && cobble >= 3 && sticks >= 2) {
            return Optional.of(new CraftAction("stone_pickaxe",
                List.of(new Cost(IS_COBBLE, 3), new Cost(IS_STICK, 2)),
                new ItemStack(Items.STONE_PICKAXE)));
        }
        // Step 5 — sticks (keep a minimum reserve).
        if (sticks < 2 && planks >= 2) {
            return Optional.of(new CraftAction("sticks",
                List.of(new Cost(IS_PLANKS, 2)),
                new ItemStack(Items.STICK, 4)));
        }
        // Step 6 — planks (top up toward the reserve from logs).
        if (planks < 5 && logs >= 1) {
            return Optional.of(new CraftAction("planks",
                List.of(new Cost(IS_LOG, 1)),
                new ItemStack(Items.OAK_PLANKS, 4)));
        }
        return Optional.empty();
    }

    /**
     * Remove {@code action}'s costs from {@code backpack}. The caller decides
     * where the {@link CraftAction#output} goes (equip it, or stash it back via
     * {@code EquipmentEvaluator.addToContainer}).
     */
    public static void apply(Container backpack, CraftAction action) {
        for (Cost cost : action.costs()) {
            removeMatching(backpack, cost.match(), cost.count());
        }
        backpack.setChanged();
    }

    // ---- Helpers ----------------------------------------------------------

    private static int countMatching(Container c, Predicate<ItemStack> match) {
        int total = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && match.test(s)) total += s.getCount();
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
