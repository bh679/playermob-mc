package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;
import java.util.Locale;

/**
 * One life's evidence of how the player treated books, distilled into the echo's two reading
 * traits (each 0–10):
 * <ul>
 *   <li><b>Reading</b> ({@link #chanceScore()}) — how likely they are to open a book at all: the
 *       share of the books that came their way that they opened. Every book opened pushes it up;
 *       every book that left them <em>unread</em> — dropped, thrown in lava, burned away — pulls it
 *       down. The echo reaches for a shelf that often.</li>
 *   <li><b>Reading focus</b> ({@link #focusScore()}) — how long they stay with a book once open:
 *       the average of every reading session's length score. Long reads push it up; flicking a
 *       book open and shut pulls it down. The echo reads that long.</li>
 * </ul>
 *
 * <p><b>Scored as rates, not volumes</b>, like {@link ReflexTally}: each is a weighted average
 * that starts from a prior at 0 ({@link #CHANCE_PRIOR}, {@link #FOCUS_PRIOR}). So a life that
 * never touched a book scores 0, thin evidence leans toward 0, and either score falls as soon as
 * the player starts behaving the other way — and climbs back when they read properly again.</p>
 *
 * <p>Books are identified by a hash of their text (see {@link ReadingHooks#bookHash}), and each
 * list remembers at most {@link #MAX_REMEMBERED_BOOKS}. A book that left unread but was read later
 * in the same life — put on a lectern, then read there — counts as opened, not ignored.</p>
 *
 * <p>Immutable and pure (no world), so the scoring unit-tests without Minecraft — see
 * {@code ReadingTallyTest}. The live session tracking that feeds it is {@link ReadingTracker}.</p>
 */
public final class ReadingTally {

    // ---- Scoring ----------------------------------------------------------
    /** Weight of the 0 prior in the chance score: one opened book alone scores ~3. */
    static final double CHANCE_PRIOR = 2.0;
    /** Weight of the 0 prior in the focus score: one long read alone scores ~3. */
    static final double FOCUS_PRIOR = 2.0;
    /** Session length (seconds) scoring 0, 5 and 10 — linear between, clamped. */
    static final double SESSION_SKIM_SECONDS = 2.0;
    static final double SESSION_NEUTRAL_SECONDS = 20.0;
    static final double SESSION_DEEP_SECONDS = 60.0;
    /** Longest a single session can count for — an idle lectern can't run the average up. */
    static final int MAX_SESSION_SECONDS = 120;
    /** How many distinct book hashes each list remembers per life. */
    static final int MAX_REMEMBERED_BOOKS = 256;

    // ---- NBT --------------------------------------------------------------
    /** The tally's compound inside a saved {@link PlayerLifeRecord}. */
    public static final String TAG = "Reading";
    /** The 0–10 chance trait in an echo's snapshot and a PlayerMob's save data (top-level int). */
    public static final String TRAIT_TAG = "Reading";
    /** The 0–10 focus trait in an echo's snapshot and a PlayerMob's save data (top-level int). */
    public static final String FOCUS_TAG = "ReadingFocus";
    static final String TAG_OPENED = "Opened";
    static final String TAG_LEFT = "LeftUnread";
    static final String TAG_SESSIONS = "Sessions";
    static final String TAG_SESSION_SCORE = "SessionScore";
    static final String TAG_SESSION_SECONDS = "SessionSeconds";

    public static final ReadingTally EMPTY = new ReadingTally(new int[0], new int[0], 0, 0F, 0F);

    /** Distinct books opened this life. */
    private final int[] opened;
    /** Distinct books that left the player's inventory while unread (may include later-read ones). */
    private final int[] left;
    private final int sessions;
    /** Sum of every session's 0–10 length score. */
    private final float sessionScore;
    /** Sum of every session's (capped) length, for the readout. */
    private final float sessionSeconds;

    private ReadingTally(int[] opened, int[] left, int sessions, float sessionScore, float sessionSeconds) {
        this.opened = opened;
        this.left = left;
        this.sessions = Math.max(0, sessions);
        this.sessionScore = Math.max(0F, sessionScore);
        this.sessionSeconds = Math.max(0F, sessionSeconds);
    }

    // ---- Accessors --------------------------------------------------------

    /** Distinct books opened this life. */
    public int opened() {
        return opened.length;
    }

    /** Distinct books that left the player unread and were never read later this life. */
    public int ignored() {
        int n = 0;
        for (int h : left) {
            if (!contains(opened, h)) n++;
        }
        return n;
    }

    /** Reading sessions finished this life. */
    public int sessions() {
        return sessions;
    }

    /** Mean (capped) session length, seconds; 0 with no sessions. */
    public double averageSessionSeconds() {
        return sessions == 0 ? 0.0 : sessionSeconds / sessions;
    }

    public boolean hasOpened(int hash) {
        return contains(opened, hash);
    }

    public boolean isEmpty() {
        return opened.length == 0 && left.length == 0 && sessions == 0;
    }

    // ---- Credits ----------------------------------------------------------

    /** The player opened the book with {@code hash}. Counted once per book. */
    public ReadingTally withOpened(int hash) {
        int[] next = add(opened, hash);
        return next == opened ? this : new ReadingTally(next, left, sessions, sessionScore, sessionSeconds);
    }

    /** The book with {@code hash} left the player's inventory (not into a container). */
    public ReadingTally withLeft(int hash) {
        int[] next = add(left, hash);
        return next == left ? this : new ReadingTally(opened, next, sessions, sessionScore, sessionSeconds);
    }

    /** One reading session ended after {@code seconds} with the book open. */
    public ReadingTally withSession(int seconds) {
        int counted = Math.max(0, Math.min(MAX_SESSION_SECONDS, seconds));
        return new ReadingTally(opened, left, sessions + 1,
            sessionScore + (float) sessionLengthScore(counted), sessionSeconds + counted);
    }

    // ---- Scoring ----------------------------------------------------------

    /** 0 at a skim ({@value #SESSION_SKIM_SECONDS} s or less), 5 at 20 s, 10 at a minute or more. */
    static double sessionLengthScore(double seconds) {
        if (seconds <= SESSION_SKIM_SECONDS) return 0.0;
        if (seconds >= SESSION_DEEP_SECONDS) return 10.0;
        if (seconds <= SESSION_NEUTRAL_SECONDS) {
            return 5.0 * (seconds - SESSION_SKIM_SECONDS) / (SESSION_NEUTRAL_SECONDS - SESSION_SKIM_SECONDS);
        }
        return 5.0 + 5.0 * (seconds - SESSION_NEUTRAL_SECONDS) / (SESSION_DEEP_SECONDS - SESSION_NEUTRAL_SECONDS);
    }

    double rawChance() {
        int o = opened();
        return 10.0 * o / (o + ignored() + CHANCE_PRIOR);
    }

    double rawFocus() {
        return sessionScore / (sessions + FOCUS_PRIOR);
    }

    /** The echo's Reading trait (chance to open a book), 0–10. No books at all reads 0. */
    public int chanceScore() {
        return clamp(rawChance());
    }

    /** The echo's Reading-focus trait (how long it reads), 0–10. No sessions reads 0. */
    public int focusScore() {
        return clamp(rawFocus());
    }

    private static int clamp(double value) {
        long rounded = Math.round(value);
        return (int) Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, rounded));
    }

    /** Operator-facing breakdown for the debug readout. */
    public String breakdown() {
        return String.format(Locale.ROOT,
            "opened %d, left unread %d → chance %d (%.1f) | sessions %d, avg %.0fs → focus %d (%.1f)",
            opened(), ignored(), chanceScore(), rawChance(),
            sessions, averageSessionSeconds(), focusScore(), rawFocus());
    }

    // ---- NBT --------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray(TAG_OPENED, opened);
        tag.putIntArray(TAG_LEFT, left);
        tag.putInt(TAG_SESSIONS, sessions);
        tag.putFloat(TAG_SESSION_SCORE, sessionScore);
        tag.putFloat(TAG_SESSION_SECONDS, sessionSeconds);
        return tag;
    }

    /** Read back; missing keys are empty/0, so an absent tag loads as {@link #EMPTY}. */
    public static ReadingTally load(CompoundTag tag) {
        return new ReadingTally(
            capped(NbtCompat.getIntArrayOrEmpty(tag, TAG_OPENED)),
            capped(NbtCompat.getIntArrayOrEmpty(tag, TAG_LEFT)),
            NbtCompat.getIntOr(tag, TAG_SESSIONS, 0),
            NbtCompat.getFloatOr(tag, TAG_SESSION_SCORE, 0F),
            NbtCompat.getFloatOr(tag, TAG_SESSION_SECONDS, 0F));
    }

    // ---- helpers ----------------------------------------------------------

    private static boolean contains(int[] values, int hash) {
        for (int v : values) {
            if (v == hash) return true;
        }
        return false;
    }

    /** {@code values} plus {@code hash}, or {@code values} itself if present or full. */
    private static int[] add(int[] values, int hash) {
        if (contains(values, hash) || values.length >= MAX_REMEMBERED_BOOKS) return values;
        int[] next = Arrays.copyOf(values, values.length + 1);
        next[values.length] = hash;
        return next;
    }

    private static int[] capped(int[] values) {
        return values.length > MAX_REMEMBERED_BOOKS ? Arrays.copyOf(values, MAX_REMEMBERED_BOOKS) : values;
    }
}
