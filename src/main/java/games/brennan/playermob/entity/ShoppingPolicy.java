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
 *   <li><b>Any score</b> sells junk for emeralds. It stops at a trader it notices with chance
 *       {@code score / 10} and notices them from further away the higher the score.</li>
 *   <li><b>{@value #BUY_MIN_SCORE}+</b> also buys gear that beats what it wears.</li>
 * </ul>
 */
public final class ShoppingPolicy {

    private ShoppingPolicy() {}

    /** Score at/above which the mob buys gear, not just sells junk. */
    public static final int BUY_MIN_SCORE = 5;

    /** Blocks within which a mob notices a trader at score 0; each score point adds half a block. */
    static final double NOTICE_BASE_BLOCKS = 4.0;
    static final double NOTICE_BLOCKS_PER_POINT = 0.5;

    /** How long the mob remembers its stop/skip decision for one trader before deciding again. */
    public static final int DECISION_MEMORY_TICKS = 12000; // 10 minutes
    /** Most trader decisions remembered at once; the oldest is forgotten first. */
    public static final int DECISION_MEMORY_CAP = 64;

    /** Shortest and longest visit, in seconds, whatever the player's own habit was. */
    static final int MIN_VISIT_SECONDS = 2;
    static final int MAX_VISIT_SECONDS = 30;
    /** Range a mob with no recorded visit length rolls from, in seconds. */
    public static final int ROLL_VISIT_MIN_SECONDS = 3;
    public static final int ROLL_VISIT_MAX_SECONDS = 20;

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

    /**
     * How far away (blocks) a mob notices a trader: {@value #NOTICE_BASE_BLOCKS} at score 0 plus
     * half a block per point — 9 at score 10. A keen shopper spots traders from further off.
     */
    public static double noticeRadius(int score) {
        return NOTICE_BASE_BLOCKS + NOTICE_BLOCKS_PER_POINT * clampScore(score);
    }

    /**
     * Chance the mob stops at a trader it has just noticed: {@code score / 10}, the same share of
     * traders its player stopped to trade with. Rolled once per trader (see
     * {@link #DECISION_MEMORY_TICKS}).
     */
    public static double stopChance(int score) {
        return clampScore(score) / (double) DispositionTraits.MAX;
    }

    /** Ticks a visit lasts: {@code seconds} clamped to {@value #MIN_VISIT_SECONDS}–{@value #MAX_VISIT_SECONDS} s. */
    public static int visitTicks(int seconds) {
        return Math.max(MIN_VISIT_SECONDS, Math.min(MAX_VISIT_SECONDS, seconds)) * 20;
    }

    private static int clampScore(int score) {
        return Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, score));
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
