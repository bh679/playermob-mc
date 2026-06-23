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

    // ---- Wood / stone chest-loot policy ----------------------------------

    @Test
    void classifiesWood() {
        assertTrue(ItemPickupPolicy.isWood(new ItemStack(Items.OAK_LOG)), "oak log");
        assertTrue(ItemPickupPolicy.isWood(new ItemStack(Items.SPRUCE_LOG)), "spruce log");
        assertTrue(ItemPickupPolicy.isWood(new ItemStack(Items.OAK_PLANKS)), "oak planks");
        assertTrue(ItemPickupPolicy.isWood(new ItemStack(Items.CRIMSON_STEM)), "crimson stem");
        assertTrue(ItemPickupPolicy.isWood(new ItemStack(Items.CRIMSON_PLANKS)), "crimson planks");
        assertTrue(ItemPickupPolicy.isWood(new ItemStack(Items.BAMBOO_PLANKS)), "bamboo planks");
        assertFalse(ItemPickupPolicy.isWood(new ItemStack(Items.COBBLESTONE)), "stone is not wood");
        assertFalse(ItemPickupPolicy.isWood(new ItemStack(Items.DIAMOND)), "diamond is not wood");
    }

    @Test
    void classifiesStoneFamily() {
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.STONE)), "stone");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.COBBLESTONE)), "cobblestone");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.DEEPSLATE)), "deepslate");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.COBBLED_DEEPSLATE)), "cobbled deepslate");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.GRANITE)), "granite");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.POLISHED_ANDESITE)), "polished andesite");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.STONE_BRICKS)), "stone bricks");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.DEEPSLATE_TILES)), "deepslate tiles");
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.BLACKSTONE)), "blackstone");
        //? if >=1.21.1 {
        assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.TUFF_BRICKS)), "tuff bricks");
        //?} else {
        /*assertTrue(ItemPickupPolicy.isStone(new ItemStack(Items.TUFF)), "tuff");*/
        //?}
        assertFalse(ItemPickupPolicy.isStone(new ItemStack(Items.OAK_PLANKS)), "planks are not stone");
        assertFalse(ItemPickupPolicy.isStone(new ItemStack(Items.DIRT)), "dirt is not stone");
        assertFalse(ItemPickupPolicy.isStone(new ItemStack(Items.DIAMOND)), "diamond is not stone");
    }

    @Test
    void classifiesBlockResourceOrNull() {
        assertEquals(ItemPickupPolicy.BlockResource.WOOD,
            ItemPickupPolicy.blockResource(new ItemStack(Items.OAK_PLANKS)), "planks → WOOD");
        assertEquals(ItemPickupPolicy.BlockResource.STONE,
            ItemPickupPolicy.blockResource(new ItemStack(Items.COBBLESTONE)), "cobblestone → STONE");
        assertNull(ItemPickupPolicy.blockResource(new ItemStack(Items.DIRT)), "dirt → neither");
        assertNull(ItemPickupPolicy.blockResource(new ItemStack(Items.SAND)), "sand → neither");
        assertNull(ItemPickupPolicy.blockResource(new ItemStack(Items.DIAMOND)), "diamond → neither");
    }

    @Test
    void detectsToolCraftableStone() {
        assertTrue(ItemPickupPolicy.isToolCraftableStone(new ItemStack(Items.COBBLESTONE)), "cobblestone");
        assertTrue(ItemPickupPolicy.isToolCraftableStone(new ItemStack(Items.BLACKSTONE)), "blackstone");
        assertTrue(ItemPickupPolicy.isToolCraftableStone(new ItemStack(Items.COBBLED_DEEPSLATE)), "cobbled deepslate");
        assertFalse(ItemPickupPolicy.isToolCraftableStone(new ItemStack(Items.STONE)), "plain stone can't craft tools");
        assertFalse(ItemPickupPolicy.isToolCraftableStone(new ItemStack(Items.GRANITE)), "granite can't");
        assertFalse(ItemPickupPolicy.isToolCraftableStone(new ItemStack(Items.STONE_BRICKS)), "stone bricks can't");
    }

    @Test
    void countsResourcePerType() {
        SimpleContainer backpack = new SimpleContainer(8);
        backpack.setItem(0, new ItemStack(Items.OAK_PLANKS, 32));
        backpack.setItem(1, new ItemStack(Items.OAK_LOG, 16));
        backpack.setItem(2, new ItemStack(Items.STONE, 10));
        backpack.setItem(3, new ItemStack(Items.DIAMOND, 5)); // neither — excluded
        assertEquals(48, ItemPickupPolicy.countResource(backpack, ItemPickupPolicy.BlockResource.WOOD), "32+16 wood");
        assertEquals(10, ItemPickupPolicy.countResource(backpack, ItemPickupPolicy.BlockResource.STONE), "10 stone");
    }

    @Test
    void capsAreIndependent() {
        SimpleContainer backpack = new SimpleContainer(8);
        backpack.setItem(0, new ItemStack(Items.OAK_PLANKS, 64)); // wood at its cap
        assertFalse(ItemPickupPolicy.wantsResource(backpack, new ItemStack(Items.OAK_LOG, 4),
            ItemPickupPolicy.BlockResource.WOOD), "wood capped → small pile rejected");
        assertTrue(ItemPickupPolicy.wantsResource(backpack, new ItemStack(Items.STONE, 4),
            ItemPickupPolicy.BlockResource.STONE), "stone empty → still wants stone");
    }

    @Test
    void woodTradesUpOnlyForStrictlyLargerPileAtCap() {
        SimpleContainer backpack = new SimpleContainer(8);
        backpack.setItem(0, new ItemStack(Items.OAK_PLANKS, 20));
        backpack.setItem(1, new ItemStack(Items.SPRUCE_LOG, 20));
        backpack.setItem(2, new ItemStack(Items.BIRCH_PLANKS, 24));
        assertEquals(64, ItemPickupPolicy.countResource(backpack, ItemPickupPolicy.BlockResource.WOOD), "wood at cap");
        assertTrue(ItemPickupPolicy.wantsResource(backpack, new ItemStack(Items.OAK_LOG, 32),
            ItemPickupPolicy.BlockResource.WOOD), "32 > smallest pile (20) → trade up");
        assertFalse(ItemPickupPolicy.wantsResource(backpack, new ItemStack(Items.OAK_LOG, 10),
            ItemPickupPolicy.BlockResource.WOOD), "10 <= smallest pile (20) → keep what we have");
    }

    @Test
    void stoneTradeUpPrioritizesToolCraftableAtCap() {
        // At the stone cap with entirely decorative stone: a tool-craftable stack trades up
        // even at a smaller count, because tool-craftable out-ranks decorative.
        SimpleContainer decorative = new SimpleContainer(8);
        decorative.setItem(0, new ItemStack(Items.GRANITE, 64));
        assertEquals(64, ItemPickupPolicy.countResource(decorative, ItemPickupPolicy.BlockResource.STONE), "stone at cap");
        assertTrue(ItemPickupPolicy.wantsResource(decorative, new ItemStack(Items.COBBLESTONE, 1),
            ItemPickupPolicy.BlockResource.STONE),
            "tool-craftable cobblestone (1) displaces decorative granite (64)");

        // Inverse: decorative cannot displace equal-count tool-craftable stone.
        SimpleContainer toolCraftable = new SimpleContainer(8);
        toolCraftable.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        assertFalse(ItemPickupPolicy.wantsResource(toolCraftable, new ItemStack(Items.GRANITE, 64),
            ItemPickupPolicy.BlockResource.STONE),
            "decorative granite (64) can't displace tool-craftable cobblestone (64)");
    }

    @Test
    void lowestValueResourceSlotPrefersDisplacingDecorative() {
        SimpleContainer backpack = new SimpleContainer(8);
        backpack.setItem(0, new ItemStack(Items.COBBLESTONE, 4)); // tool-craftable — higher value
        backpack.setItem(1, new ItemStack(Items.GRANITE, 64));    // decorative — lower value despite bigger pile
        assertEquals(1, ItemPickupPolicy.lowestValueResourceSlot(backpack, ItemPickupPolicy.BlockResource.STONE),
            "decorative granite is the displacement target over tool-craftable cobblestone");
    }
}
