package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * One life's evidence of the player's <em>shopping</em> — trades made with villagers and
 * wandering traders, and the emeralds that changed hands. Distilled at death into the echo's
 * {@code Shopping} score (0–10), which decides how keen the echo is to trade (see
 * {@code ShopWithVillagerGoal}).
 *
 * <p>Held in {@link PlayerLifeStore} in its own map, beside — not inside — {@link PlayerLifeRecord},
 * so it stays self-contained. Immutable value type: every {@code with…} returns a fresh tally.
 * Pure (no world) so the scoring is unit-testable — see {@code ShoppingTallyTest}.</p>
 *
 * <p><b>Scored as a volume, not a rate.</b> Shopping is a hobby: a life that traded more cares
 * more. The score saturates — {@code 10 × evidence / (evidence + PRIOR_EVIDENCE)} — so the prior
 * at 0 keeps a single trade from reading as a keen shopper, and a long life of trading approaches
 * but never overshoots 10. Each trade is worth {@value #TRADE_WEIGHT}, plus
 * {@value #EMERALD_WEIGHT} per emerald spent or earned (an emerald block counts nine).</p>
 */
public final class ShoppingTally {

    // ---- Scoring ----------------------------------------------------------
    /** Evidence one trade adds, whatever it moved. */
    static final double TRADE_WEIGHT = 1.0;
    /** Evidence per emerald spent or earned. */
    static final double EMERALD_WEIGHT = 0.25;
    /** Evidence that scores exactly 5 — e.g. 8 trades moving 32 emeralds. */
    static final double PRIOR_EVIDENCE = 16.0;

    // ---- NBT --------------------------------------------------------------
    public static final String TAG = "Shopping";
    static final String TAG_TRADES = "Trades";
    static final String TAG_SPENT = "EmeraldsSpent";
    static final String TAG_EARNED = "EmeraldsEarned";

    public static final ShoppingTally EMPTY = new ShoppingTally(0, 0, 0);

    private final int trades;
    private final int emeraldsSpent;
    private final int emeraldsEarned;

    private ShoppingTally(int trades, int emeraldsSpent, int emeraldsEarned) {
        this.trades = Math.max(0, trades);
        this.emeraldsSpent = Math.max(0, emeraldsSpent);
        this.emeraldsEarned = Math.max(0, emeraldsEarned);
    }

    public int trades() { return trades; }
    public int emeraldsSpent() { return emeraldsSpent; }
    public int emeraldsEarned() { return emeraldsEarned; }

    public boolean isEmpty() {
        return trades == 0 && emeraldsSpent == 0 && emeraldsEarned == 0;
    }

    /** One completed trade that paid {@code spent} emeralds and returned {@code earned}. */
    public ShoppingTally withTrade(int spent, int earned) {
        return new ShoppingTally(trades + 1, emeraldsSpent + Math.max(0, spent),
            emeraldsEarned + Math.max(0, earned));
    }

    /** Weighted evidence behind the score. */
    double evidence() {
        return trades * TRADE_WEIGHT + (emeraldsSpent + (double) emeraldsEarned) * EMERALD_WEIGHT;
    }

    /** Unrounded score in {@code [0, 10)}. */
    double rawScore() {
        double e = evidence();
        return 10.0 * e / (e + PRIOR_EVIDENCE);
    }

    /** The echo's shopping score, rounded and clamped to {@code [0, 10]}. */
    public int score() {
        long rounded = Math.round(rawScore());
        return (int) Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, rounded));
    }

    /** Operator-facing breakdown for the {@code /playermob life} readout. */
    public String breakdown() {
        return String.format(Locale.ROOT, "shopping %d (raw %.2f) | trades %d | emeralds spent %d earned %d",
            score(), rawScore(), trades, emeraldsSpent, emeraldsEarned);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_TRADES, trades);
        tag.putInt(TAG_SPENT, emeraldsSpent);
        tag.putInt(TAG_EARNED, emeraldsEarned);
        return tag;
    }

    /** Read back; every missing key is 0, so an absent tag loads as {@link #EMPTY}. */
    public static ShoppingTally load(CompoundTag tag) {
        return new ShoppingTally(
            NbtCompat.getIntOr(tag, TAG_TRADES, 0),
            NbtCompat.getIntOr(tag, TAG_SPENT, 0),
            NbtCompat.getIntOr(tag, TAG_EARNED, 0));
    }
}
