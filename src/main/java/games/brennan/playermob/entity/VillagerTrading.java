package games.brennan.playermob.entity;

import games.brennan.playermob.compat.ItemDataCompat;
import games.brennan.playermob.mixin.AbstractVillagerTradeInvoker;
import net.minecraft.world.SimpleContainer;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.List;
import java.util.function.Predicate;

/**
 * A {@link PlayerMobEntity} trading with a villager or wandering trader through the merchant's
 * <em>real</em> offers — the same prices (demand and special-price adjusted), stock, uses and
 * restock a player gets. Nothing is ever conjured: every trade takes its full price out of the
 * mob's backpack and hands back exactly the offer's result.
 *
 * <p><b>Why not {@code notifyTrade}?</b> That method also fires the trade advancement and, on
 * Forge/NeoForge, {@code TradeWithVillagerEvent} — both for the merchant's trading <em>player</em>,
 * which here is nobody. So the trade is done step for step instead: the offer's uses go up (stock,
 * demand at the next restock), the villager gets its trade XP through
 * {@link AbstractVillagerTradeInvoker} (levelling and the XP orb), and it says yes. Because
 * {@code notifyTrade} is never called, an echo's trades never count toward a player's own
 * Shopping stat either.</p>
 */
public final class VillagerTrading {

    private VillagerTrading() {}

    /** What one trade did. */
    public enum Kind { SELL, BUY, GIFT }

    /** A completed trade: what kind, and the item the mob now holds from it. */
    public record Result(Kind kind, ItemStack item) {}

    /** The two stacks a deal pays with — {@code payB} is empty when the offer has no second cost. */
    record Payment(ItemStack payA, ItemStack payB) {}

    /** True when {@code merchant} can be traded with right now: alive, grown, awake, not busy with a player. */
    public static boolean canTradeWith(AbstractVillager merchant) {
        return merchant.isAlive() && !merchant.isBaby() && !merchant.isSleeping()
            && !merchant.isTrading() && !merchant.getOffers().isEmpty();
    }

    /**
     * A trader worth noticing: alive, grown and with offers — a villager with a job or a wandering
     * trader. Unemployed villagers and nitwits have no offers, so they never count.
     */
    public static boolean isTrader(AbstractVillager merchant) {
        return merchant.isAlive() && !merchant.isBaby() && !merchant.getOffers().isEmpty();
    }

    /** True when the mob has a deal it would take with {@code merchant} right now. */
    public static boolean hasDeal(PlayerMobEntity mob, AbstractVillager merchant, boolean buyGear) {
        return canTradeWith(merchant) && findOffer(mob, merchant, buyGear) != null;
    }

    /**
     * Make the best deal available — a gear upgrade it can afford first (when {@code buyGear}),
     * else a sale of junk for emeralds — or {@code null} when there is none. A bought item is
     * returned for the caller to equip; a sale's emeralds are already in the backpack (or dropped
     * at the mob's feet if it is full).
     */
    public static Result tradeOnce(PlayerMobEntity mob, AbstractVillager merchant, boolean buyGear) {
        if (!canTradeWith(merchant)) return null;
        Deal deal = findOffer(mob, merchant, buyGear);
        if (deal == null) return null;

        MerchantOffer offer = deal.offer();
        ItemStack bought = settle(mob.getInventory(), offer, deal.payment());
        rewardMerchant(mob, merchant, offer);

        if (deal.kind() == Kind.SELL) {
            ItemStack leftover = EquipmentEvaluator.addToContainer(mob.getInventory(), bought);
            if (!leftover.isEmpty()) mob.dropAtLocation(leftover);
        }
        return new Result(deal.kind(), bought);
    }

    /**
     * Buy a gift: the best gear on offer (highest {@link EquipmentEvaluator#score}) that
     * {@link GiftShopping#upgrades} what its recipient owns ({@code owned}) and that the mob can pay
     * for — emeralds and junk, never gear. The gift goes into the mob's backpack (or drops at its
     * feet if full) for the caller to deliver. {@code null} when nothing on offer is worth buying.
     */
    public static Result tradeGift(PlayerMobEntity mob, AbstractVillager merchant, List<ItemStack> owned) {
        if (!canTradeWith(merchant)) return null;
        SimpleContainer pack = mob.getInventory();
        boolean ownsRanged = mob.ownsRangedWeapon();
        MerchantOffer best = null;
        Payment bestPay = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MerchantOffer offer : merchant.getOffers()) {
            ItemStack result = offer.getResult();
            if (offer.isOutOfStock() || !GiftShopping.upgrades(result, owned)) continue;
            double score = EquipmentEvaluator.score(result);
            if (score <= bestScore) continue;
            Payment pay = payment(pack, offer, true, ownsRanged);
            if (pay != null) {
                best = offer;
                bestPay = pay;
                bestScore = score;
            }
        }
        if (best == null) return null;
        ItemStack gift = settle(pack, best, bestPay);
        rewardMerchant(mob, merchant, best);
        ItemStack leftover = EquipmentEvaluator.addToContainer(pack, gift.copy());
        if (!leftover.isEmpty()) mob.dropAtLocation(leftover);
        return new Result(Kind.GIFT, gift);
    }

    /** The villager's side of any echo trade: its XP (and level-up), its "yes", its glance. */
    private static void rewardMerchant(PlayerMobEntity mob, AbstractVillager merchant, MerchantOffer offer) {
        ((AbstractVillagerTradeInvoker) merchant).playermob$rewardTradeXp(offer);
        merchant.playSound(merchant.getNotifyTradeSound(), 1.0F, 1.0F);
        merchant.getLookControl().setLookAt(mob);
    }

    /**
     * The world-free core of one trade: take exactly the offer's current price out of {@code pack}
     * (across stacks), use up one of the offer's stock, and return a copy of its result. The caller
     * gives the result a home and rewards the villager.
     */
    static ItemStack settle(SimpleContainer pack, MerchantOffer offer, Payment payment) {
        remove(pack, payment.payA(), offer.getCostA().getCount());
        if (!offer.getCostB().isEmpty()) {
            remove(pack, payment.payB(), offer.getCostB().getCount());
        }
        offer.increaseUses();
        return offer.assemble();
    }

    private record Deal(MerchantOffer offer, Kind kind, Payment payment) {}

    private static Deal findOffer(PlayerMobEntity mob, AbstractVillager merchant, boolean buyGear) {
        SimpleContainer pack = mob.getInventory();
        boolean ownsRanged = mob.ownsRangedWeapon();
        if (buyGear) {
            for (MerchantOffer offer : merchant.getOffers()) {
                if (offer.isOutOfStock() || !mob.wantsBoughtGear(offer.getResult())) continue;
                Payment pay = payment(pack, offer, true, ownsRanged);
                if (pay != null) return new Deal(offer, Kind.BUY, pay);
            }
        }
        for (MerchantOffer offer : merchant.getOffers()) {
            if (offer.isOutOfStock() || ShoppingPolicy.emeraldValue(offer.getResult()) <= 0) continue;
            Payment pay = payment(pack, offer, false, ownsRanged);
            if (pay != null) return new Deal(offer, Kind.SELL, pay);
        }
        return null;
    }

    /**
     * Stacks from {@code pack} that pay {@code offer}, merged per kind so a price split across
     * several stacks still counts, or {@code null} when it can't be paid. Selling pays only with
     * junk; buying ({@code mayUseMoney}) may also spend emeralds. Matching is the offer's own
     * {@code satisfiedBy}, so the price is what a player would pay right now.
     */
    static Payment payment(SimpleContainer pack, MerchantOffer offer, boolean mayUseMoney, boolean ownsRanged) {
        Predicate<ItemStack> usable = stack -> ShoppingPolicy.emeraldValue(stack) > 0
            ? mayUseMoney
            : isJunk(pack, stack, offer, ownsRanged);
        boolean needsB = !offer.getCostB().isEmpty();
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack a = merged(pack, pack.getItem(i), usable);
            if (a.isEmpty()) continue;
            if (!needsB) {
                if (offer.satisfiedBy(a, ItemStack.EMPTY)) return new Payment(a, ItemStack.EMPTY);
                continue;
            }
            for (int j = 0; j < pack.getContainerSize(); j++) {
                ItemStack b = merged(pack, pack.getItem(j), usable);
                if (!b.isEmpty() && !ItemDataCompat.isSameItemSameData(a, b) && offer.satisfiedBy(a, b)) {
                    return new Payment(a, b);
                }
            }
        }
        return null;
    }

    /**
     * True when {@code stack} is junk the mob would part with to pay {@code offer}: not gear, not
     * arrows while it owns a bow or crossbow ({@code ownsRanged}), and never into its food or
     * building-block reserves.
     */
    static boolean isJunk(SimpleContainer pack, ItemStack stack, MerchantOffer offer, boolean ownsRanged) {
        boolean inUse = ItemPickupPolicy.weaponCategory(stack) != null
            || ItemPickupPolicy.isArmorOrShield(stack)
            || (ItemPickupPolicy.isAmmo(stack) && ownsRanged);
        int sell = ItemDataCompat.isSameItemSameData(stack, offer.getCostA())
            ? offer.getCostA().getCount() : offer.getCostB().getCount();
        if (ItemDataCompat.isFood(stack)) {
            return ShoppingPolicy.isJunk(stack, inUse, count(pack, ItemDataCompat::isFood),
                ShoppingPolicy.FOOD_KEEP_COUNT, sell);
        }
        if (ItemPickupPolicy.isBuildingBlock(stack)) {
            return ShoppingPolicy.isJunk(stack, inUse, count(pack, ItemPickupPolicy::isBuildingBlock),
                ItemPickupPolicy.BUILDING_BLOCK_CAP, sell);
        }
        return ShoppingPolicy.isJunk(stack, inUse, stack.getCount(), 0, 0);
    }
    /** {@code template} with the count of every usable backpack stack of the same item and data, or empty. */
    private static ItemStack merged(SimpleContainer pack, ItemStack template, Predicate<ItemStack> usable) {
        if (template.isEmpty() || !usable.test(template)) return ItemStack.EMPTY;
        int total = 0;
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (!s.isEmpty() && ItemDataCompat.isSameItemSameData(s, template)) total += s.getCount();
        }
        return template.copyWithCount(total);
    }

    private static int count(SimpleContainer pack, Predicate<ItemStack> kind) {
        int total = 0;
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (!s.isEmpty() && kind.test(s)) total += s.getCount();
        }
        return total;
    }

    /** Take {@code amount} of {@code template}'s item and data out of the backpack, across stacks. */
    private static void remove(SimpleContainer pack, ItemStack template, int amount) {
        int left = amount;
        for (int i = 0; i < pack.getContainerSize() && left > 0; i++) {
            ItemStack s = pack.getItem(i);
            if (s.isEmpty() || !ItemDataCompat.isSameItemSameData(s, template)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            if (s.isEmpty()) pack.setItem(i, ItemStack.EMPTY);
            left -= take;
        }
        pack.setChanged();
    }
}
