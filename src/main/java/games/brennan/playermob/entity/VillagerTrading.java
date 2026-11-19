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
    public enum Kind { SELL, BUY }

    /** A completed trade: what kind, and the item the mob now holds from it. */
    public record Result(Kind kind, ItemStack item) {}

    /** The two stacks a deal pays with — {@code payB} is empty when the offer has no second cost. */
    private record Payment(ItemStack payA, ItemStack payB) {}

    /** True when {@code merchant} can be traded with right now: alive, grown, awake, not busy with a player. */
    public static boolean canTradeWith(AbstractVillager merchant) {
        return merchant.isAlive() && !merchant.isBaby() && !merchant.isSleeping()
            && !merchant.isTrading() && !merchant.getOffers().isEmpty();
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
        SimpleContainer pack = mob.getInventory();
        int costA = offer.getCostA().getCount();
        int costB = offer.getCostB().isEmpty() ? 0 : offer.getCostB().getCount();
        remove(pack, deal.payment().payA(), costA);
        if (costB > 0) remove(pack, deal.payment().payB(), costB);

        offer.increaseUses();
        ((AbstractVillagerTradeInvoker) merchant).playermob$rewardTradeXp(offer);
        merchant.playSound(merchant.getNotifyTradeSound(), 1.0F, 1.0F);
        merchant.getLookControl().setLookAt(mob);

        ItemStack bought = offer.assemble();
        if (deal.kind() == Kind.SELL) {
            ItemStack leftover = EquipmentEvaluator.addToContainer(pack, bought);
            if (!leftover.isEmpty()) mob.dropAtLocation(leftover);
        }
        return new Result(deal.kind(), bought);
    }

    private record Deal(MerchantOffer offer, Kind kind, Payment payment) {}

    private static Deal findOffer(PlayerMobEntity mob, AbstractVillager merchant, boolean buyGear) {
        if (buyGear) {
            for (MerchantOffer offer : merchant.getOffers()) {
                if (offer.isOutOfStock() || !mob.wantsBoughtGear(offer.getResult())) continue;
                Payment pay = payment(mob, offer, true);
                if (pay != null) return new Deal(offer, Kind.BUY, pay);
            }
        }
        for (MerchantOffer offer : merchant.getOffers()) {
            if (offer.isOutOfStock() || ShoppingPolicy.emeraldValue(offer.getResult()) <= 0) continue;
            Payment pay = payment(mob, offer, false);
            if (pay != null) return new Deal(offer, Kind.SELL, pay);
        }
        return null;
    }

    /**
     * Stacks from the backpack that pay {@code offer}, merged per kind so a price split across
     * several stacks still counts, or {@code null} when the mob can't pay. Selling pays only with
     * junk; buying may also spend money. Matching is the offer's own {@code satisfiedBy}.
     */
    private static Payment payment(PlayerMobEntity mob, MerchantOffer offer, boolean mayUseMoney) {
        SimpleContainer pack = mob.getInventory();
        Predicate<ItemStack> usable = stack -> ShoppingPolicy.emeraldValue(stack) > 0
            ? mayUseMoney
            : isJunk(mob, stack, offer);
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

    /** True when {@code stack} is junk the mob would part with to pay {@code offer}. */
    private static boolean isJunk(PlayerMobEntity mob, ItemStack stack, MerchantOffer offer) {
        boolean inUse = ItemPickupPolicy.weaponCategory(stack) != null
            || ItemPickupPolicy.isArmorOrShield(stack)
            || (ItemPickupPolicy.isAmmo(stack) && mob.ownsRangedWeapon());
        int sell = ItemDataCompat.isSameItemSameData(stack, offer.getCostA())
            ? offer.getCostA().getCount() : offer.getCostB().getCount();
        SimpleContainer pack = mob.getInventory();
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
