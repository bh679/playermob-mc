package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link ItemPickupPolicy} — the floor-item classifier.
 *
 * <p>Only the {@code Mob}-agnostic categories are reachable here; the
 * equip-upgrade decision ({@code PlayerMobEntity.wouldEquipFloorItem}) needs
 * protected Mob access and is covered by the in-game Gate 2 smoke test. Same
 * {@link Bootstrap} dance as {@code EquipmentEvaluatorTest} — vanilla item
 * registries must be bootstrapped before any {@link Items} reference.</p>
 */
class ItemPickupPolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesWeaponCategories() {
        assertEquals(ItemPickupPolicy.WeaponCategory.SWORD,
            ItemPickupPolicy.weaponCategory(new ItemStack(Items.DIAMOND_SWORD)), "sword");
        assertEquals(ItemPickupPolicy.WeaponCategory.AXE,
            ItemPickupPolicy.weaponCategory(new ItemStack(Items.DIAMOND_AXE)), "axe");
        assertEquals(ItemPickupPolicy.WeaponCategory.PICKAXE,
            ItemPickupPolicy.weaponCategory(new ItemStack(Items.DIAMOND_PICKAXE)), "pickaxe");
        assertEquals(ItemPickupPolicy.WeaponCategory.RANGED,
            ItemPickupPolicy.weaponCategory(new ItemStack(Items.BOW)), "bow is ranged");
        assertEquals(ItemPickupPolicy.WeaponCategory.RANGED,
            ItemPickupPolicy.weaponCategory(new ItemStack(Items.CROSSBOW)), "crossbow is ranged");
        // Shovels, blocks, and valuables are not toolkit weapons.
        assertNull(ItemPickupPolicy.weaponCategory(new ItemStack(Items.DIAMOND_SHOVEL)), "shovel");
        assertNull(ItemPickupPolicy.weaponCategory(new ItemStack(Items.COBBLESTONE)), "block");
        assertNull(ItemPickupPolicy.weaponCategory(new ItemStack(Items.DIAMOND)), "valuable");
    }

    @Test
    void identifiesArmorOrShield() {
        assertTrue(ItemPickupPolicy.isArmorOrShield(new ItemStack(Items.IRON_CHESTPLATE)), "armor");
        assertTrue(ItemPickupPolicy.isArmorOrShield(new ItemStack(Items.SHIELD)), "shield");
        // Weapons go through the toolkit path, not the armor path.
        assertFalse(ItemPickupPolicy.isArmorOrShield(new ItemStack(Items.DIAMOND_SWORD)), "weapon is not armor");
        assertFalse(ItemPickupPolicy.isArmorOrShield(new ItemStack(Items.COBBLESTONE)), "block is not armor");
    }

    @Test
    void meleeDamageRanksTiersAndTypes() {
        double woodSword = ItemPickupPolicy.meleeAttackDamage(new ItemStack(Items.WOODEN_SWORD));
        double diamondSword = ItemPickupPolicy.meleeAttackDamage(new ItemStack(Items.DIAMOND_SWORD));
        double diamondAxe = ItemPickupPolicy.meleeAttackDamage(new ItemStack(Items.DIAMOND_AXE));
        assertTrue(diamondSword > woodSword, "diamond sword hits harder than wooden");
        assertTrue(diamondAxe > diamondSword, "an axe hits harder than a sword of the same tier");
        assertEquals(0.0, ItemPickupPolicy.meleeAttackDamage(new ItemStack(Items.BOW)),
            "bows carry no melee attack-damage modifier");
    }

    @Test
    void comparesQualityWithinCategory() {
        assertTrue(ItemPickupPolicy.compareQuality(
            new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.WOODEN_SWORD)) > 0, "diamond > wood sword");
        assertTrue(ItemPickupPolicy.compareQuality(
            new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.STONE_PICKAXE)) > 0, "diamond > stone pickaxe");
        assertTrue(ItemPickupPolicy.compareQuality(
            new ItemStack(Items.CROSSBOW), new ItemStack(Items.BOW)) > 0, "crossbow out-ranks bow");
    }

    @Test
    void identifiesAmmo() {
        assertTrue(ItemPickupPolicy.isAmmo(new ItemStack(Items.ARROW)), "arrow");
        assertTrue(ItemPickupPolicy.isAmmo(new ItemStack(Items.SPECTRAL_ARROW)), "spectral arrow");
        assertTrue(ItemPickupPolicy.isAmmo(new ItemStack(Items.TIPPED_ARROW)), "tipped arrow");
        assertFalse(ItemPickupPolicy.isAmmo(new ItemStack(Items.DIAMOND)), "diamond is not ammo");
    }

    @Test
    void identifiesBuildingBlocks() {
        assertTrue(ItemPickupPolicy.isBuildingBlock(new ItemStack(Items.COBBLESTONE)), "cobblestone");
        assertTrue(ItemPickupPolicy.isBuildingBlock(new ItemStack(Items.OAK_PLANKS)), "planks");
        assertFalse(ItemPickupPolicy.isBuildingBlock(new ItemStack(Items.DIAMOND)), "diamond is not a block");
        assertFalse(ItemPickupPolicy.isBuildingBlock(new ItemStack(Items.ARROW)), "arrow is not a block");
    }

    @Test
    void identifiesConsumables() {
        assertTrue(ItemPickupPolicy.isConsumable(new ItemStack(Items.BREAD)), "bread is food");
        assertTrue(ItemPickupPolicy.isConsumable(new ItemStack(Items.GOLDEN_APPLE)), "golden apple is food");
        assertTrue(ItemPickupPolicy.isConsumable(new ItemStack(Items.POTION)), "potion");
        assertTrue(ItemPickupPolicy.isConsumable(new ItemStack(Items.SPLASH_POTION)), "splash potion");
        assertFalse(ItemPickupPolicy.isConsumable(new ItemStack(Items.COBBLESTONE)), "block is not consumable");
    }

    @Test
    void identifiesValuables() {
        assertTrue(ItemPickupPolicy.isValuable(new ItemStack(Items.DIAMOND)), "diamond");
        assertTrue(ItemPickupPolicy.isValuable(new ItemStack(Items.EMERALD)), "emerald");
        assertTrue(ItemPickupPolicy.isValuable(new ItemStack(Items.NETHERITE_INGOT)), "netherite ingot");
        assertTrue(ItemPickupPolicy.isValuable(new ItemStack(Items.ENCHANTED_BOOK)), "enchanted book");
        assertFalse(ItemPickupPolicy.isValuable(new ItemStack(Items.ROTTEN_FLESH)), "junk is not valuable");
        assertFalse(ItemPickupPolicy.isValuable(new ItemStack(Items.COBBLESTONE)), "block is not 'valuable'");
    }

    @Test
    void countsBuildingBlocksIgnoringNonBlocks() {
        SimpleContainer backpack = new SimpleContainer(8);
        backpack.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        backpack.setItem(1, new ItemStack(Items.OAK_PLANKS, 16));
        backpack.setItem(2, new ItemStack(Items.DIAMOND, 5)); // not a block — excluded
        assertEquals(48, ItemPickupPolicy.countBuildingBlocks(backpack), "32 + 16 blocks");
        assertEquals(1, ItemPickupPolicy.smallestBuildingBlockSlot(backpack), "planks (16) is the smallest block stack");
    }

    @Test
    void wantsBlocksUnderTheCap() {
        SimpleContainer backpack = new SimpleContainer(8);
        backpack.setItem(0, new ItemStack(Items.COBBLESTONE, 10)); // well under one stack
        assertTrue(ItemPickupPolicy.wantsBuildingBlock(backpack, new ItemStack(Items.STONE, 4)),
            "below the cap → always wants more blocks");
    }

    @Test
    void tradesUpOnlyForStrictlyLargerPilesAtTheCap() {
        // Three block stacks summing to the 64-block cap; smallest is 20.
        SimpleContainer backpack = new SimpleContainer(8);
        backpack.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
        backpack.setItem(1, new ItemStack(Items.DIRT, 20));
        backpack.setItem(2, new ItemStack(Items.SAND, 24));
        assertEquals(ItemPickupPolicy.BUILDING_BLOCK_CAP,
            ItemPickupPolicy.countBuildingBlocks(backpack), "at the cap");

        assertTrue(ItemPickupPolicy.wantsBuildingBlock(backpack, new ItemStack(Items.STONE, 32)),
            "32 > smallest carried (20) → trade up");
        assertFalse(ItemPickupPolicy.wantsBuildingBlock(backpack, new ItemStack(Items.STONE, 10)),
            "10 <= smallest carried (20) → keep what we have");
    }
}
