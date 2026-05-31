package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link EquipmentEvaluator}.
 *
 * <p>The swap methods live on {@link PlayerMobEntity} (need protected
 * {@code Mob.canReplaceCurrentItem} access) — covered by the in-game Gate 2
 * smoke test. This class covers what's purely container-shaped or operates
 * on raw {@link ItemStack}s.</p>
 *
 * <p>Vanilla classes like {@link ItemStack} and {@link SimpleContainer}
 * trigger {@link net.minecraft.core.registries.BuiltInRegistries} static
 * initialisation, which requires {@link Bootstrap#bootStrap()} first.
 * Without it, the first reference throws {@code Not bootstrapped (called
 * from registry ResourceKey[minecraft:root / minecraft:game_event])}.</p>
 *
 * <p>Enchantment tests additionally need a {@link HolderLookup.Provider}
 * because enchantments live in a datapack-style registry in 1.21+ — populated
 * by {@link VanillaRegistries#createLookup()} after bootstrap.</p>
 */
class EquipmentEvaluatorTest {

    private static HolderLookup.Provider registryAccess;

    @BeforeAll
    static void bootstrapMinecraft() {
        // Idempotent — safe to call multiple times across test classes.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registryAccess = VanillaRegistries.createLookup();
    }

    @Test
    void addToContainerHandlesEmptyInputStack() {
        // Empty input — addToContainer returns the empty stack unchanged.
        // Exercises the early-exit path (both loops bail because
        // remaining.isEmpty()).
        SimpleContainer container = new SimpleContainer(2);
        ItemStack remaining = EquipmentEvaluator.addToContainer(container, ItemStack.EMPTY);
        assertTrue(remaining.isEmpty(), "Empty input → empty result");
    }

    @Test
    void addToContainerHandlesZeroSizeContainer() {
        // Pathological: container with 0 slots. addToContainer must not
        // throw — returns the input as leftover.
        SimpleContainer container = new SimpleContainer(0);
        ItemStack remaining = EquipmentEvaluator.addToContainer(container, ItemStack.EMPTY);
        assertTrue(remaining.isEmpty(), "Zero-size container with empty input → empty");
    }

    // ---- shouldReplace -------------------------------------------------

    @Test
    void shouldReplaceRejectsEmptyCandidate() {
        // Nothing to swap in.
        assertFalse(EquipmentEvaluator.shouldReplace(ItemStack.EMPTY, new ItemStack(Items.IRON_SWORD)));
    }

    @Test
    void shouldReplaceTakesEquipmentIntoEmptySlot() {
        // Recognised gear into empty slot — always wins.
        assertTrue(EquipmentEvaluator.shouldReplace(new ItemStack(Items.IRON_SWORD), ItemStack.EMPTY));
    }

    @Test
    void shouldReplaceRejectsNonEquipmentIntoEmptySlot() {
        // Bread isn't gear — the raid goal shouldn't grab it as a sword
        // replacement even when the slot is empty.
        assertFalse(EquipmentEvaluator.shouldReplace(new ItemStack(Items.BREAD), ItemStack.EMPTY));
    }

    @Test
    void shouldReplaceRejectsNonEquipmentOverEquipment() {
        // Trying to swap bread in for an iron sword should be rejected.
        assertFalse(EquipmentEvaluator.shouldReplace(
            new ItemStack(Items.BREAD), new ItemStack(Items.IRON_SWORD)));
    }

    @Test
    void shouldReplacePicksHigherBaseDamageWeapon() {
        // Diamond sword (base attack damage 7) beats iron sword (5) when
        // neither is enchanted.
        assertTrue(EquipmentEvaluator.shouldReplace(
            new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_SWORD)));
    }

    @Test
    void shouldReplaceRejectsLowerBaseDamageWeapon() {
        // Inverse: iron sword should NOT replace diamond sword when neither
        // is enchanted. (Locks in the symmetry of the previous test.)
        assertFalse(EquipmentEvaluator.shouldReplace(
            new ItemStack(Items.IRON_SWORD), new ItemStack(Items.DIAMOND_SWORD)));
    }

    @Test
    void shouldReplacePicksHigherBaseDamageArmor() {
        // Diamond chestplate (armor 8) beats leather chestplate (3). Armor's
        // attribute modifiers live in Item.getDefaultAttributeModifiers() in
        // 1.21.1 — not in the DataComponents.ATTRIBUTE_MODIFIERS component —
        // so this test catches any regression in EquipmentEvaluator's fallback path.
        assertTrue(EquipmentEvaluator.shouldReplace(
            new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.LEATHER_CHESTPLATE)));
    }

    @Test
    void shouldReplacePicksEnchantedIronOverPlainDiamond() {
        // The headline v2 behaviour: Sharpness V iron sword (base 5 + 5×0.5
        // = 7.5) should beat plain diamond sword (base 7).
        ItemStack ironSharp5 = new ItemStack(Items.IRON_SWORD);
        ironSharp5.enchant(sharpness(), 5);

        assertTrue(EquipmentEvaluator.shouldReplace(
            ironSharp5, new ItemStack(Items.DIAMOND_SWORD)));
    }

    @Test
    void shouldReplacePicksMoreEnchantedSameItem() {
        // Among two iron swords, the enchanted one wins.
        ItemStack enchanted = new ItemStack(Items.IRON_SWORD);
        enchanted.enchant(sharpness(), 3);

        assertTrue(EquipmentEvaluator.shouldReplace(enchanted, new ItemStack(Items.IRON_SWORD)));
    }

    @Test
    void shouldReplacePicksEnchantedArmor() {
        // Protection IV iron chestplate should win over plain iron chestplate
        // (same base armor — enchantment levels break the tie).
        ItemStack protected_ = new ItemStack(Items.IRON_CHESTPLATE);
        protected_.enchant(protection(), 4);

        assertTrue(EquipmentEvaluator.shouldReplace(
            protected_, new ItemStack(Items.IRON_CHESTPLATE)));
    }

    private static Holder<Enchantment> sharpness() {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
    }

    private static Holder<Enchantment> protection() {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
    }
}
