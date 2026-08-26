package games.brennan.playermob.entity;

/**
 * Frame-agnostic "is this arrow about to hit me?" test that drives the
 * raise-a-shield-against-incoming-fire reflex (see {@code BlockArrowsGoal}).
 *
 * <p>Given the mob's centre, an arrow's position, and the arrow's per-tick
 * velocity, {@link #isIncoming} projects the arrow forward along a straight line
 * and reports whether its closest approach passes within {@code hitRadius} of the
 * mob, soon enough to matter. Straight-line is a deliberate simplification —
 * arrows do drop under gravity, but over the &lt;1&nbsp;s lead window the reflex
 * cares about, the linear closest-approach is close enough to decide "raise the
 * shield now", and the mob re-evaluates every tick as the real trajectory curves.</p>
 *
 * <p>Pure logic, no Minecraft types in the signature — the unit-tested core of the
 * reflex, exactly as {@link DoorStuckMonitor} is for the stuck-door recovery. The
 * caller ({@code BlockArrowsGoal}) does the entity scan and supplies the numbers.
 * Not thread-safe state (it has none); used only from the server thread.</p>
 */
public final class ArrowThreat {

    /**
     * Velocity magnitude (squared, in blocks²/tick²) below which an arrow counts
     * as "not really flying" — a landed/stuck arrow, which vanilla parks at ~zero
     * delta-movement. ~0.1 blocks/tick: well under a live arrow's speed (a freshly
     * fired arrow travels ~3 b/t) yet above the residual jitter of a settled one.
     * Lets the caller skip an explicit "in ground" check and rely on this gate.
     */
    static final double MIN_SPEED_SQR = 0.1 * 0.1;

    private ArrowThreat() {}

    /**
     * Whether {@code arrow} is on a near-collision course with the mob.
     *
     * @param mobX       mob centre X
     * @param mobY       mob centre Y (use the bounding-box centre, not the feet)
     * @param mobZ       mob centre Z
     * @param arrowX     arrow X
     * @param arrowY     arrow Y
     * @param arrowZ     arrow Z
     * @param velX       arrow per-tick velocity X (delta-movement)
     * @param velY       arrow per-tick velocity Y
     * @param velZ       arrow per-tick velocity Z
     * @param hitRadius  block radius around the mob centre that counts as a hit
     * @param maxLeadTicks reject threats whose closest approach is further than
     *                     this many ticks in the future (an anticipation horizon)
     * @return {@code true} if the arrow is moving toward the mob and its projected
     *         closest approach lands within {@code hitRadius} within the horizon
     */
    public static boolean isIncoming(double mobX, double mobY, double mobZ,
                                     double arrowX, double arrowY, double arrowZ,
                                     double velX, double velY, double velZ,
                                     double hitRadius, double maxLeadTicks) {
        return isIncoming(mobX, mobY, mobZ, arrowX, arrowY, arrowZ, velX, velY, velZ,
            hitRadius, 0.0, maxLeadTicks);
    }

    /**
     * As {@link #isIncoming(double, double, double, double, double, double, double, double,
     * double, double, double)}, but with a <b>minimum</b> lead as well as a maximum: an arrow
     * whose closest approach is nearer than {@code minLeadTicks} is reported as no threat,
     * because the mob could not physically react in time.
     *
     * <p>That lower bound is what gives reaction speed teeth here. The upper bound alone means
     * every mob blocks everything it sees; a floor means a slow mob eats the point-blank shot
     * it "saw" coming, while a fast one ({@code minLeadTicks == 0}) blocks it.</p>
     *
     * @param minLeadTicks ignore threats landing sooner than this many ticks from now;
     *                     {@code 0} disables the floor
     */
    public static boolean isIncoming(double mobX, double mobY, double mobZ,
                                     double arrowX, double arrowY, double arrowZ,
                                     double velX, double velY, double velZ,
                                     double hitRadius, double minLeadTicks, double maxLeadTicks) {
        double speedSqr = velX * velX + velY * velY + velZ * velZ;
        if (speedSqr < MIN_SPEED_SQR) {
            return false; // landed / stalled arrow — no threat
        }

        // Vector from the arrow to the mob centre.
        double dx = mobX - arrowX;
        double dy = mobY - arrowY;
        double dz = mobZ - arrowZ;

        // Component of that vector along the arrow's heading. <= 0 ⇒ the arrow is
        // level with or moving away from the mob, never toward it.
        double approach = dx * velX + dy * velY + dz * velZ;
        if (approach <= 0) {
            return false;
        }

        // Ticks until the arrow is closest to the mob (project onto the heading).
        // Units: (blocks·blocks/tick) / (blocks/tick)² = ticks.
        double ticksToClosest = approach / speedSqr;
        if (ticksToClosest > maxLeadTicks) {
            return false; // too far in the future to start blocking yet
        }
        if (ticksToClosest < minLeadTicks) {
            return false; // inside the reflex blind window — no time to get the shield up
        }

        // Closest-approach point along the straight-line projection, and how far
        // it misses the mob centre by.
        double closestX = arrowX + velX * ticksToClosest;
        double closestY = arrowY + velY * ticksToClosest;
        double closestZ = arrowZ + velZ * ticksToClosest;
        double missX = mobX - closestX;
        double missY = mobY - closestY;
        double missZ = mobZ - closestZ;
        double missSqr = missX * missX + missY * missY + missZ * missZ;

        return missSqr <= hitRadius * hitRadius;
    }
}
