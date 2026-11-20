package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.entity.ShoppingPolicy;
import games.brennan.playermob.entity.VillagerTrading;
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
 * Feeds a survival player's {@link ShoppingTally}: which traders they came across, which of those
 * they traded with, and how long they kept trading screens open. Called from
 * {@code ServerPlayerTickShoppingMixin} (once a second), {@code VillagerTradeShoppingMixin} (each
 * trade) and {@code VillagerTradingSessionMixin} (each screen closed). Never throws into the game.
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

    /** Once a second: every trader within {@link #MEET_RADIUS} counts as met (once per life). */
    public static void onPlayerTick(ServerPlayer player) {
        if (player.tickCount % SCAN_TICKS != 0 || !counts(player)) return;
        try {
            var nearby = player.level().getEntitiesOfClass(AbstractVillager.class,
                player.getBoundingBox().inflate(MEET_RADIUS), VillagerTrading::isTrader);
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

    /**
     * Longest a single trading screen counts for: a screen left open while the player is away
     * shouldn't teach the echo to stand at a villager for ten minutes.
     */
    static final long MAX_VISIT_TICKS = 6000L; // 5 minutes

    /** The player closed a trading screen they had open for {@code ticks}. */
    public static void onVisitEnd(ServerPlayer player, long ticks) {
        if (!counts(player) || ticks <= 0L) return;
        try {
            long counted = Math.min(ticks, MAX_VISIT_TICKS);
            PlayerLifeStore.recordShopping(player, tally -> tally.withVisit(counted));
        } catch (RuntimeException e) {
            LOGGER.warn("[playermob] Failed to record a trading visit", e);
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
