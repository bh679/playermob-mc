package games.brennan.playermob.entity;

/**
 * The odds and timings behind a PlayerMob's books ({@code goal/ReadBookGoal},
 * {@code goal/WriteBookGoal}). Pure maths over primitives so the curves unit-test without a world —
 * see {@code ReadingPolicyTest}.
 *
 * <ul>
 *   <li><b>Reading</b> and <b>Writing</b> are chances, not timers: each shelf a mob walks past gets
 *       one roll at {@link #passChance} — 0% at trait 0, {@link #MAX_PASS_CHANCE} at 10 — except a
 *       lectern, read in place at {@link #lecternChance} (up to 100%). A mob
 *       carrying a book &amp; quill rolls the same Writing chance every {@link #WRITE_CHECK_TICKS}
 *       while at rest to stop and write in it.</li>
 *   <li><b>Reading focus</b> sets how long a read or a writing session lasts ({@link #readTicks}).</li>
 *   <li>After each writing session it signs the book with {@link #SIGN_CHANCE}.</li>
 * </ul>
 */
public final class ReadingPolicy {

    /** The chance at trait 10 — even the most bookish mob walks past half the shelves. */
    public static final double MAX_PASS_CHANCE = 0.5;

    /** How often a mob carrying a book &amp; quill, at rest, considers writing in it (20 s). */
    public static final int WRITE_CHECK_TICKS = 400;
    /** Chance a writing session ends with the book signed (then dropped and burned). */
    public static final double SIGN_CHANCE = 0.25;

    /** Mean session at Reading focus 0 (2 s — a flick through) and at focus 10 (30 s). */
    public static final int MIN_READ_TICKS = 40;
    public static final int MAX_READ_TICKS = 600;
    /** How far chance stretches or shortens one session around its mean (±25%). */
    public static final double READ_SPREAD = 0.25;

    private ReadingPolicy() {}

    /**
     * The chance a mob stops at one shelf it is walking past (or, for Writing, sits down to write):
     * linear from 0 at trait 0 to {@link #MAX_PASS_CHANCE} at 10.
     *
     * @param trait the mob's Reading or Writing trait (0–10)
     */
    public static double passChance(int trait) {
        int clamped = Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, trait));
        return MAX_PASS_CHANCE * clamped / DispositionTraits.MAX;
    }

    /**
     * The chance a mob stops to read at a lectern it is walking past: linear from 0 at Reading 0 to
     * certain (100%) at 10 — a lectern holds a book already open, so a bookish mob never walks by.
     */
    public static double lecternChance(int reading) {
        int clamped = Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, reading));
        return (double) clamped / DispositionTraits.MAX;
    }

    /**
     * How long one read or writing session lasts, in ticks: set by the Reading-focus trait — a mean
     * from {@link #MIN_READ_TICKS} at focus 0 (a skimmer flicks through) to {@link #MAX_READ_TICKS}
     * at 10 — stretched or shortened by up to {@link #READ_SPREAD} by chance.
     *
     * @param focus the mob's Reading-focus trait (0–10)
     * @param roll  a uniform random in {@code [0, 1)}
     */
    public static int readTicks(int focus, double roll) {
        double trait = Math.max(0, Math.min(DispositionTraits.MAX, focus)) / (double) DispositionTraits.MAX;
        double chance = Math.max(0.0, Math.min(1.0, roll));
        double mean = MIN_READ_TICKS + (MAX_READ_TICKS - MIN_READ_TICKS) * trait;
        return (int) Math.round(mean * (1.0 - READ_SPREAD + 2.0 * READ_SPREAD * chance));
    }
}
