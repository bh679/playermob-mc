package games.brennan.playermob.compat;

//? if >=26 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.equipment.Equippable;
*///?}

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Version-bridging <em>item-category</em> tests for the gear/weapon classifiers.
 *
 * <p>MC 26.x deleted the per-tool/armor {@link Item} subclasses that the AI used
 * for {@code instanceof} checks — {@code SwordItem}, {@code PickaxeItem},
 * {@code ArmorItem}, {@code DiggerItem} are gone (tool behaviour is now fully
 * data-driven through the {@code TOOL}/{@code WEAPON}/{@code EQUIPPABLE} data
 * components and the {@code minecraft:swords} / {@code minecraft:pickaxes} /
 * {@code minecraft:*_armor} item tags). {@code AxeItem}, {@code BowItem},
 * {@code CrossbowItem}, {@code ShieldItem}, and {@code TridentItem} all still
 * exist, so only the sword / pickaxe / armor tests move here.</p>
 *
 * <p>On 1.20.1 / 1.21.1 these delegate to the original {@code instanceof}
 * checks — byte-identical to what shipped. On 26.x they read the vanilla item
 * tags ({@code SWORDS}, {@code PICKAXES}) and the {@code EQUIPPABLE} component's
 * armor slot, which is the same set of vanilla items the subclasses covered.</p>
 *
 * <p>Stateless; every method static and pure on the {@link ItemStack}.</p>
 */
public final class ItemKindCompat {

    private ItemKindCompat() {}

    /** True if the stack is a sword (any material). */
    public static boolean isSword(ItemStack stack) {
        //? if >=26 {
        /*return stack.getItem().builtInRegistryHolder().is(ItemTags.SWORDS);
        *///?} else {
        return stack.getItem() instanceof net.minecraft.world.item.SwordItem;
        //?}
    }

    /** True if the stack is a pickaxe (any material). */
    public static boolean isPickaxe(ItemStack stack) {
        //? if >=26 {
        /*return stack.getItem().builtInRegistryHolder().is(ItemTags.PICKAXES);
        *///?} else {
        return stack.getItem() instanceof net.minecraft.world.item.PickaxeItem;
        //?}
    }

    /** True if the stack is a wearable armor piece (helmet/chest/legs/boots). */
    public static boolean isArmor(ItemStack stack) {
        //? if >=26 {
        /*Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot().isArmor();
        *///?} else {
        return stack.getItem() instanceof net.minecraft.world.item.ArmorItem;
        //?}
    }

    /**
     * True if the stack is any equippable wearable — armor, shield, elytra, or a worn head.
     * Mirrors the old {@code Equipable.get(stack) != null}; 26.x reads the {@code EQUIPPABLE}
     * data component, which every wearable (shields included) now carries.
     */
    public static boolean isEquippable(ItemStack stack) {
        //? if >=26 {
        /*return stack.has(DataComponents.EQUIPPABLE);
        *///?} else {
        return net.minecraft.world.item.Equipable.get(stack) != null;
        //?}
    }

    /**
     * The natural armor {@code EquipmentSlot} this stack equips into, or
     * {@link net.minecraft.world.entity.EquipmentSlot#MAINHAND} when it is not
     * armor. Mirrors the old {@code ArmorItem.getEquipmentSlot()}.
     */
    public static net.minecraft.world.entity.EquipmentSlot armorSlotOrMainhand(ItemStack stack) {
        //? if >=26 {
        /*Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable != null && equippable.slot().isArmor()) {
            return equippable.slot();
        }
        return EquipmentSlot.MAINHAND;
        *///?} else {
        if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor) {
            return armor.getEquipmentSlot();
        }
        return net.minecraft.world.entity.EquipmentSlot.MAINHAND;
        //?}
    }
}
