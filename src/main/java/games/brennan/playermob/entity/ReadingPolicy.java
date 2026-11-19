package games.brennan.playermob.entity;

/**
 * Timing rules for a PlayerMob reading ({@code goal/ReadBookGoal}): how often a mob with a given
 * Reading trait goes looking for a book, and how long it reads. Pure maths over primitives so the
 * curve is unit-tested without a world — see {@code ReadingPolicyTest}.
 *
 * <p>Two traits: <b>Reading</b> sets the <em>rate</em> — a Reading-10 mob reaches for a book about
 * every {@link #GAP_SECONDS_AT_MAX} seconds; the gap grows as {@code MAX / reading}, so a Reading-1
 * mob does it ten times less often, and Reading 0 never. <b>Reading focus</b> sets how long each
 * read lasts ({@link #readTicks}).</p>
 */
public final class ReadingPolicy {

    private static final int TICKS_PER_SECOND = 20;

    /** Mean seconds between reads for a Reading-10 mob. */
    public static final int GAP_SECONDS_AT_MAX = 45;
    /** Gap after a search that found no shelf — try again a little later, not at the full gap. */
    public static final int NO_SHELF_RETRY_TICKS = 200;

    /** Mean read at Reading focus 0 (2 s — a flick through) and at focus 10 (30 s). */
    public static final int MIN_READ_TICKS = 40;
    public static final int MAX_READ_TICKS = 600;
    /** How far chance stretches or shortens one read around its mean (±25%). */
    public static final double READ_SPREAD = 0.25;

    /** Returned by {@link #gapTicks} for a mob that never reads. */
    public static final int NEVER = -1;

    private ReadingPolicy() {}

    /**
     * Ticks to wait before the next read: uniform over 0.5×–1.5× the mean, which is
     * {@link #GAP_SECONDS_AT_MAX} × {@code 10 / reading}. Spread so a room of readers doesn't
     * reach for the shelves in unison.
     *
     * @param reading the mob's Reading trait (0–10); 0 never reads
     * @param roll    a uniform random in {@code [0, 1)}
     * @return the gap in ticks, or {@link #NEVER}
     */
    public static int gapTicks(int reading, double roll) {
        if (reading <= 0) return NEVER;
        int clampedReading = Math.min(DispositionTraits.MAX, reading);
        double mean = GAP_SECONDS_AT_MAX * TICKS_PER_SECOND * (double) DispositionTraits.MAX / clampedReading;
        double clamped = Math.max(0.0, Math.min(1.0, roll));
        return (int) Math.round(mean * (0.5 + clamped));
    }

    /**
     * How long one read lasts, in ticks: set by the Reading-focus trait — a mean from
     * {@link #MIN_READ_TICKS} at focus 0 (a skimmer flicks through and shelves it) to
     * {@link #MAX_READ_TICKS} at 10 — stretched or shortened by up to {@link #READ_SPREAD} by chance.
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
