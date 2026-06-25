package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link RangedAmmo} — the inventory arrow helpers behind ammo-aware ranged combat.
 *
 * <p>Same {@link Bootstrap} dance as {@code ItemPickupPolicyTest} — vanilla item registries must be
 * bootstrapped before any {@link Items} reference. Only the registry-free, tag-free {@code instanceof ArrowItem}
 * path is exercised, so no datapack tags are needed.</p>
 */
class RangedAmmoTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SimpleContainer container(ItemStack... stacks) {
        SimpleContainer c = new SimpleContainer(8);
        for (int i = 0; i < stacks.length; i++) {
            c.setItem(i, stacks[i]);
        }
        return c;
    }

    @Test
    void isArrowRecognisesArrowItems() {
        assertTrue(RangedAmmo.isArrow(new ItemStack(Items.ARROW)), "plain arrow");
        assertTrue(RangedAmmo.isArrow(new ItemStack(Items.TIPPED_ARROW)), "tipped arrow");
        assertFalse(RangedAmmo.isArrow(new ItemStack(Items.DIAMOND_SWORD)), "sword is not ammo");
        assertFalse(RangedAmmo.isArrow(new ItemStack(Items.COBBLESTONE)), "block is not ammo");
        assertFalse(RangedAmmo.isArrow(ItemStack.EMPTY), "empty is not ammo");
    }

    @Test
    void countArrowsSumsOnlyArrowStacks() {
        assertEquals(0, RangedAmmo.countArrows(container()), "empty container");
        assertEquals(5, RangedAmmo.countArrows(container(new ItemStack(Items.ARROW, 5))), "single stack");
        // Arrows in two slots are summed; the sword and block in between are ignored.
        SimpleContainer mixed = container(
            new ItemStack(Items.ARROW, 3),
            new ItemStack(Items.DIAMOND_SWORD),
            new ItemStack(Items.TIPPED_ARROW, 2),
            new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(5, RangedAmmo.countArrows(mixed), "3 + 2 arrows across slots");
    }

    @Test
    void hasUsableAmmoAndFirstSlotTrackArrows() {
        assertFalse(RangedAmmo.hasUsableAmmo(container()), "no arrows");
        assertEquals(-1, RangedAmmo.firstArrowSlot(container(new ItemStack(Items.COBBLESTONE))), "no arrow slot");

        SimpleContainer withArrows = container(
            new ItemStack(Items.DIAMOND_SWORD),
            new ItemStack(Items.ARROW, 1));
        assertTrue(RangedAmmo.hasUsableAmmo(withArrows), "one arrow present");
        assertEquals(1, RangedAmmo.firstArrowSlot(withArrows), "first arrow in slot 1");
    }

    @Test
    void consumeOneArrowDecrementsAndEmptiesAtZero() {
        SimpleContainer c = container(new ItemStack(Items.ARROW, 2));
        assertTrue(RangedAmmo.consumeOneArrow(c), "consumed first");
        assertEquals(1, c.getItem(0).getCount(), "two becomes one");

        assertTrue(RangedAmmo.consumeOneArrow(c), "consumed last");
        assertTrue(c.getItem(0).isEmpty(), "slot cleared at zero");
        assertEquals(0, RangedAmmo.countArrows(c), "no arrows remain");

        assertFalse(RangedAmmo.consumeOneArrow(c), "nothing left to consume");
    }

    @Test
    void consumeOneArrowNeverTouchesNonArrows() {
        SimpleContainer c = container(
            new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.ARROW, 1));
        assertTrue(RangedAmmo.consumeOneArrow(c), "consumed the arrow");
        assertEquals(64, c.getItem(0).getCount(), "block stack untouched");
        assertTrue(c.getItem(1).isEmpty(), "arrow slot cleared");
    }
}
