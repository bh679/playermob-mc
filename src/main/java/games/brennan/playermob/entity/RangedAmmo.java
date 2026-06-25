package games.brennan.playermob.entity;

import net.minecraft.world.Container;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * Pure, weapon-aware inventory-ammo helpers for ranged combat: classify, count, locate, and consume the ammo a
 * given ranged weapon accepts.
 *
 * <p>Which items count as ammo depends on the weapon, exactly like vanilla: a <b>crossbow</b> fires arrows
 * <em>or</em> firework rockets; a <b>bow</b> (and modded {@link ProjectileWeaponItem}s) fires arrows only. Arrow
 * classification reuses {@link ItemPickupPolicy#isAmmo} (an {@code instanceof ArrowItem} test covering plain,
 * spectral, and tipped arrows). The weapon dispatch is by {@code instanceof} ({@link CrossbowItem} /
 * {@link FireworkRocketItem}) rather than vanilla's {@code getSupportedHeldProjectiles()} so it stays stable
 * across the Stonecutter MC nodes.</p>
 *
 * <p>Everything operates on the {@link Container} / {@link ItemStack} contract only — no entity, level, registry,
 * or tag access — so it's version-agnostic and unit-testable with a plain {@code SimpleContainer}.</p>
 */
public final class RangedAmmo {

    private RangedAmmo() {}

    /** True if {@code stack} is an arrow (plain / spectral / tipped). Delegates to {@link ItemPickupPolicy#isAmmo}. */
    public static boolean isArrow(ItemStack stack) {
        return ItemPickupPolicy.isAmmo(stack);
    }

    /** True if {@code stack} is a firework rocket (crossbow-only ammo). */
    public static boolean isFirework(ItemStack stack) {
        return stack.getItem() instanceof FireworkRocketItem;
    }

    /**
     * Whether {@code weapon} accepts {@code candidate} as ammo: a crossbow takes arrows or fireworks; any other
     * projectile weapon (bow, modded bows) takes arrows only; a non-projectile weapon takes nothing.
     */
    public static boolean accepts(ItemStack weapon, ItemStack candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        if (weapon.getItem() instanceof CrossbowItem) {
            return isArrow(candidate) || isFirework(candidate);
        }
        if (weapon.getItem() instanceof ProjectileWeaponItem) {
            return isArrow(candidate);
        }
        return false;
    }

    /** Total arrows held across every slot of {@code inv} (weapon-agnostic; used by tests and arrow counts). */
    public static int countArrows(Container inv) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isArrow(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Index of the first slot holding ammo {@code weapon} accepts, or {@code -1} if none. */
    public static int firstAmmoSlot(Container inv, ItemStack weapon) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (accepts(weapon, stack)) {
                return i;
            }
        }
        return -1;
    }

    /** True if {@code inv} holds at least one item {@code weapon} can fire. */
    public static boolean hasAmmoFor(Container inv, ItemStack weapon) {
        return firstAmmoSlot(inv, weapon) >= 0;
    }

    /**
     * Remove one round of ammo {@code weapon} accepts from the first matching slot of {@code inv}, clearing the
     * slot when it hits zero.
     *
     * @return true if a round was consumed; false if {@code inv} held no matching ammo
     */
    public static boolean consumeOneAmmo(Container inv, ItemStack weapon) {
        int slot = firstAmmoSlot(inv, weapon);
        if (slot < 0) {
            return false;
        }
        ItemStack stack = inv.getItem(slot);
        stack.shrink(1);
        if (stack.isEmpty()) {
            inv.setItem(slot, ItemStack.EMPTY);
        }
        inv.setChanged();
        return true;
    }
}
