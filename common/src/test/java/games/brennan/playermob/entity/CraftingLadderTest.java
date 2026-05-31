package games.brennan.playermob.entity;

import games.brennan.playermob.entity.CraftingLadder.CraftAction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the curated {@link CraftingLadder}.
 *
 * <p>Like {@link EquipmentEvaluatorTest}, touching vanilla {@link ItemStack} /
 * {@link SimpleContainer} requires {@link Bootstrap#bootStrap()} first — else the
 * first registry reference throws "Not bootstrapped". The ladder matches inputs
 * by explicit item set (not tags), so it needs no datapack/tag reload.</p>
 *
 * <p>The {@code tableInReach} argument models whether a crafting table is
 * available: tools (3×3 recipes) are only offered when it's {@code true}.</p>
 */
class CraftingLadderTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Build a backpack from (item, count) pairs. */
    private static SimpleContainer backpack(Object... itemsAndCounts) {
        SimpleContainer c = new SimpleContainer(8);
        int slot = 0;
        for (int i = 0; i < itemsAndCounts.length; i += 2) {
            Item item = (Item) itemsAndCounts[i];
            int count = (int) itemsAndCounts[i + 1];
            c.setItem(slot++, new ItemStack(item, count));
        }
        return c;
    }

    private static CraftAction craft(SimpleContainer bp, Set<Item> owned, boolean tableInReach) {
        Optional<CraftAction> action = CraftingLadder.nextCraft(bp, owned, tableInReach);
        assertTrue(action.isPresent(), "expected a craft action");
        return action.get();
    }

    @Test
    void emptyBackpackCraftsNothing() {
        assertTrue(CraftingLadder.nextCraft(new SimpleContainer(8), Set.of(), true).isEmpty(),
            "Nothing to work with → no craft");
    }

    @Test
    void singleLogCraftsPlanks() {
        CraftAction a = craft(backpack(Items.OAK_LOG, 1), Set.of(), false);
        assertEquals("planks", a.name());
        assertEquals(Items.OAK_PLANKS, a.output().getItem());
        assertEquals(4, a.output().getCount(), "1 log → 4 planks");
        assertFalse(a.needsTable(), "planks craft in hand");
    }

    @Test
    void planksWithoutSticksCraftsSticks() {
        // 4 planks, 0 sticks → no tool is craftable (needs sticks); make sticks first.
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 4), Set.of(), true);
        assertEquals("sticks", a.name());
        assertEquals(Items.STICK, a.output().getItem());
        assertEquals(4, a.output().getCount());
    }

    @Test
    void planksAndStickCraftWoodenSwordAtTable() {
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 2, Items.STICK, 1), Set.of(), true);
        assertEquals("wooden_sword", a.name());
        assertEquals(Items.WOODEN_SWORD, a.output().getItem());
        assertTrue(a.needsTable(), "a sword needs a crafting table");
    }

    @Test
    void toolWithheldWithoutTableTriggersTableCraft() {
        // Same materials, but no table in reach → the mob must craft a table first.
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 4, Items.STICK, 1), Set.of(), false);
        assertEquals("crafting_table", a.name());
        assertEquals(Items.CRAFTING_TABLE, a.output().getItem());
        assertFalse(a.needsTable(), "a table is crafted in hand (2x2)");
    }

    @Test
    void doesNotCraftSecondTableWhenAlreadyHoldingOne() {
        // Holds a table already (to place) → don't craft another; fall through to sticks.
        CraftAction a = craft(backpack(
                Items.CRAFTING_TABLE, 1, Items.OAK_PLANKS, 4, Items.STICK, 1),
            Set.of(), false);
        assertNotEquals("crafting_table", a.name(), "shouldn't craft a 2nd table while carrying one");
        assertEquals("sticks", a.name());
    }

    @Test
    void ownedSwordSkipsToPickaxe() {
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 3, Items.STICK, 2),
            Set.of(Items.WOODEN_SWORD), true);
        assertEquals("wooden_pickaxe", a.name());
        assertEquals(Items.WOODEN_PICKAXE, a.output().getItem());
    }

    @Test
    void backpackSwordAlsoGuardsStepOne() {
        CraftAction a = craft(backpack(
                Items.WOODEN_SWORD, 1, Items.OAK_PLANKS, 3, Items.STICK, 2),
            Set.of(), true);
        assertEquals("wooden_pickaxe", a.name(),
            "A sword sitting in the backpack should still skip the sword step");
    }

    @Test
    void cobbleAndStickCraftStoneSwordAtTable() {
        CraftAction a = craft(backpack(Items.COBBLESTONE, 2, Items.STICK, 1), Set.of(), true);
        assertEquals("stone_sword", a.name());
        assertEquals(Items.STONE_SWORD, a.output().getItem());
    }

    @Test
    void craftsAxeOnceSwordsAndPicksOwned() {
        // Owns both swords + both picks → next tool is the axe.
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 3, Items.STICK, 2),
            Set.of(Items.WOODEN_SWORD, Items.STONE_SWORD,
                   Items.WOODEN_PICKAXE, Items.STONE_PICKAXE),
            true);
        assertEquals("wooden_axe", a.name());
        assertEquals(Items.WOODEN_AXE, a.output().getItem());
    }

    @Test
    void tagAgnosticBirchFeedsTheLadder() {
        CraftAction logToPlanks = craft(backpack(Items.BIRCH_LOG, 1), Set.of(), false);
        assertEquals("planks", logToPlanks.name());

        CraftAction birchSword = craft(backpack(Items.BIRCH_PLANKS, 2, Items.STICK, 1), Set.of(), true);
        assertEquals("wooden_sword", birchSword.name());
    }

    @Test
    void applyConsumesExactInputs() {
        // 4 planks + 4 sticks at a table → wooden sword consumes 2 planks + 1 stick.
        SimpleContainer bp = backpack(Items.OAK_PLANKS, 4, Items.STICK, 4);
        CraftAction a = craft(bp, Set.of(), true);
        assertEquals("wooden_sword", a.name());

        CraftingLadder.apply(bp, a);

        assertEquals(2, countOf(bp, Items.OAK_PLANKS), "2 planks consumed");
        assertEquals(3, countOf(bp, Items.STICK), "1 stick consumed");
        assertEquals(0, countOf(bp, Items.WOODEN_SWORD),
            "apply() only consumes inputs — the caller places the output");
    }

    @Test
    void fullyGearedCraftsNothing() {
        SimpleContainer bp = backpack(Items.OAK_PLANKS, 5, Items.STICK, 2);
        Set<Item> owned = Set.of(
            Items.WOODEN_SWORD, Items.STONE_SWORD,
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE,
            Items.WOODEN_AXE, Items.STONE_AXE);
        assertFalse(CraftingLadder.nextCraft(bp, owned, true).isPresent(),
            "Fully geared with reserves → ladder is done");
    }

    @Test
    void wantsTableReflectsToolAvailability() {
        assertTrue(CraftingLadder.wantsTable(backpack(Items.OAK_PLANKS, 2, Items.STICK, 1), Set.of()),
            "Materials for a sword + no sword owned → wants a table");
        assertFalse(CraftingLadder.wantsTable(new SimpleContainer(8), Set.of()),
            "No materials → doesn't want a table");
        assertFalse(CraftingLadder.wantsTable(
                backpack(Items.OAK_PLANKS, 5, Items.STICK, 2),
                Set.of(Items.WOODEN_SWORD, Items.STONE_SWORD,
                       Items.WOODEN_PICKAXE, Items.STONE_PICKAXE,
                       Items.WOODEN_AXE, Items.STONE_AXE)),
            "Fully geared → doesn't want a table");
    }

    @Test
    void noObjectiveMeansNoCraftEvenWithMaterials() {
        // Full stone-tier kit owned: even with a pile of raw materials and a
        // table, the ladder does nothing (no busywork).
        SimpleContainer bp = backpack(
            Items.OAK_LOG, 10, Items.OAK_PLANKS, 10, Items.COBBLESTONE, 10, Items.STICK, 10);
        Set<Item> kitted = Set.of(Items.STONE_SWORD, Items.STONE_PICKAXE, Items.STONE_AXE);
        assertFalse(CraftingLadder.nextCraft(bp, kitted, true).isPresent(),
            "No missing tool → craft nothing");
    }

    @Test
    void hasToolObjectiveTracksTheKit() {
        assertTrue(CraftingLadder.hasToolObjective(new SimpleContainer(8), Set.of()),
            "Owns nothing → has an objective");
        assertTrue(CraftingLadder.hasToolObjective(new SimpleContainer(8), Set.of(Items.WOODEN_SWORD)),
            "Only a wooden sword → still wants the stone kit");
        assertFalse(CraftingLadder.hasToolObjective(new SimpleContainer(8),
                Set.of(Items.STONE_SWORD, Items.STONE_PICKAXE, Items.STONE_AXE)),
            "Full stone kit → no objective");
        assertFalse(CraftingLadder.hasToolObjective(new SimpleContainer(8),
                Set.of(Items.IRON_SWORD, Items.DIAMOND_PICKAXE, Items.NETHERITE_AXE)),
            "Raided iron/diamond/netherite tools also satisfy the kit");
    }

    private static int countOf(SimpleContainer c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) n += s.getCount();
        }
        return n;
    }
}
