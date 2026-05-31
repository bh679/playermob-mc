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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the curated {@link CraftingLadder}.
 *
 * <p>Like {@link EquipmentEvaluatorTest}, touching vanilla {@link ItemStack} /
 * {@link SimpleContainer} / item tags requires {@link Bootstrap#bootStrap()}
 * first — otherwise the first registry reference throws "Not bootstrapped".</p>
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

    private static CraftAction craft(SimpleContainer bp, Set<Item> owned) {
        Optional<CraftAction> action = CraftingLadder.nextCraft(bp, owned);
        assertTrue(action.isPresent(), "expected a craft action");
        return action.get();
    }

    @Test
    void emptyBackpackCraftsNothing() {
        assertTrue(CraftingLadder.nextCraft(new SimpleContainer(8), Set.of()).isEmpty(),
            "Nothing to work with → no craft");
    }

    @Test
    void singleLogCraftsPlanks() {
        CraftAction a = craft(backpack(Items.OAK_LOG, 1), Set.of());
        assertEquals("planks", a.name());
        assertEquals(Items.OAK_PLANKS, a.output().getItem());
        assertEquals(4, a.output().getCount(), "1 log → 4 planks");
    }

    @Test
    void planksWithoutSticksCraftsSticks() {
        // 4 planks, 0 sticks → step 5 (sticks reserve) fires before any tool.
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 4), Set.of());
        assertEquals("sticks", a.name());
        assertEquals(Items.STICK, a.output().getItem());
        assertEquals(4, a.output().getCount());
    }

    @Test
    void planksAndStickCraftWoodenSword() {
        // Sword is step 1 — preferred over the pickaxe when the mob owns neither.
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 2, Items.STICK, 1), Set.of());
        assertEquals("wooden_sword", a.name());
        assertEquals(Items.WOODEN_SWORD, a.output().getItem());
    }

    @Test
    void ownedSwordSkipsToPickaxe() {
        // Already holding a sword (equipped, not in backpack) → step 1 guard skips,
        // and with 3 planks + 2 sticks the wooden pickaxe (step 3) is next.
        CraftAction a = craft(backpack(Items.OAK_PLANKS, 3, Items.STICK, 2),
            Set.of(Items.WOODEN_SWORD));
        assertEquals("wooden_pickaxe", a.name());
        assertEquals(Items.WOODEN_PICKAXE, a.output().getItem());
    }

    @Test
    void backpackSwordAlsoGuardsStepOne() {
        // Ownership is checked against the backpack too, not just equipped tools.
        CraftAction a = craft(backpack(
                Items.WOODEN_SWORD, 1, Items.OAK_PLANKS, 3, Items.STICK, 2),
            Set.of());
        assertEquals("wooden_pickaxe", a.name(),
            "A sword sitting in the backpack should still skip step 1");
    }

    @Test
    void cobbleAndStickCraftStoneSword() {
        // No planks, so step 1 (wooden sword) can't fire; cobble + stick → stone sword.
        CraftAction a = craft(backpack(Items.COBBLESTONE, 2, Items.STICK, 1), Set.of());
        assertEquals("stone_sword", a.name());
        assertEquals(Items.STONE_SWORD, a.output().getItem());
    }

    @Test
    void tagAgnosticBirchFeedsTheLadder() {
        // Birch (not oak) logs/planks still match via ItemTags.
        CraftAction logToPlanks = craft(backpack(Items.BIRCH_LOG, 1), Set.of());
        assertEquals("planks", logToPlanks.name());

        CraftAction birchSword = craft(backpack(Items.BIRCH_PLANKS, 2, Items.STICK, 1), Set.of());
        assertEquals("wooden_sword", birchSword.name());
    }

    @Test
    void applyConsumesExactInputs() {
        // 4 planks + 4 sticks → wooden sword consumes 2 planks + 1 stick, leaving 2 + 3.
        SimpleContainer bp = backpack(Items.OAK_PLANKS, 4, Items.STICK, 4);
        CraftAction a = craft(bp, Set.of());
        assertEquals("wooden_sword", a.name());

        CraftingLadder.apply(bp, a);

        assertEquals(2, countOf(bp, Items.OAK_PLANKS), "2 planks consumed");
        assertEquals(3, countOf(bp, Items.STICK), "1 stick consumed");
        assertEquals(0, countOf(bp, Items.WOODEN_SWORD),
            "apply() only consumes inputs — the caller places the output");
    }

    @Test
    void fullyGearedCraftsNothing() {
        // Owns the full wood+stone kit and holds reserve planks/sticks → idle.
        SimpleContainer bp = backpack(Items.OAK_PLANKS, 5, Items.STICK, 2);
        Set<Item> owned = Set.of(
            Items.WOODEN_SWORD, Items.STONE_SWORD,
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE);
        assertFalse(CraftingLadder.nextCraft(bp, owned).isPresent(),
            "Fully geared with reserves → ladder is done");
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
