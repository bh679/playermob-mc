package games.brennan.playermob.compat;

//? if >=1.21.1 {
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
//?}

import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Version-bridging accessors for the item data that moved from NBT/legacy getters
 * (MC 1.20.1) into the data-component system (MC 1.21.1).
 *
 * <p>Each method carries the {@code //?} Stonecutter guard <em>inside</em> its body
 * so every call site stays a single version-agnostic line. The {@code >=1.21.1}
 * branch is the original component-based code, byte-identical to what shipped; the
 * {@code else} branch is the 1.20.1 legacy-API equivalent, hand-verified against the
 * decompiled 1.20.1 sources to be behaviourally identical (same nutrition, same
 * effect probabilities, same attribute-modifier filtering).</p>
 *
 * <p>Food, attribute-modifier, and custom-name helpers live here. Crossbow charged
 * state lives in {@link CrossbowCompat} (it has its own cluster of call sites).</p>
 */
public final class ItemDataCompat {

    private ItemDataCompat() {}

    // ---- FOOD -------------------------------------------------------------

    /** The stack's {@link FoodProperties}, or {@code null} if it isn't food. */
    public static FoodProperties foodProperties(ItemStack stack) {
        //? if >=1.21.1 {
        return stack.get(DataComponents.FOOD);
        //?} else {
        /*return stack.getItem().getFoodProperties();*/
        //?}
    }

    /** True if the stack is edible (carries food data). */
    public static boolean isFood(ItemStack stack) {
        //? if >=1.21.1 {
        return stack.has(DataComponents.FOOD);
        //?} else {
        /*return stack.getItem().getFoodProperties() != null;*/
        //?}
    }

    /** Nutrition (food points) a {@link FoodProperties} restores. */
    public static int nutrition(FoodProperties food) {
        //? if >=1.21.1 {
        return food.nutrition();
        //?} else {
        /*return food.getNutrition();*/
        //?}
    }

    /**
     * Roll the food's effects against {@code random} and apply the winners to
     * {@code target} — the heal itself is the caller's job. Mirrors the
     * probability-gated effect loop both vanilla versions run on eat; centralised
     * here because the effect-list shape differs (1.21.1 {@code PossibleEffect}
     * record vs 1.20.1 {@code Pair<MobEffectInstance, Float>}).
     */
    public static void applyFoodEffects(FoodProperties food, LivingEntity target,
                                        net.minecraft.util.RandomSource random) {
        //? if >=1.21.1 {
        for (FoodProperties.PossibleEffect possible : food.effects()) {
            if (random.nextFloat() < possible.probability()) {
                // Defensive copy — the supplier returns the same instance across calls.
                target.addEffect(new MobEffectInstance(possible.effect()));
            }
        }
        //?} else {
        /*for (com.mojang.datafixers.util.Pair<MobEffectInstance, Float> possible : food.getEffects()) {
            if (random.nextFloat() < possible.getSecond()) {
                target.addEffect(new MobEffectInstance(possible.getFirst()));
            }
        }*/
        //?}
    }

    // ---- ATTRIBUTE MODIFIERS ---------------------------------------------
    //
    // The attribute reference and modifier accessors diverge across versions
    // (1.21.1 keys modifiers by Holder<Attribute> with record-style getters
    // .operation()/.amount(); 1.20.1 uses the raw Attribute with bean getters
    // .getOperation()/.getAmount(), and the ADD_VALUE operation is named
    // ADDITION). Both attributes PlayerMob reads — ATTACK_DAMAGE and ARMOR — are
    // hidden behind dedicated methods so no call site ever touches the diverging
    // attribute/modifier types.

    /**
     * Sum of the stack's {@code ATTACK_DAMAGE} ADD_VALUE modifiers, reading the
     * stack-level modifiers and falling back to the item's main-hand defaults when
     * the stack carries none. The equipment scorer's primary signal.
     */
    public static double attackDamageAddValue(ItemStack stack) {
        //? if >=1.21.1 {
        return sumAddValueWithDefault(stack, EquipmentSlot.MAINHAND,
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        //?} else {
        /*return sumAddValueWithDefault(stack, EquipmentSlot.MAINHAND,
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);*/
        //?}
    }

    /**
     * Sum of the stack's {@code ARMOR} ADD_VALUE modifiers, reading the stack-level
     * modifiers and falling back to the item's {@code slot} defaults (armor populates
     * per-slot default modifiers) when the stack carries none.
     */
    public static double armorAddValue(ItemStack stack, EquipmentSlot slot) {
        //? if >=1.21.1 {
        return sumAddValueWithDefault(stack, slot,
            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        //?} else {
        /*return sumAddValueWithDefault(stack, slot,
            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);*/
        //?}
    }

    /**
     * Main-hand {@code ATTACK_DAMAGE} the stack grants when held — slot-scoped only,
     * no item-default fallback (matches what the item contributes in the main hand).
     * Used by the floor-pickup melee-quality comparison.
     */
    public static double mainhandAttackDamage(ItemStack stack) {
        //? if >=1.21.1 {
        return sumSlotAddValue(stack, EquipmentSlot.MAINHAND,
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        //?} else {
        /*return sumSlotAddValue(stack, EquipmentSlot.MAINHAND,
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);*/
        //?}
    }

    // The two private helpers below are authored per-version: the parameter type of
    // `attribute` (Holder<Attribute> vs Attribute) and the modifier getters differ,
    // so the whole method body is guarded.

    //? if >=1.21.1 {
    private static double sumAddValueWithDefault(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attribute) {
        ItemAttributeModifiers modifiers =
            stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (modifiers.modifiers().isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers();
        }
        double total = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                total += entry.modifier().amount();
            }
        }
        return total;
    }

    private static double sumSlotAddValue(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attribute) {
        ItemAttributeModifiers modifiers =
            stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double[] total = {0.0};
        modifiers.forEach(slot, (attr, modifier) -> {
            if (attr.equals(attribute)) {
                total[0] += modifier.amount();
            }
        });
        return total[0];
    }
    //?} else {
    /*private static double sumAddValueWithDefault(ItemStack stack, EquipmentSlot slot, Attribute attribute) {
        com.google.common.collect.Multimap<Attribute, AttributeModifier> modifiers =
            stack.getAttributeModifiers(slot);
        if (modifiers.isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers(slot);
        }
        double total = 0;
        for (java.util.Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
            AttributeModifier modifier = entry.getValue();
            if (entry.getKey().equals(attribute)
                    && modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                total += modifier.getAmount();
            }
        }
        return total;
    }

    private static double sumSlotAddValue(ItemStack stack, EquipmentSlot slot, Attribute attribute) {
        com.google.common.collect.Multimap<Attribute, AttributeModifier> modifiers =
            stack.getAttributeModifiers(slot);
        double total = 0;
        for (java.util.Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
            if (entry.getKey().equals(attribute)) {
                total += entry.getValue().getAmount();
            }
        }
        return total;
    }*/
    //?}

    // ---- ENCHANTMENTS -----------------------------------------------------

    /**
     * Total of every enchantment level on the stack (Sharpness V + Unbreaking III
     * → 8). 1.21.1 reads the {@code ItemEnchantments} component; 1.20.1 reads the
     * legacy {@code EnchantmentHelper} map off the stack NBT.
     */
    public static int enchantmentLevelsTotal(ItemStack stack) {
        //? if >=1.21.1 {
        int total = 0;
        for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Holder<net.minecraft.world.item.enchantment.Enchantment>> entry
                : stack.getEnchantments().entrySet()) {
            total += entry.getIntValue();
        }
        return total;
        //?} else {
        /*int total = 0;
        for (int level : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack).values()) {
            total += level;
        }
        return total;*/
        //?}
    }

    /** Number of distinct enchantments on the stack — the gear tie-breaker. */
    public static int enchantmentCount(ItemStack stack) {
        //? if >=1.21.1 {
        return stack.getEnchantments().size();
        //?} else {
        /*return net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack).size();*/
        //?}
    }

    // ---- STACK IDENTITY ---------------------------------------------------

    /**
     * True if the two stacks are the same item with equal data (the merge test
     * for inventory stacking). 1.21.1 compares data components; 1.20.1 compares
     * item + NBT tags.
     */
    public static boolean isSameItemSameData(ItemStack a, ItemStack b) {
        //? if >=1.21.1 {
        return ItemStack.isSameItemSameComponents(a, b);
        //?} else {
        /*return ItemStack.isSameItemSameTags(a, b);*/
        //?}
    }

    // ---- CUSTOM NAME ------------------------------------------------------

    /** Stamp a custom hover name onto the stack. */
    public static void setCustomName(ItemStack stack, Component name) {
        //? if >=1.21.1 {
        stack.set(DataComponents.CUSTOM_NAME, name);
        //?} else {
        /*stack.setHoverName(name);*/
        //?}
    }
}
