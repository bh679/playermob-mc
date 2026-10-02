package games.brennan.playermob.entity;

/**
 * Timing rules for the idle bag-check ({@code goal/IdleInventoryGoal}) — how long a PlayerMob that
 * is already standing still waits before glancing through its gear, and how long the glance lasts.
 * Pure maths over primitives so the windows are unit-tested without a world.
 */
public final class IdleInventoryPolicy {

    /** Server ticks per second. */
    private static final int TICKS_PER_SECOND = 20;

    /** Shortest bag-check, in ticks (2 s). */
    public static final int MIN_DURATION_TICKS = 40;

    /** Longest bag-check, in ticks (5 s). */
    public static final int MAX_DURATION_TICKS = 100;

    /**
     * How many consecutive eligible {@code canUse} checks the mob must pass before a bag-check may
     * start — it has to have actually come to rest, not merely be between two path nodes.
     */
    public static final int SETTLE_CHECKS = 10;

    /** Returned by {@link #gapTicks} when the bag-check is switched off. */
    public static final int NEVER = -1;

    private IdleInventoryPolicy() {}

    /**
     * Ticks to wait before the next bag-check: uniform over 0.5×–1.5× the configured interval, so
     * the mean is the interval and a room of mobs doesn't check in unison.
     *
     * @param intervalSeconds the {@code idleInventorySeconds} setting; {@code <= 0} disables
     * @param roll            a uniform random in {@code [0, 1)}
     * @return the gap in ticks, or {@link #NEVER}
     */
    public static int gapTicks(int intervalSeconds, double roll) {
        if (intervalSeconds <= 0) return NEVER;
        double clamped = Math.max(0.0, Math.min(1.0, roll));
        return (int) Math.round(intervalSeconds * (double) TICKS_PER_SECOND * (0.5 + clamped));
    }

    /**
     * How long one bag-check lasts, in ticks.
     *
     * @param roll a uniform random in {@code [0, 1)}
     */
    public static int durationTicks(double roll) {
        double clamped = Math.max(0.0, Math.min(1.0, roll));
        return MIN_DURATION_TICKS + (int) Math.round((MAX_DURATION_TICKS - MIN_DURATION_TICKS) * clamped);
    }
}
