package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link ForagePolicy} — the food-acquisition classifier.
 *
 * <p>The {@code wantsFood} decision is fully pure; {@code isEdible} and
 * {@code isRipeFoodCrop} need vanilla item/block registries, so we run the same
 * {@link Bootstrap} dance as {@code ItemPickupPolicyTest}. The live-entity
 * {@code isHuntableFoodAnimal} path needs spawned mobs and is covered by the
 * in-game Gate 2 smoke test instead.</p>
 */
class ForagePolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void wantsFoodWhenItHasNone() {
        // No food at all → always seek, regardless of health.
        assertTrue(ForagePolicy.wantsFood(false, 0, 20f, 20f), "full health, no food");
        assertTrue(ForagePolicy.wantsFood(false, 0, 5f, 20f), "hurt, no food");
    }

    @Test
    void escalatesWhileHurtUntilBuffered() {
        // Hurt + carrying less than the heal buffer → keep foraging.
        assertTrue(ForagePolicy.wantsFood(true, ForagePolicy.HEAL_BUFFER_NUTRITION - 1, 10f, 20f),
            "hurt, below buffer");
        // Hurt but already buffered → content.
        assertFalse(ForagePolicy.wantsFood(true, ForagePolicy.HEAL_BUFFER_NUTRITION, 10f, 20f),
            "hurt, at buffer");
        assertFalse(ForagePolicy.wantsFood(true, 100, 10f, 20f), "hurt, well-stocked");
    }

    @Test
    void contentAtFullHealthWithFood() {
        assertFalse(ForagePolicy.wantsFood(true, 0, 20f, 20f), "full health, has food");
        assertFalse(ForagePolicy.wantsFood(true, 100, 20f, 20f), "full health, well-stocked");
    }

    @Test
    void identifiesEdibleItems() {
        assertTrue(ForagePolicy.isEdible(new ItemStack(Items.CARROT)), "carrot is edible");
        assertTrue(ForagePolicy.isEdible(new ItemStack(Items.COOKED_BEEF)), "cooked beef is edible");
        assertTrue(ForagePolicy.isEdible(new ItemStack(Items.POTATO)), "raw potato is edible");
        assertFalse(ForagePolicy.isEdible(new ItemStack(Items.DIAMOND_SWORD)), "sword is not edible");
        assertFalse(ForagePolicy.isEdible(new ItemStack(Items.COBBLESTONE)), "block is not edible");
        assertFalse(ForagePolicy.isEdible(new ItemStack(Items.WHEAT_SEEDS)), "seeds are not edible");
    }

    @Test
    void onlyRipeWhitelistedCropsAreHarvestable() {
        CropBlock carrots = (CropBlock) Blocks.CARROTS;
        CropBlock potatoes = (CropBlock) Blocks.POTATOES;
        CropBlock beetroots = (CropBlock) Blocks.BEETROOTS;
        CropBlock wheat = (CropBlock) Blocks.WHEAT;

        // Ripe whitelisted crops → harvestable.
        assertTrue(ForagePolicy.isRipeFoodCrop(carrots.getStateForAge(carrots.getMaxAge())), "ripe carrots");
        assertTrue(ForagePolicy.isRipeFoodCrop(potatoes.getStateForAge(potatoes.getMaxAge())), "ripe potatoes");
        assertTrue(ForagePolicy.isRipeFoodCrop(beetroots.getStateForAge(beetroots.getMaxAge())), "ripe beetroots");

        // Immature whitelisted crop → not yet.
        assertFalse(ForagePolicy.isRipeFoodCrop(carrots.getStateForAge(0)), "immature carrots");

        // Wheat is excluded even when ripe (not directly edible — needs crafting).
        assertFalse(ForagePolicy.isRipeFoodCrop(wheat.getStateForAge(wheat.getMaxAge())), "ripe wheat excluded");

        // Non-crop block → not harvestable.
        assertFalse(ForagePolicy.isRipeFoodCrop(Blocks.STONE.defaultBlockState()), "stone is not a crop");
    }
}
