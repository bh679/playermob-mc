package games.brennan.playermob.entity;

import games.brennan.playermob.testutil.WeaponClassification;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@link GiftShopping#upgrades}: is gear on offer a real upgrade for a player who owns these stacks? */
class GiftShoppingTest {

    private static List<ItemStack> owns(ItemStack... stacks) {
        return List.of(stacks);
    }

    @Test
    void armourBeatsAnEmptySlot() {
        assertTrue(GiftShopping.upgrades(new ItemStack(Items.IRON_CHESTPLATE), owns()));
    }

    @Test
    void armourMustBeatWhatIsWorn() {
        assertTrue(GiftShopping.upgrades(new ItemStack(Items.IRON_CHESTPLATE),
            owns(new ItemStack(Items.LEATHER_CHESTPLATE))));
        assertFalse(GiftShopping.upgrades(new ItemStack(Items.IRON_CHESTPLATE),
            owns(new ItemStack(Items.DIAMOND_CHESTPLATE))));
        assertFalse(GiftShopping.upgrades(new ItemStack(Items.IRON_CHESTPLATE),
            owns(new ItemStack(Items.IRON_CHESTPLATE))), "an equal piece is no upgrade");
    }

    @Test
    void aBetterPieceCarriedInTheBagCountsToo() {
        assertFalse(GiftShopping.upgrades(new ItemStack(Items.IRON_CHESTPLATE),
            owns(new ItemStack(Items.LEATHER_CHESTPLATE), new ItemStack(Items.DIAMOND_CHESTPLATE))));
    }

    @Test
    void otherSlotsDoNotMatter() {
        assertTrue(GiftShopping.upgrades(new ItemStack(Items.IRON_BOOTS),
            owns(new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.DIAMOND_HELMET))));
    }

    @Test
    void weaponsCompareWithinTheirKind() {
        assumeTrue(WeaponClassification.available(), WeaponClassification.SKIP_REASON);
        assertTrue(GiftShopping.upgrades(new ItemStack(Items.IRON_SWORD), owns(new ItemStack(Items.STONE_SWORD))));
        assertFalse(GiftShopping.upgrades(new ItemStack(Items.IRON_SWORD), owns(new ItemStack(Items.DIAMOND_SWORD))));
        // A diamond pickaxe says nothing about swords.
        assertTrue(GiftShopping.upgrades(new ItemStack(Items.IRON_SWORD), owns(new ItemStack(Items.DIAMOND_PICKAXE))));
    }

    @Test
    void nonGearIsNeverAGift() {
        assertFalse(GiftShopping.upgrades(new ItemStack(Items.BREAD), owns()));
        assertFalse(GiftShopping.upgrades(ItemStack.EMPTY, owns()));
    }
}
