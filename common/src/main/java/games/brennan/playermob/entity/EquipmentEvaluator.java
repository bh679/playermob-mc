package games.brennan.playermob.entity;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Equipment comparison helpers for the raid goals.
 *
 * <p>The scoring is enchantment-aware: a Sharpness-V iron sword (base
 * attack-damage 5 + 5 enchant levels × 0.5 = 7.5) beats a plain diamond sword
 * (base 7). Vanilla {@link net.minecraft.world.entity.Mob#canReplaceCurrentItem}
 * short-circuits on base damage and ignores enchantments when materials differ;
 * this comparator removes that short-circuit so a well-enchanted lower-tier
 * weapon can win.</p>
 *
 * <p>The 0.5-per-level coefficient is a calibration choice, not a damage
 * simulation. It's close enough to Sharpness's actual bonus (3.0 at level 5)
 * that the user-visible outcome — "enchanted iron beats plain diamond" — holds
 * for the common cases without modelling per-enchantment damage formulas.</p>
 *
 * <p>The {@link #addToContainer} helper is the original v1 utility — stash a
 * displaced item back into the container after a swap.</p>
 *
 * <p>Stateless — every method is static. Unit-tested in
 * {@link EquipmentEvaluatorTest}.</p>
 */
public final class EquipmentEvaluator {

    /** Each enchantment level adds this much to the candidate's score. */
    private static final double ENCHANT_LEVEL_WEIGHT = 0.5;

    /**
     * Fallback base score for bows/crossbows, which carry no default
     * ATTACK_DAMAGE attribute modifier (their damage lives in the projectile).
     * Picked so a plain bow scores below an iron sword (5) but above an
     * unenchanted wooden sword (4) — keeps ranged weapons in the same ballpark
     * as melee for swap purposes.
     */
    private static final double BOW_BASE_SCORE = 4.0;

    private EquipmentEvaluator() {}

    /**
     * Returns {@code true} if the mob should take {@code candidate} in place
     * of {@code current}. Non-equipment candidates never win; an equipment
     * candidate always wins against an empty or non-equipment slot.
     */
    public static boolean shouldReplace(ItemStack candidate, ItemStack current) {
        if (candidate.isEmpty()) return false;
        if (!isRecognizedEquipment(candidate)) return false;
        if (current.isEmpty()) return true;
        if (!isRecognizedEquipment(current)) return true;
        return score(candidate) > score(current);
    }

    /**
     * Item types the mob treats as gear. Hoes, pickaxes, food, blocks, etc.
     * fall through to {@code false} — those should never displace held gear.
     */
    private static boolean isRecognizedEquipment(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof SwordItem
            || item instanceof AxeItem
            || item instanceof BowItem
            || item instanceof CrossbowItem
            || item instanceof ShieldItem
            || item instanceof ArmorItem;
    }

    /**
     * Effective gear value = base attribute stat (attack damage for weapons,
     * armor for armor, fallback for bows) + {@link #ENCHANT_LEVEL_WEIGHT}
     * per enchantment level. Visible for testing.
     */
    static double score(ItemStack stack) {
        double base = baseStat(stack);
        return base + totalEnchantmentLevels(stack) * ENCHANT_LEVEL_WEIGHT;
    }

    /**
     * Reads the stack's attribute modifiers and returns the first meaningful
     * base stat: attack damage if present, else armor, else the bow/crossbow
     * fallback, else 0.
     *
     * <p>Uses the vanilla fallback pattern: stack-level
     * {@code DataComponents.ATTRIBUTE_MODIFIERS} first, then
     * {@link Item#getDefaultAttributeModifiers()} if the component is empty.
     * Weapons and shields populate the component directly via
     * {@code Item.Properties.attributes(...)}; armor populates the legacy
     * {@code getDefaultAttributeModifiers} override instead — so without the
     * fallback we'd read 0 for every armor piece.</p>
     */
    @SuppressWarnings("deprecation") // Item.getDefaultAttributeModifiers — see Javadoc below.
    private static double baseStat(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(
            DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (modifiers.modifiers().isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers();
        }

        double attackDamage = sumAddValue(modifiers, Attributes.ATTACK_DAMAGE);
        if (attackDamage > 0) return attackDamage;

        double armor = sumAddValue(modifiers, Attributes.ARMOR);
        if (armor > 0) return armor;

        Item item = stack.getItem();
        if (item instanceof BowItem || item instanceof CrossbowItem) return BOW_BASE_SCORE;
        return 0;
    }

    /**
     * Sum of all {@code ADD_VALUE} modifiers for the given attribute holder.
     * Multiply / multiply-base modifiers are intentionally ignored — they're
     * almost never used for gear damage/armor and would require knowing the
     * base value to apply correctly.
     */
    private static double sumAddValue(ItemAttributeModifiers modifiers, Holder<Attribute> attribute) {
        double total = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                total += entry.modifier().amount();
            }
        }
        return total;
    }

    /**
     * Sum of all enchantment levels on the stack. Sharpness V + Unbreaking III
     * returns 8. No registry access required — reads the stack's components
     * directly.
     */
    static int totalEnchantmentLevels(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        int total = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            total += entry.getIntValue();
        }
        return total;
    }

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
