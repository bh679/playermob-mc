package games.brennan.playermob.compat;

//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ChargedProjectiles;
//?}

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Version-bridging accessors for crossbow charged-projectile state, which moved
 * from the stack's {@code "Charged"} NBT flag + {@code CrossbowItem.setCharged}
 * statics (MC 1.20.1) into the {@code CHARGED_PROJECTILES} data component
 * (MC 1.21.1). The guard lives inside each method so the combat goal, the
 * arrow-blocking reflex, and the renderer all call a single uniform line.
 */
public final class CrossbowCompat {

    private CrossbowCompat() {}

    /** True if {@code stack} is a crossbow currently holding a charged projectile. */
    public static boolean isCharged(ItemStack stack) {
        //? if >=1.21.1 {
        ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
        return charged != null && !charged.isEmpty();
        //?} else {
        /*return CrossbowItem.isCharged(stack);*/
        //?}
    }

    /** Clear {@code stack}'s charged state back to uncharged. No-op on a non-crossbow. */
    public static void clearCharged(ItemStack stack) {
        //? if >=1.21.1 {
        stack.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
        //?} else {
        /*CrossbowItem.setCharged(stack, false);*/
        //?}
    }

    /**
     * Ticks it takes {@code shooter} to fully draw {@code stack}. 1.21.1 added the
     * shooter parameter (for the Quick Charge enchant lookup, now component-based);
     * 1.20.1 reads the enchant off the stack and ignores the shooter.
     */
    public static int chargeDuration(ItemStack stack, LivingEntity shooter) {
        //? if >=1.21.1 {
        return CrossbowItem.getChargeDuration(stack, shooter);
        //?} else {
        /*return CrossbowItem.getChargeDuration(stack);*/
        //?}
    }
}
