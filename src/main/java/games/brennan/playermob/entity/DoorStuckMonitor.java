package games.brennan.playermob.entity;

/**
 * Frame-agnostic "is the mob wedged in place?" detector that gates the off-train
 * close-a-blocking-door recovery (see {@code DoorObstruction} and
 * {@code PlayerMobEntity.recoverFromStuckDoor}).
 *
 * <p>Fed the mob's position once per tick, it reports {@code true} on the single
 * tick a recovery should fire: the mob has been <em>trying</em> to move yet made
 * no horizontal headway for {@link #STUCK_TICKS} consecutive ticks. After firing
 * it arms an internal {@link #COOLDOWN_TICKS} window so the recovery can't flap —
 * one close per window, and only if the mob is still stuck when it elapses.</p>
 *
 * <p><b>Why the caller supplies the position.</b> Off a train, "progress" is
 * world-space displacement. On a moving Dungeon Train carriage the mob's world
 * position changes every tick just from being carried along, so progress must be
 * measured in the carriage's own (sub-level) coordinates — otherwise a mob standing
 * still on a moving carriage would always look like it's moving. This class stays
 * agnostic by taking whatever frame the caller measured in; the caller picks the
 * right one. Only horizontal movement counts — doors block horizontal travel, and
 * vertical jitter (stairs, step-up, gravity) must not read as progress.</p>
 *
 * <p>Pure logic, no Minecraft types — the unit-tested core of the recovery, exactly
 * as {@code TrainConfinement.boardingDirection} is for boarding. Not thread-safe;
 * used only from the server thread, one instance per mob.</p>
 */
public final class DoorStuckMonitor {

    /**
     * Per-tick horizontal movement (squared, in blocks²) at or below which the mob
     * counts as "not progressing". ~0.02 blocks/tick — comfortably below a walking
     * mob's speed (~0.2–0.3) yet above idle physics jitter.
     */
    static final double PROGRESS_EPS_SQR = 0.02 * 0.02;

    /** Consecutive no-progress ticks (while trying to move) before a recovery fires. ~1.5 s. */
    static final int STUCK_TICKS = 30;

    /** Ticks after a fire during which the monitor stays silent, giving the close time to help. ~1.5 s. */
    static final int COOLDOWN_TICKS = 30;

    private boolean hasLast;
    private double lastX;
    private double lastZ;
    private int noProgressTicks;
    private int cooldownTicks;

    /**
     * Advance one tick.
     *
     * @param x            current X in the caller's chosen frame
     * @param z            current Z in the same frame
     * @param tryingToMove whether the mob is actively pathing (idle mobs never count as stuck)
     * @return {@code true} exactly on the tick a stuck-recovery should fire
     */
    public boolean tick(double x, double z, boolean tryingToMove) {
        return tick(x, z, tryingToMove, STUCK_TICKS, COOLDOWN_TICKS);
    }

    /**
     * As {@link #tick(double, double, boolean)}, but with caller-supplied thresholds — so a mob's
     * reaction speed can decide how long it flounders before noticing it is wedged, and how long
     * it waits before it is willing to notice again. Kept as parameters rather than a trait
     * dependency so this stays pure, primitives-only, and testable without a world.
     *
     * @param stuckTicks    consecutive no-progress ticks before recovery fires
     * @param cooldownTicks anti-flap window armed after firing
     */
    public boolean tick(double x, double z, boolean tryingToMove, int stuckTicks, int cooldownTicks) {
        boolean progressed = !hasLast || horizontalSqr(x, z) > PROGRESS_EPS_SQR;
        lastX = x;
        lastZ = z;
        hasLast = true;

        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            noProgressTicks = 0;
            return false;
        }
        if (!tryingToMove || progressed) {
            noProgressTicks = 0;
            return false;
        }
        if (++noProgressTicks >= stuckTicks) {
            noProgressTicks = 0;
            this.cooldownTicks = cooldownTicks;
            return true;
        }
        return false;
    }

    private double horizontalSqr(double x, double z) {
        double dx = x - lastX;
        double dz = z - lastZ;
        return dx * dx + dz * dz;
    }
}
