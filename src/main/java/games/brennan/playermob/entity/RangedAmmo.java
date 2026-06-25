package games.brennan.playermob.entity;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Pure inventory-ammo helpers for ranged combat: count, locate, and consume arrows in a mob's backpack.
 *
 * <p>Arrow classification reuses {@link ItemPickupPolicy#isAmmo} (an {@code instanceof ArrowItem} test that
 * covers plain, spectral, and tipped arrows) so the rule lives in one place. Everything here operates on the
 * {@link Container} / {@link ItemStack} contract only — no entity, level, registry, or tag access — so it stays
 * version-agnostic across the Stonecutter MC targets and is unit-testable with a plain {@code SimpleContainer}.</p>
 *
 * <p>Used as the single source of truth behind {@code PlayerMobEntity#hasRangedAmmo()}, the ammo-aware weapon
 * switch in {@code equipBestWeaponForTarget}, the bow fire path in {@code performRangedAttack}, and the
 * {@code SeekArrowsGoal} restock drive.</p>
 */
public final class RangedAmmo {

    private RangedAmmo() {}

    /** True if {@code stack} is an arrow (plain / spectral / tipped). Delegates to {@link ItemPickupPolicy#isAmmo}. */
    public static boolean isArrow(ItemStack stack) {
        return ItemPickupPolicy.isAmmo(stack);
    }

    /** Total arrows held across every slot of {@code inv}. */
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

    /** True if {@code inv} holds at least one arrow. */
    public static boolean hasUsableAmmo(Container inv) {
        return firstArrowSlot(inv) >= 0;
    }

    /** Index of the first slot holding an arrow, or {@code -1} if none. */
    public static int firstArrowSlot(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isArrow(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Remove one arrow from the first arrow stack in {@code inv}, clearing the slot when it hits zero.
     *
     * @return true if an arrow was consumed; false if {@code inv} held none
     */
    public static boolean consumeOneArrow(Container inv) {
        int slot = firstArrowSlot(inv);
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
