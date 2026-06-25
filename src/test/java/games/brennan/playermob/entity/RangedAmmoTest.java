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
 * Pure-logic tests for {@link RangedAmmo} — the weapon-aware inventory ammo helpers behind ranged combat.
 *
 * <p>Same {@link Bootstrap} dance as {@code ItemPickupPolicyTest}: vanilla item registries must be bootstrapped
 * before any {@link Items} reference, so weapon/ammo stacks are built inside the tests (after {@code @BeforeAll}),
 * never as static fields. Only the registry-free, tag-free {@code instanceof} path is exercised — no datapack
 * tags needed.</p>
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
    void classifiesArrowsAndFireworks() {
        assertTrue(RangedAmmo.isArrow(new ItemStack(Items.ARROW)), "plain arrow");
        assertTrue(RangedAmmo.isArrow(new ItemStack(Items.TIPPED_ARROW)), "tipped arrow");
        assertFalse(RangedAmmo.isArrow(new ItemStack(Items.FIREWORK_ROCKET)), "firework is not an arrow");
        assertFalse(RangedAmmo.isArrow(new ItemStack(Items.DIAMOND_SWORD)), "sword is not an arrow");

        assertTrue(RangedAmmo.isFirework(new ItemStack(Items.FIREWORK_ROCKET)), "firework rocket");
        assertFalse(RangedAmmo.isFirework(new ItemStack(Items.ARROW)), "arrow is not a firework");
        assertFalse(RangedAmmo.isFirework(ItemStack.EMPTY), "empty is not a firework");
    }

    @Test
    void acceptsIsWeaponAware() {
        ItemStack bow = new ItemStack(Items.BOW);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        ItemStack arrow = new ItemStack(Items.ARROW);
        ItemStack firework = new ItemStack(Items.FIREWORK_ROCKET);

        // Bow: arrows only.
        assertTrue(RangedAmmo.accepts(bow, arrow), "bow takes arrows");
        assertTrue(RangedAmmo.accepts(bow, new ItemStack(Items.TIPPED_ARROW)), "bow takes tipped arrows");
        assertFalse(RangedAmmo.accepts(bow, firework), "bow does NOT take fireworks");
        assertFalse(RangedAmmo.accepts(bow, new ItemStack(Items.COBBLESTONE)), "bow takes no blocks");

        // Crossbow: arrows or fireworks.
        assertTrue(RangedAmmo.accepts(crossbow, arrow), "crossbow takes arrows");
        assertTrue(RangedAmmo.accepts(crossbow, firework), "crossbow takes fireworks");
        assertFalse(RangedAmmo.accepts(crossbow, new ItemStack(Items.COBBLESTONE)), "crossbow takes no blocks");

        // Non-projectile weapon and empty candidate take nothing.
        assertFalse(RangedAmmo.accepts(new ItemStack(Items.DIAMOND_SWORD), arrow), "sword is not ranged");
        assertFalse(RangedAmmo.accepts(crossbow, ItemStack.EMPTY), "empty candidate");
    }

    @Test
    void countArrowsCountsArrowsOnly() {
        assertEquals(0, RangedAmmo.countArrows(container()), "empty container");
        SimpleContainer mixed = container(
            new ItemStack(Items.ARROW, 3),
            new ItemStack(Items.FIREWORK_ROCKET, 5),
            new ItemStack(Items.TIPPED_ARROW, 2));
        assertEquals(5, RangedAmmo.countArrows(mixed), "fireworks are not counted as arrows");
    }

    @Test
    void firstAmmoSlotAndHasAmmoForAreWeaponAware() {
        ItemStack bow = new ItemStack(Items.BOW);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);

        // Only a firework present: a crossbow can use it, a bow can't.
        SimpleContainer fireworkOnly = container(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.FIREWORK_ROCKET, 4));
        assertFalse(RangedAmmo.hasAmmoFor(fireworkOnly, bow), "bow has no usable ammo");
        assertEquals(-1, RangedAmmo.firstAmmoSlot(fireworkOnly, bow), "bow finds no slot");
        assertTrue(RangedAmmo.hasAmmoFor(fireworkOnly, crossbow), "crossbow can fire the firework");
        assertEquals(1, RangedAmmo.firstAmmoSlot(fireworkOnly, crossbow), "crossbow finds the firework slot");

        // Arrow before firework: both find the arrow first (slot order).
        SimpleContainer arrowThenFirework = container(new ItemStack(Items.ARROW, 2), new ItemStack(Items.FIREWORK_ROCKET, 1));
        assertEquals(0, RangedAmmo.firstAmmoSlot(arrowThenFirework, bow), "bow → arrow slot");
        assertEquals(0, RangedAmmo.firstAmmoSlot(arrowThenFirework, crossbow), "crossbow → arrow slot first");
    }

    @Test
    void consumeOneAmmoDecrementsAndIsWeaponAware() {
        ItemStack bow = new ItemStack(Items.BOW);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);

        // Crossbow consumes a firework when that's all it has; decrements then empties.
        SimpleContainer fireworks = container(new ItemStack(Items.FIREWORK_ROCKET, 2));
        assertTrue(RangedAmmo.consumeOneAmmo(fireworks, crossbow), "crossbow consumes a firework");
        assertEquals(1, fireworks.getItem(0).getCount(), "two becomes one");
        assertTrue(RangedAmmo.consumeOneAmmo(fireworks, crossbow), "consumes the last firework");
        assertTrue(fireworks.getItem(0).isEmpty(), "slot cleared at zero");
        assertFalse(RangedAmmo.consumeOneAmmo(fireworks, crossbow), "nothing left");

        // A bow can't consume a firework — leaves it untouched.
        SimpleContainer bowVsFirework = container(new ItemStack(Items.FIREWORK_ROCKET, 3));
        assertFalse(RangedAmmo.consumeOneAmmo(bowVsFirework, bow), "bow rejects fireworks");
        assertEquals(3, bowVsFirework.getItem(0).getCount(), "firework stack untouched by the bow");

        // With both, the arrow (slot 0) goes first; the block is never touched.
        SimpleContainer mixed = container(
            new ItemStack(Items.ARROW, 1),
            new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.FIREWORK_ROCKET, 1));
        assertTrue(RangedAmmo.consumeOneAmmo(mixed, crossbow), "consumed the arrow");
        assertTrue(mixed.getItem(0).isEmpty(), "arrow slot cleared");
        assertEquals(64, mixed.getItem(1).getCount(), "block stack untouched");
        assertEquals(1, mixed.getItem(2).getCount(), "firework still there");
    }
}
