package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;
import java.util.Locale;

/**
 * One life's evidence of the player's <em>reading</em> — time spent with a book screen open (a
 * written book, a book &amp; quill, or a book on a lectern) and how many different books they
 * read. Distilled by {@link #score()} into the echo's {@code Reading} trait (0–10): the more
 * bookish the life, the more often its echo pulls a book off a shelf and reads it.
 *
 * <p>Immutable value type, held in {@link PlayerLifeRecord} as one field. Pure (no world) so the
 * scoring is unit-testable — see {@code ReadingTallyTest}. The live, tick-timed session tracking
 * that produces these samples lives in {@link ReadingTracker}.</p>
 *
 * <p><b>Scored as a saturating volume.</b> Reading is a hobby, not a reflex: what matters is how
 * much of it the player did, but with diminishing returns so a long life can't run past the scale.
 * Two curves, both {@code 10·(1 − e^(−x/k))}: one over minutes read ({@link #MINUTES_SCALE}) and
 * one over distinct books ({@link #BOOKS_SCALE}), blended {@link #TIME_SHARE} to time. The prior is
 * 0 — a life that never opened a book reads 0, and a single short read only reaches 1–2.</p>
 *
 * <p>Distinct books are tracked by a content hash (see {@link ReadingTracker}), at most
 * {@link #MAX_REMEMBERED_BOOKS} per life, so re-opening the same book never inflates the count.</p>
 */
public final class ReadingTally {

    // ---- Scoring ----------------------------------------------------------
    /** Minutes of reading at which the time curve reaches ~6.3 (1 − 1/e of the scale). */
    static final double MINUTES_SCALE = 30.0;
    /** Distinct books at which the book curve reaches ~6.3. */
    static final double BOOKS_SCALE = 6.0;
    /** Share of the score that comes from time; the rest comes from distinct books. */
    static final double TIME_SHARE = 0.6;
    /** How many distinct book hashes one life remembers (later books still add time, not count). */
    static final int MAX_REMEMBERED_BOOKS = 256;

    // ---- NBT --------------------------------------------------------------
    /** The tally's compound inside a saved {@link PlayerLifeRecord}. */
    public static final String TAG = "Reading";
    /** The 0–10 trait in an echo's snapshot and a PlayerMob's save data (top-level int). */
    public static final String TRAIT_TAG = "Reading";
    static final String TAG_SECONDS = "Seconds";
    static final String TAG_BOOKS = "Books";
    static final String TAG_BOOK_HASHES = "BookHashes";

    public static final ReadingTally EMPTY = new ReadingTally(0F, 0, new int[0]);

    private final float seconds;
    private final int books;
    /** Hashes of the distinct books counted so far, in the order read. */
    private final int[] bookHashes;

    private ReadingTally(float seconds, int books, int[] bookHashes) {
        this.seconds = Math.max(0F, seconds);
        this.books = Math.max(0, books);
        this.bookHashes = bookHashes;
    }

    /** Seconds this life spent with a book open. */
    public float seconds() { return seconds; }

    /** Distinct books this life read through (each counted once). */
    public int books() { return books; }

    /** Whether the book with {@code hash} has already been counted this life. */
    public boolean hasRead(int hash) {
        for (int h : bookHashes) {
            if (h == hash) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return seconds == 0F && books == 0;
    }

    // ---- Credits ----------------------------------------------------------

    /** {@code delta} more seconds spent reading. */
    public ReadingTally withSeconds(float delta) {
        if (delta <= 0F) return this;
        return new ReadingTally(seconds + delta, books, bookHashes);
    }

    /**
     * One book read through. Counted only the first time this life reads that {@code hash};
     * past {@link #MAX_REMEMBERED_BOOKS} the count stops growing, so a farm of copies can't
     * run it up forever.
     */
    public ReadingTally withBook(int hash) {
        if (hasRead(hash) || bookHashes.length >= MAX_REMEMBERED_BOOKS) return this;
        int[] next = Arrays.copyOf(bookHashes, bookHashes.length + 1);
        next[bookHashes.length] = hash;
        return new ReadingTally(seconds, books + 1, next);
    }

    // ---- Scoring ----------------------------------------------------------

    /** {@code 10·(1 − e^(−x/scale))}: 0 at 0, ~6.3 at {@code scale}, approaching 10. */
    static double saturate(double x, double scale) {
        if (x <= 0) return 0.0;
        return 10.0 * (1.0 - Math.exp(-x / scale));
    }

    double timeScore() {
        return saturate(seconds / 60.0, MINUTES_SCALE);
    }

    double bookScore() {
        return saturate(books, BOOKS_SCALE);
    }

    /** The echo's Reading trait, 0–10. A life with no reading reads exactly 0. */
    public int score() {
        double blended = TIME_SHARE * timeScore() + (1.0 - TIME_SHARE) * bookScore();
        long rounded = Math.round(blended);
        return (int) Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, rounded));
    }

    /** Operator-facing breakdown for the debug readout. */
    public String breakdown() {
        return String.format(Locale.ROOT, "%.1f min (%.1f), %d books (%.1f) → %d",
            seconds / 60.0, timeScore(), books, bookScore(), score());
    }

    // ---- NBT --------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat(TAG_SECONDS, seconds);
        tag.putInt(TAG_BOOKS, books);
        tag.putIntArray(TAG_BOOK_HASHES, bookHashes);
        return tag;
    }

    /** Read back; missing keys are 0, so an absent tag loads as {@link #EMPTY}. */
    public static ReadingTally load(CompoundTag tag) {
        int[] hashes = NbtCompat.getIntArrayOrEmpty(tag, TAG_BOOK_HASHES);
        if (hashes.length > MAX_REMEMBERED_BOOKS) {
            hashes = Arrays.copyOf(hashes, MAX_REMEMBERED_BOOKS);
        }
        return new ReadingTally(NbtCompat.getFloatOr(tag, TAG_SECONDS, 0F),
            NbtCompat.getIntOr(tag, TAG_BOOKS, 0), hashes);
    }
}
