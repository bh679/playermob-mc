package games.brennan.playermob.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingPolicyTest {

    @Test
    void scoreZeroNeverTrades() {
        assertFalse(ShoppingPolicy.trades(0));
        assertTrue(ShoppingPolicy.trades(1));
    }

    @Test
    void buyingGearStartsAtFive() {
        assertFalse(ShoppingPolicy.buysGear(4));
        assertTrue(ShoppingPolicy.buysGear(5));
        assertTrue(ShoppingPolicy.buysGear(10));
    }

    @Test
    void noticeRadiusGrowsWithScore() {
        assertEquals(4.0, ShoppingPolicy.noticeRadius(0), 1e-9);
        assertEquals(6.5, ShoppingPolicy.noticeRadius(5), 1e-9);
        assertEquals(9.0, ShoppingPolicy.noticeRadius(10), 1e-9);
        assertEquals(9.0, ShoppingPolicy.noticeRadius(40), 1e-9);
    }

    @Test
    void stopChanceIsScoreOverTen() {
        assertEquals(0.0, ShoppingPolicy.stopChance(0), 1e-9);
        assertEquals(0.7, ShoppingPolicy.stopChance(7), 1e-9);
        assertEquals(1.0, ShoppingPolicy.stopChance(10), 1e-9);
    }

    @Test
    void visitLengthIsClamped() {
        assertEquals(40, ShoppingPolicy.visitTicks(0));
        assertEquals(200, ShoppingPolicy.visitTicks(10));
        assertEquals(600, ShoppingPolicy.visitTicks(600));
        assertEquals(600, ShoppingPolicy.visitTicks(30));
    }

    @Test
    void emeraldValueCountsBlocksAsNine() {
        assertEquals(5, ShoppingPolicy.emeraldValue(new ItemStack(Items.EMERALD, 5)));
        assertEquals(18, ShoppingPolicy.emeraldValue(new ItemStack(Items.EMERALD_BLOCK, 2)));
        assertEquals(0, ShoppingPolicy.emeraldValue(new ItemStack(Items.WHEAT, 20)));
        assertEquals(0, ShoppingPolicy.emeraldValue(ItemStack.EMPTY));
    }

    @Test
    void moneyAndTotemsAreNeverJunk() {
        assertFalse(ShoppingPolicy.isJunk(new ItemStack(Items.EMERALD, 10), false, 10, 0, 1));
        assertFalse(ShoppingPolicy.isJunk(new ItemStack(Items.TOTEM_OF_UNDYING), false, 1, 0, 1));
    }

    @Test
    void itemsInUseAreNeverJunk() {
        assertFalse(ShoppingPolicy.isJunk(new ItemStack(Items.ARROW, 32), true, 32, 0, 1));
    }

    @Test
    void plainJunkSells() {
        assertTrue(ShoppingPolicy.isJunk(new ItemStack(Items.STRING, 14), false, 14, 0, 14));
    }

    @Test
    void reservesAreKept() {
        ItemStack food = new ItemStack(Items.CARROT, 30);
        int keep = ShoppingPolicy.FOOD_KEEP_COUNT;
        assertTrue(ShoppingPolicy.isJunk(food, false, keep + 10, keep, 10));
        assertFalse(ShoppingPolicy.isJunk(food, false, keep + 9, keep, 10));
    }
}
