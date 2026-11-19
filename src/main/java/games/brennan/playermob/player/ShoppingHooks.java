package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.entity.ShoppingPolicy;
import net.minecraft.server.level.ServerPlayer;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
import net.minecraft.stats.Stats;
import net.minecraft.world.item.trading.MerchantOffer;
import org.slf4j.Logger;

/**
 * Feeds a survival player's {@link ShoppingTally}: which traders they came across, and which of
 * those they traded with. Called from {@code ServerPlayerTickShoppingMixin} (once a second) and
 * {@code VillagerTradeShoppingMixin} (each trade). Never throws into the tick or the trade.
 */
public final class ShoppingHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How close a trader has to be to count as met — near enough to see what it sells. */
    static final double MEET_RADIUS = 6.0;
    /** Ticks between scans for nearby traders. */
    static final int SCAN_TICKS = 20;
    private static final double TICKS_PER_MINUTE = 1200.0;

    private ShoppingHooks() {}

    /** Only a live survival player builds a shopping record. */
    private static boolean counts(ServerPlayer player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    /** Minutes into this life — the vanilla time-since-death statistic. */
    static double lifeMinutes(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH)) / TICKS_PER_MINUTE;
    }

    /**
     * A trader with something to trade: alive, grown and with offers — a villager with a job, or a
     * wandering trader. Unemployed villagers and nitwits have no offers, so they never count.
     */
    static boolean isTrader(AbstractVillager merchant) {
        return merchant.isAlive() && !merchant.isBaby() && !merchant.getOffers().isEmpty();
    }

    /** Once a second: every trader within {@link #MEET_RADIUS} counts as met (once per life). */
    public static void onPlayerTick(ServerPlayer player) {
        if (player.tickCount % SCAN_TICKS != 0 || !counts(player)) return;
        try {
            var nearby = player.level().getEntitiesOfClass(AbstractVillager.class,
                player.getBoundingBox().inflate(MEET_RADIUS), ShoppingHooks::isTrader);
            if (nearby.isEmpty()) return;
            double minutes = lifeMinutes(player);
            PlayerLifeStore.recordShopping(player, tally -> {
                ShoppingTally next = tally;
                for (AbstractVillager merchant : nearby) {
                    next = next.withMet(merchant.getUUID(), minutes);
                }
                return next;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("[playermob] Failed to record traders met", e);
        }
    }

    /** One trade the player took from {@code merchant}'s screen. */
    public static void onTrade(ServerPlayer player, AbstractVillager merchant, MerchantOffer offer) {
        if (!counts(player)) return;
        try {
            int spent = ShoppingPolicy.emeraldValue(offer.getCostA()) + ShoppingPolicy.emeraldValue(offer.getCostB());
            int earned = ShoppingPolicy.emeraldValue(offer.getResult());
            double minutes = lifeMinutes(player);
            PlayerLifeStore.recordShopping(player,
                tally -> tally.withTrade(merchant.getUUID(), minutes, spent, earned));
        } catch (RuntimeException e) {
            LOGGER.warn("[playermob] Failed to record a villager trade", e);
        }
    }
}
