package games.brennan.playermob.entity;

/**
 * Timing rules for a PlayerMob reading ({@code goal/ReadBookGoal}): how often a mob with a given
 * Reading trait goes looking for a book, and how long it reads. Pure maths over primitives so the
 * curve is unit-tested without a world — see {@code ReadingPolicyTest}.
 *
 * <p>The trait sets the <em>rate</em>: a Reading-10 mob reaches for a book about every
 * {@link #GAP_SECONDS_AT_MAX} seconds; the gap grows as {@code MAX / reading}, so a Reading-1 mob
 * does it ten times less often, and Reading 0 never. Bookish mobs also read for longer.</p>
 */
public final class ReadingPolicy {

    private static final int TICKS_PER_SECOND = 20;

    /** Mean seconds between reads for a Reading-10 mob. */
    public static final int GAP_SECONDS_AT_MAX = 45;
    /** Gap after a search that found no shelf — try again a little later, not at the full gap. */
    public static final int NO_SHELF_RETRY_TICKS = 200;

    /** Shortest read, in ticks (5 s), and the longest (15 s). */
    public static final int MIN_READ_TICKS = 100;
    public static final int MAX_READ_TICKS = 300;

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
     * How long one read lasts, in ticks: half set by the trait (a bookish mob lingers), half by
     * chance, within {@link #MIN_READ_TICKS}–{@link #MAX_READ_TICKS}.
     *
     * @param reading the mob's Reading trait (0–10)
     * @param roll    a uniform random in {@code [0, 1)}
     */
    public static int readTicks(int reading, double roll) {
        double trait = Math.max(0, Math.min(DispositionTraits.MAX, reading)) / (double) DispositionTraits.MAX;
        double chance = Math.max(0.0, Math.min(1.0, roll));
        return MIN_READ_TICKS + (int) Math.round((MAX_READ_TICKS - MIN_READ_TICKS) * (0.5 * trait + 0.5 * chance));
    }
}
