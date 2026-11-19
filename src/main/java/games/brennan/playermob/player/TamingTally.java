package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * One life's evidence of the player's way with animals — the input to the echo's <b>Taming</b>
 * hobby score: how many animals the player tamed, bred and fed by hand. Held in
 * {@link PlayerLifeRecord} as one field (like {@link ReflexTally}) so the record stays readable.
 *
 * <p><b>Scored as a volume, not a rate.</b> Animal handling is a hobby: the more of it a life did,
 * the more its echo cares for animals. Each act adds weighted points — taming weighs most, a
 * breeding less, a plain feed least — and the score saturates toward 10 against a prior of
 * {@link #PRIOR_POINTS} at 0, so a life with no evidence reads 0 (matching reaction speed's "no
 * score means 0") and no amount of farming quite reaches 10 without taming too.</p>
 *
 * <p>Immutable value type; pure (no world), so the scoring is unit-tested — see
 * {@code TamingTallyTest}.</p>
 */
public final class TamingTally {

    // ---- Scoring ----------------------------------------------------------
    /** Points per animal tamed — a standing commitment to keep something alive. */
    static final double TAME_POINTS = 3.0;
    /** Points per baby the player bred. */
    static final double BREED_POINTS = 1.0;
    /** Points per animal fed by hand (breeding feeds included: a pair is 2 feeds + 1 breed). */
    static final double FEED_POINTS = 0.25;
    /** The prior at 0: this many points of evidence score exactly 5. */
    static final double PRIOR_POINTS = 6.0;

    // ---- NBT --------------------------------------------------------------
    /** Key of this tally's compound inside the life record. */
    public static final String TAG = "Taming";
    static final String TAG_TAMES = "Tames";
    static final String TAG_BREEDS = "Breeds";
    static final String TAG_FEEDS = "Feeds";

    /** A life that has done nothing with animals yet. */
    public static final TamingTally EMPTY = new TamingTally(0, 0, 0);

    private final int tames;
    private final int breeds;
    private final int feeds;

    private TamingTally(int tames, int breeds, int feeds) {
        this.tames = Math.max(0, tames);
        this.breeds = Math.max(0, breeds);
        this.feeds = Math.max(0, feeds);
    }

    public int tames() { return tames; }
    public int breeds() { return breeds; }
    public int feeds() { return feeds; }

    public boolean isEmpty() {
        return tames == 0 && breeds == 0 && feeds == 0;
    }

    /** One more animal tamed. */
    public TamingTally withTame() {
        return new TamingTally(tames + 1, breeds, feeds);
    }

    /** One more baby bred. */
    public TamingTally withBreed() {
        return new TamingTally(tames, breeds + 1, feeds);
    }

    /** One more animal fed by hand. */
    public TamingTally withFeed() {
        return new TamingTally(tames, breeds, feeds + 1);
    }

    /** The weighted evidence behind {@link #score()}. */
    public double points() {
        return tames * TAME_POINTS + breeds * BREED_POINTS + feeds * FEED_POINTS;
    }

    /**
     * The Taming score, {@code [0, 10]}: {@code 10 · points / (points + PRIOR_POINTS)}, rounded.
     * 0 evidence → 0; two tames → 5; it approaches 10 but only a long animal-keeping life gets there.
     */
    public int score() {
        double points = points();
        double raw = DispositionTraits.MAX * points / (points + PRIOR_POINTS);
        long rounded = Math.round(raw);
        return (int) Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, rounded));
    }

    /** One-line breakdown for {@code /playermob life}. */
    public String breakdown() {
        return String.format(Locale.ROOT, "tamed %d, bred %d, fed %d (%.2f pts) → Taming %d",
            tames, breeds, feeds, points(), score());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_TAMES, tames);
        tag.putInt(TAG_BREEDS, breeds);
        tag.putInt(TAG_FEEDS, feeds);
        return tag;
    }

    /** Read back; missing keys are 0, so an absent tag loads as {@link #EMPTY}. */
    public static TamingTally load(CompoundTag tag) {
        TamingTally loaded = new TamingTally(
            NbtCompat.getIntOr(tag, TAG_TAMES, 0),
            NbtCompat.getIntOr(tag, TAG_BREEDS, 0),
            NbtCompat.getIntOr(tag, TAG_FEEDS, 0));
        return loaded.isEmpty() ? EMPTY : loaded;
    }
}
