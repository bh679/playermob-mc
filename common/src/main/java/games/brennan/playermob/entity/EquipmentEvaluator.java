package games.brennan.playermob.entity;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Container-side helpers for the raid goals.
 *
 * <p>The actual <em>swap</em> logic (candidate-vs-current comparison via
 * {@code Mob.canReplaceCurrentItem}, slot routing via
 * {@code Mob.getEquipmentSlotForItem}) lives on
 * {@link PlayerMobEntity#tryReplaceFromContainer} and
 * {@link PlayerMobEntity#tryReplaceFromArmorStand} — both rely on
 * {@code protected} methods that only a Mob subclass can read. This class
 * holds the Mob-agnostic "stash this back" helper that the swap path calls
 * after taking a candidate.</p>
 *
 * <p>Stateless — every method is static. Unit-tested in
 * {@link EquipmentEvaluatorTest}.</p>
 */
public final class EquipmentEvaluator {

    private EquipmentEvaluator() {}

    /**
     * Add a stack to the first mergeable / empty slot in {@code container}.
     * Returns whatever didn't fit (empty if it all fit).
     */
    public static ItemStack addToContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        // First pass: merge into existing same-item stacks.
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty()
                    && ItemStack.isSameItemSameComponents(slot, remaining)
                    && slot.getCount() < slot.getMaxStackSize()) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int toMove = Math.min(space, remaining.getCount());
                slot.grow(toMove);
                remaining.shrink(toMove);
                container.setChanged();
            }
        }
        // Second pass: drop into first empty slot.
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, remaining);
                container.setChanged();
                remaining = ItemStack.EMPTY;
            }
        }
        return remaining;
    }
}
