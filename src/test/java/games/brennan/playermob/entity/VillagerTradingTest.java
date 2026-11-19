package games.brennan.playermob.entity;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.21.1 {
import net.minecraft.world.item.trading.ItemCost;
import java.util.Optional;
//?}
import net.minecraft.world.item.trading.MerchantOffer;
import games.brennan.playermob.testutil.WeaponClassification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The world-free core of an echo's villager trade ({@link VillagerTrading#payment},
 * {@link VillagerTrading#settle}): exactly the price leaves the backpack, exactly the result comes
 * back, the offer's stock is used, and nothing the mob needs is ever spent.
 */
class VillagerTradingTest {

    private static MerchantOffer offer(Item costA, int countA, Item costB, int countB,
                                       ItemStack result, int maxUses) {
        //? if >=1.21.1 {
        Optional<ItemCost> b = costB == null ? Optional.empty() : Optional.of(new ItemCost(costB, countB));
        return new MerchantOffer(new ItemCost(costA, countA), b, result, maxUses, 1, 0.05F);
        //?} else {
        /*ItemStack b = costB == null ? ItemStack.EMPTY : new ItemStack(costB, countB);
        return new MerchantOffer(new ItemStack(costA, countA), b, result, maxUses, 1, 0.05F);*/
        //?}
    }

    private static MerchantOffer sale(Item item, int count) {
        return offer(item, count, null, 0, new ItemStack(Items.EMERALD), 16);
    }

    private static SimpleContainer pack(ItemStack... stacks) {
        SimpleContainer pack = new SimpleContainer(Math.max(9, stacks.length));
        for (int i = 0; i < stacks.length; i++) {
            pack.setItem(i, stacks[i]);
        }
        return pack;
    }

    private static int count(SimpleContainer pack, Item item) {
        int total = 0;
        for (int i = 0; i < pack.getContainerSize(); i++) {
            if (pack.getItem(i).is(item)) total += pack.getItem(i).getCount();
        }
        return total;
    }

    @Test
    void sellsJunkForExactlyThePrice() {
        SimpleContainer pack = pack(new ItemStack(Items.PAPER, 30));
        MerchantOffer offer = sale(Items.PAPER, 24);
        VillagerTrading.Payment pay = VillagerTrading.payment(pack, offer, false, false);
        assertNotNull(pay);
        ItemStack result = VillagerTrading.settle(pack, offer, pay);
        assertEquals(6, count(pack, Items.PAPER));
        assertEquals(1, result.getCount());
        assertTrue(result.is(Items.EMERALD));
        assertEquals(1, offer.getUses());
    }

    @Test
    void aPriceSplitAcrossStacksIsPaid() {
        SimpleContainer pack = pack(new ItemStack(Items.PAPER, 13), new ItemStack(Items.PAPER, 13));
        MerchantOffer offer = sale(Items.PAPER, 24);
        VillagerTrading.Payment pay = VillagerTrading.payment(pack, offer, false, false);
        assertNotNull(pay);
        VillagerTrading.settle(pack, offer, pay);
        assertEquals(2, count(pack, Items.PAPER));
    }

    @Test
    void oneItemShortCannotPay() {
        SimpleContainer pack = pack(new ItemStack(Items.PAPER, 23));
        assertNull(VillagerTrading.payment(pack, sale(Items.PAPER, 24), false, false));
    }

    @Test
    void sellingNeverSpendsEmeralds() {
        SimpleContainer pack = pack(new ItemStack(Items.EMERALD, 10));
        MerchantOffer offer = offer(Items.EMERALD, 5, null, 0, new ItemStack(Items.EMERALD_BLOCK), 4);
        assertNull(VillagerTrading.payment(pack, offer, false, false));
    }

    @Test
    void buyingSpendsEmeraldsAndASecondCost() {
        SimpleContainer pack = pack(new ItemStack(Items.EMERALD, 10), new ItemStack(Items.BOOK, 2));
        MerchantOffer offer = offer(Items.EMERALD, 6, Items.BOOK, 1, new ItemStack(Items.IRON_CHESTPLATE), 3);
        VillagerTrading.Payment pay = VillagerTrading.payment(pack, offer, true, false);
        assertNotNull(pay);
        ItemStack result = VillagerTrading.settle(pack, offer, pay);
        assertEquals(4, count(pack, Items.EMERALD));
        assertEquals(1, count(pack, Items.BOOK));
        assertTrue(result.is(Items.IRON_CHESTPLATE));
    }

    @Test
    void aMissingSecondCostCannotPay() {
        SimpleContainer pack = pack(new ItemStack(Items.EMERALD, 10));
        MerchantOffer offer = offer(Items.EMERALD, 6, Items.BOOK, 1, new ItemStack(Items.IRON_CHESTPLATE), 3);
        assertNull(VillagerTrading.payment(pack, offer, true, false));
    }

    @Test
    void gearIsNeverSpent() {
        assumeTrue(WeaponClassification.available(), WeaponClassification.SKIP_REASON);
        SimpleContainer pack = pack(new ItemStack(Items.IRON_SWORD));
        MerchantOffer offer = sale(Items.IRON_SWORD, 1);
        assertNull(VillagerTrading.payment(pack, offer, false, false));
    }

    @Test
    void arrowsAreKeptOnlyWhileTheMobOwnsABow() {
        SimpleContainer pack = pack(new ItemStack(Items.ARROW, 32));
        MerchantOffer offer = sale(Items.ARROW, 16);
        assertNull(VillagerTrading.payment(pack, offer, false, true));
        assertNotNull(VillagerTrading.payment(pack, offer, false, false));
    }

    @Test
    void theBuildingBlockReserveIsKept() {
        // Blocks are pooled, as the mob hoards them: only what's above its stack of 64 is junk.
        MerchantOffer offer = sale(Items.STONE, 20);
        assertNull(VillagerTrading.payment(pack(new ItemStack(Items.STONE, 64)), offer, false, false));
        assertNotNull(VillagerTrading.payment(
            pack(new ItemStack(Items.STONE, 20), new ItemStack(Items.COBBLESTONE, 64)), offer, false, false));
    }

    @Test
    void stringSellsWithoutABlockReserve() {
        SimpleContainer pack = pack(new ItemStack(Items.STRING, 14));
        assertNotNull(VillagerTrading.payment(pack, sale(Items.STRING, 14), false, false));
    }

    @Test
    void theFoodReserveIsKept() {
        int keep = ShoppingPolicy.FOOD_KEEP_COUNT;
        MerchantOffer offer = sale(Items.CARROT, 22);
        assertNull(VillagerTrading.payment(pack(new ItemStack(Items.CARROT, keep + 21)), offer, false, false));
        assertNotNull(VillagerTrading.payment(pack(new ItemStack(Items.CARROT, keep + 22)), offer, false, false));
    }
}
