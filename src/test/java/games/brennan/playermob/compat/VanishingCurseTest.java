package games.brennan.playermob.compat;

//? if >=1.21.1 {
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
//?}
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemDataCompat#hasVanishingCurse}: the test both the death drop and echo capture use to
 * leave Curse of Vanishing items behind. Enchantments need the vanilla registry lookup on 1.21+
 * (see {@code EquipmentEvaluatorTest}).
 */
class VanishingCurseTest {

    //? if >=1.21.1 {
    private static HolderLookup.Provider registryAccess;
    //?}

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //? if >=1.21.1 {
        registryAccess = VanillaRegistries.createLookup();
        //?}
    }

    @Test
    void cursedStackIsDetected() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(vanishing(), 1);
        assertTrue(ItemDataCompat.hasVanishingCurse(sword));
    }

    @Test
    void otherEnchantmentsAreNotTheCurse() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(sharpness(), 5);
        assertFalse(ItemDataCompat.hasVanishingCurse(sword));
    }

    @Test
    void plainAndEmptyStacksAreNotCursed() {
        assertFalse(ItemDataCompat.hasVanishingCurse(new ItemStack(Items.BREAD)));
        assertFalse(ItemDataCompat.hasVanishingCurse(ItemStack.EMPTY));
    }

    //? if >=1.21.1 {
    private static Holder<Enchantment> vanishing() {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE);
    }

    private static Holder<Enchantment> sharpness() {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
    }
    //?} else {
    /*private static Enchantment vanishing() {
        return Enchantments.VANISHING_CURSE;
    }

    private static Enchantment sharpness() {
        return Enchantments.SHARPNESS;
    }
    *///?}
}
