package games.brennan.playermob.entity;

/**
 * Per-mob rate limit on door operations, with a Fibonacci backoff for a mob that keeps
 * reaching for a door without getting anywhere.
 *
 * <p>A PlayerMob operates at most one door every {@link #LADDER_TICKS}{@code [0]} ticks (2 s).
 * If the previous operation didn't move it a full block, the next wait climbs the ladder —
 * 2, 3, 5, 8, 13, 21, 34 s — and holds at the last rung. Moving a whole block drops it
 * straight back to 2 s. So a mob walking a route through several doors pays only the flat
 * 2 s each time, while one flapping the same doorway backs off harder and harder.</p>
 *
 * <p><b>Why the close-behind is exempt.</b> {@code PlayerMobDoorGoal} opens a door, crosses,
 * then closes it behind ~1 s later. Those two changes are one logical operation, not two, so
 * the close is let through mid-window via the {@code exempt} flag — without it every "closes
 * doors" mob would silently become a "leaves doors open" mob. An exempt change re-arms at the
 * <em>current</em> rung without climbing: it is the second half of a slot already paid for.</p>
 *
 * <p><b>Why the caller supplies the position.</b> Off a train, progress is world-space
 * displacement. On a moving Dungeon Train carriage the mob's world position changes every tick
 * just from being carried along, so a mob standing still at a carriage door would look like it
 * had covered a block every time and never back off — progress must be measured in the
 * carriage's own (sub-level) coordinates. This class stays agnostic by taking whatever frame
 * the caller measured in, exactly as {@link DoorStuckMonitor} does; the caller picks the right
 * one. Only horizontal movement counts — doors block horizontal travel, and vertical jitter
 * (stairs, step-up, gravity) must not read as progress.</p>
 *
 * <p>Pure logic, no Minecraft types — the unit-tested core of the rate limit, as
 * {@link DoorStuckMonitor} is for the stuck-door recovery. Not thread-safe; used only from the
 * server thread, one instance per mob. Transient: a fresh ladder after a reload is correct,
 * since the flapping it throttles always restarts from scratch too.</p>
 */
public final class DoorChangeCooldown {

    /**
     * The backoff ladder, in ticks: 2, 3, 5, 8, 13, 21, 34 seconds. Fibonacci, so the wait
     * grows fast enough to stop a determined flapper without a mob ever going door-dead —
     * it holds at the last rung rather than growing without bound.
     */
    static final int[] LADDER_TICKS = {40, 60, 100, 160, 260, 420, 680};

    /**
     * Horizontal distance (squared, in blocks²) the mob must have covered since its last door
     * operation for that operation to count as having got it somewhere. One whole block.
     */
    static final double PROGRESS_DIST_SQR = 1.0;

    /** Ticks left before a non-exempt operation is allowed again (0 ⇒ ready). */
    private int ticks;
    /** Index into {@link #LADDER_TICKS} — how far up the backoff this mob currently sits. */
    private int rung;
    /** Whether {@link #anchorX}/{@link #anchorZ} hold a real prior operation to measure from. */
    private boolean anchored;
    private double anchorX;
    private double anchorZ;

    /** Advance one tick. Call once per server tick, on and off a train. */
    public void tick() {
        if (ticks > 0) {
            ticks--;
        }
    }

    /** Whether a non-exempt door operation is currently blocked. */
    public boolean isOnCooldown() {
        return ticks > 0;
    }

    /**
     * Ask to change a door now, from position {@code (x, z)} in the caller's chosen frame.
     *
     * @param exempt whether this is the paired close-behind of an operation already paid for —
     *               always allowed, and re-arms at the current rung without climbing
     * @return whether the change is allowed; when it is, the anchor moves here and the next
     *         window is armed. A refused change changes nothing at all — it neither extends
     *         the window, climbs the ladder, nor moves the anchor, so repeated blocked
     *         attempts can't starve the mob.
     */
    public boolean tryChange(double x, double z, boolean exempt) {
        if (!exempt) {
            if (ticks > 0) {
                return false;
            }
            // First ever operation has nothing to measure from, so it starts at the bottom.
            rung = (anchored && !movedAFullBlock(x, z))
                ? Math.min(rung + 1, LADDER_TICKS.length - 1)
                : 0;
        }
        anchored = true;
        anchorX = x;
        anchorZ = z;
        ticks = LADDER_TICKS[rung];
        return true;
    }

    private boolean movedAFullBlock(double x, double z) {
        double dx = x - anchorX;
        double dz = z - anchorZ;
        return dx * dx + dz * dz >= PROGRESS_DIST_SQR;
    }
}
