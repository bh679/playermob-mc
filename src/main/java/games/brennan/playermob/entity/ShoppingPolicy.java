package games.brennan.playermob.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * How keen a {@link PlayerMobEntity} is to trade with villagers, from its {@code Shopping} score
 * (0–10, from the player whose life it embodies — see {@code ShoppingTally}), and which of its
 * backpack items count as junk it will sell. All-static and world-free so it is unit-tested like
 * {@link GiftPolicy}; the trading itself lives in {@link VillagerTrading}, the trip in
 * {@code ShopWithVillagerGoal}.
 *
 * <ul>
 *   <li><b>Score 0</b> never trades.</li>
 *   <li><b>Any score</b> sells junk for emeralds — more often the higher it is.</li>
 *   <li><b>{@value #BUY_MIN_SCORE}+</b> also buys gear that beats what it wears.</li>
 * </ul>
 */
public final class ShoppingPolicy {

    private ShoppingPolicy() {}

    /** Score at/above which the mob buys gear, not just sells junk. */
    public static final int BUY_MIN_SCORE = 5;

    /** Ticks between trips at score 1 and at score 10 (linear between), before the ±25% jitter. */
    static final int COOLDOWN_AT_MIN = 6000; // 5 minutes
    static final int COOLDOWN_AT_MAX = 1200; // 1 minute
    static final double COOLDOWN_JITTER = 0.25;

    /** Wait before looking again when a search found no villager worth visiting. */
    public static final int RETRY_TICKS = 200;

    /** Most trades in one visit; the villager's own stock is the other limit. */
    public static final int MAX_TRADES_PER_TRIP = 6;

    /** Ticks between trades at neutral reaction speed (scaled by the ITEMS channel). */
    public static final int TRADE_BEAT_TICKS = 20;

    /** Food the mob keeps for itself before any is junk — the same reserve gifting keeps. */
    public static final int FOOD_KEEP_COUNT = 20;

    /** Emeralds one emerald block is worth. */
    static final int EMERALDS_PER_BLOCK = 9;

    /** True when a mob with {@code score} trades at all. */
    public static boolean trades(int score) {
        return score > 0;
    }

    /** True when a mob with {@code score} buys gear as well as selling junk. */
    public static boolean buysGear(int score) {
        return score >= BUY_MIN_SCORE;
    }

    /** Chance that a trip starts when its cooldown has run out: {@code score / 10}. */
    public static double tripChance(int score) {
        return Math.max(0, Math.min(DispositionTraits.MAX, score)) / (double) DispositionTraits.MAX;
    }

    /**
     * Ticks to wait before the next trip: {@value #COOLDOWN_AT_MIN} at score 1 falling to
     * {@value #COOLDOWN_AT_MAX} at 10, scaled by {@code roll} in {@code [0, 1)} to ±25%.
     */
    public static int cooldownTicks(int score, double roll) {
        int clamped = Math.max(1, Math.min(DispositionTraits.MAX, score));
        double t = (clamped - 1) / (double) (DispositionTraits.MAX - 1);
        double base = COOLDOWN_AT_MIN + (COOLDOWN_AT_MAX - COOLDOWN_AT_MIN) * t;
        double jitter = 1.0 + COOLDOWN_JITTER * (2.0 * roll - 1.0);
        return (int) Math.round(base * jitter);
    }

    /** Emeralds a stack is worth as money: emeralds count one, emerald blocks nine, anything else 0. */
    public static int emeraldValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(Items.EMERALD)) return stack.getCount();
        if (stack.is(Items.EMERALD_BLOCK)) return stack.getCount() * EMERALDS_PER_BLOCK;
        return 0;
    }

    /**
     * Whether a backpack stack is junk the mob will sell. Never money or a totem, never gear or ammo
     * the mob uses ({@code inUse}), and never into a reserve: a kind the mob keeps a supply of (food,
     * building blocks) is junk only while {@code carriedOfKind - sellCount} stays at or above
     * {@code reserve}. Pass {@code reserve} 0 for kinds it keeps none of.
     */
    public static boolean isJunk(ItemStack stack, boolean inUse, int carriedOfKind, int reserve,
                                 int sellCount) {
        if (stack.isEmpty() || inUse) return false;
        if (emeraldValue(stack) > 0 || stack.is(Items.TOTEM_OF_UNDYING)) return false;
        return carriedOfKind - sellCount >= reserve;
    }
}
