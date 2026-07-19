package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The reusable ballistic carry-velocity leap across a Dungeon-Train inter-group gap,
 * extracted from {@link CrossGroupGapGoal} so both the forward-exploration crossing and the
 * flee escape ({@link FleeFromCategoryGoal}) share <em>one</em> tuned trajectory rather than
 * duplicating it (the trajectory tuning is delicate — see issue #54).
 *
 * <p><b>A sprint jump that moves with the train.</b> The train translates through world space
 * every tick, and an airborne mob is no longer carried by the ship — so the leap first
 * <em>measures the train's carry velocity</em> from the mob's own per-tick displacement while it
 * stands at the gap edge (a riding mob standing still is moved only by the train). The leap is then
 * a ballistic sprint jump <em>relative to the train</em>: a vanilla jump straight up plus a
 * sustained sprint toward the target, both on top of the carry, with gravity arcing it back down
 * onto the far group — exactly how a sprinting player crosses the gap. Staying genuinely airborne
 * (a real arc, not a floor-skim) matters: Sable only sticks a riding mob to a carriage while it is
 * grounded, so a low skim re-grounds on the origin and gets re-grabbed mid-leap.</p>
 *
 * <p><b>Sized to the gap.</b> The arc scales to the measured seam width
 * ({@link TrainConfinement#groupGapWidth}, read once at {@link #launch}), because Dungeon Train
 * v0.471.0 tightened inter-group gaps to ~0.4 blocks and a fixed ~3.8-block sprint leap across a
 * hand's-width seam looked absurd. A wide or unmeasurable gap keeps the original sprint-jump
 * values, so this is a no-op wherever the seam isn't measurably tight.</p>
 *
 * <p><b>Lifecycle</b> — single leap at a time, owned by one goal instance:</p>
 * <ol>
 *   <li>{@link #trackCarry} each tick while standing/settling at the gap edge.</li>
 *   <li>{@link #launch} to take off (freezes the carry, vanilla jump + sprint toward the target,
 *       and {@link PlayerMobEntity#setCrossingGap setCrossingGap(true)} so a passing hostile can't
 *       yank the mob mid-air).</li>
 *   <li>{@link #tickFlight} each tick while airborne — returns {@code true} once landed
 *       (re-boarded the far group, or reached the target room).</li>
 *   <li>{@link #reset} to clear state when the owning goal stops or re-arms. The owning goal is
 *       responsible for clearing {@code setCrossingGap(false)} (on landing and on its own stop).</li>
 * </ol>
 */
final class GapLeap {

    /** ~2.5 blocks: close enough to the target room centre to count as "arrived". */
    private static final double REACH_DISTANCE_SQR = 6.25;

    /** Airborne backstop (6s). The owning goal enforces it via {@link #flightTicks()}. */
    static final int FLIGHT_TIMEOUT_TICKS = 120;

    // Sprint-jump tuning. The leap is a ballistic arc, like a player: a vanilla jump straight up
    // plus a sustained sprint toward the target (air control), then gravity brings it down onto the
    // far group. The sprint is added on top of the train carry, so it tracks a moving train; it must
    // clear a normal carriage-group gap the way a sprinting player does. Confirmed via in-game
    // trajectory diagnostics (#54): the earlier "gentle floor-skim" re-grounded on the origin and
    // stalled, and a held-altitude hover floated too high and too far.
    static final double SPRINT_SPEED = 0.38; // horizontal sprint toward the target, on top of carry
    static final double LAUNCH_UP = 0.42;    // vanilla jump impulse; gravity arcs the rest

    // Gap-proportionate hop. Dungeon Train v0.471.0 tightened inter-group seams from ~1.0 blocks
    // to ~0.4 (min 0.3, max 0.5), which made the sprint jump above read as comically oversized: it
    // always travelled SPRINT_SPEED * airtime ~= 3.8 blocks regardless of the gap, because
    // launchVelocity normalises to a fixed speed and the target only sets *direction*, never
    // magnitude. For a measurably tight gap we scale both the impulse and the sustained speed to
    // the gap; a wide or unmeasurable gap keeps the exact values above, so the flee escape and any
    // older/wider spacing behave bit-identically to before.
    /** Gaps at or below this (blocks) get the small step-over hop. Covers DT's 0.3-0.5 plus AABB
     *  jitter, while leaving wider spacing on the proven sprint jump. */
    static final double SMALL_GAP_THRESHOLD = 1.5;
    /** Step-over impulse: ~7.5 ticks airborne, peak ~0.56 blocks. Deliberately kept well above
     *  skim height — per #54 a low skim re-grounds on the origin and gets re-grabbed mid-leap. */
    static final double HOP_UP = 0.30;
    /** Blocks past the far edge to aim for: the launch point is the nav-done position, short of
     *  the true edge, and the mob must land on deck rather than on the lip. Erring long means a
     *  slight overshoot onto the far deck rather than falling short into the gap. */
    static final double LANDING_MARGIN = 1.5;
    /** Speed floor, so a near-zero gap can't produce a hop so slow the train carry dominates. */
    static final double MIN_HOP_SPEED = 0.10;
    /** Vanilla gravity, for the airtime estimate. */
    static final double GRAVITY_PER_TICK = 0.08;
    /** Grace before the grounded-landing check, so the launch tick isn't read as a landing. */
    private static final int MIN_AIRBORNE_TICKS = 3;

    private boolean launched = false;
    private boolean leftOrigin = false;
    private int flightTicks = 0;
    /** Sustained horizontal speed for the current hop, sized to the gap at {@link #launch}. */
    private double hopSpeed = SPRINT_SPEED;
    private Vec3 target;

    // Train carry tracking: the mob's world displacement per tick while riding == the
    // train's velocity. Measured during the approach/settle, frozen at launch, then added
    // to the flight velocity so the hop moves with the train.
    private boolean havePrevPos = false;
    private double prevX, prevY, prevZ;
    private Vec3 measuredCarry = Vec3.ZERO;
    private Vec3 launchCarry = Vec3.ZERO;

    /** True once {@link #launch} has fired and before {@link #reset}. */
    boolean isLaunched() {
        return launched;
    }

    /** Ticks elapsed since launch; {@code 0} until launched. The owning goal compares this
     *  against {@link #FLIGHT_TIMEOUT_TICKS} in its {@code canContinueToUse}. */
    int flightTicks() {
        return flightTicks;
    }

    /**
     * Measure the train's per-tick carry from the mob's world displacement. Call each tick while
     * the mob walks to / settles at the gap edge; while settled (not walking) the displacement is
     * pure carry, which is what {@link #launch} freezes.
     */
    void trackCarry(PlayerMobEntity mob) {
        if (havePrevPos) {
            measuredCarry = new Vec3(mob.getX() - prevX, mob.getY() - prevY, mob.getZ() - prevZ);
        }
        prevX = mob.getX();
        prevY = mob.getY();
        prevZ = mob.getZ();
        havePrevPos = true;
    }

    /**
     * Takeoff toward {@code target}: a sprint toward it plus the frozen train carry, with a jump
     * up. From here gravity does the arc (see {@link #tickFlight}) — a normal player sprint jump.
     * Marks the mob as crossing so the leap can't be preempted mid-air.
     *
     * <p>The arc is sized to {@code gapWidth} (blocks, from
     * {@link TrainConfinement#groupGapWidth}): a tight Dungeon-Train seam gets a small step-over,
     * a wide or {@link TrainConfinement#UNKNOWN_GAP} one the full sprint jump. The gap is read
     * once, here — like the carry it is frozen for the flight, never re-queried mid-air.</p>
     */
    void launch(PlayerMobEntity mob, Vec3 target, double gapWidth) {
        this.launched = true;
        this.leftOrigin = false;
        this.flightTicks = 0;
        this.target = target;
        this.launchCarry = measuredCarry; // freeze the train's carry velocity for the airborne phase
        double rise = hopRise(gapWidth);
        this.hopSpeed = hopSpeed(gapWidth, rise);
        mob.setCrossingGap(true);
        mob.getNavigation().stop();
        Vec3 fwd = launchVelocity(mob.position(), target, hopSpeed, 0.0);
        mob.setDeltaMovement(launchCarry.x + fwd.x, rise, launchCarry.z + fwd.z);
    }

    /**
     * Advance the airborne hop one tick: sustain the sprint toward the (carry-tracked) far group
     * on top of the carry, leaving the vertical to gravity.
     *
     * @return {@code true} once landed — the caller should stop / transition (and clear
     *         {@code setCrossingGap(false)}). The caller is also responsible for the flight-timeout
     *         backstop via {@link #flightTicks()}.
     */
    boolean tickFlight(PlayerMobEntity mob) {
        flightTicks++;

        // Back on solid ground: the reliable landing signal for a small hop. Neither signal below
        // fires at a tight seam — the mob's AABB may never leave a carriage box (so leftOrigin
        // never sets) and a short hop never gets within REACH_DISTANCE_SQR of the far room's
        // *centre* — which would strand the goal until FLIGHT_TIMEOUT_TICKS. MIN_AIRBORNE_TICKS
        // covers the launch tick itself; a mid-flight roof landing means we're already across.
        if (flightTicks >= MIN_AIRBORNE_TICKS && mob.onGround()) {
            return true;
        }

        boolean confined = TrainConfinement.isConfined(mob);

        // Landed? Check first, against last tick's target. Either we re-boarded a group after
        // leaving the origin's footprint, or we reached the target room (covers a gap small enough
        // we never left a box). Re-boarding is the primary signal and needs no target precision.
        if (!confined) {
            leftOrigin = true;
        } else if (leftOrigin
                || (target != null && mob.distanceToSqr(target.x, target.y, target.z) < REACH_DISTANCE_SQR)) {
            return true;
        }

        // Track the moving far group by advancing the frozen launch target by the train carry —
        // never re-resolve the group target mid-air: once the mob drifts it can latch onto a farther
        // group and the aim jumps (seen in diagnostics: horiz leapt 8 -> 40).
        if (target == null) {
            return true;
        }
        target = target.add(launchCarry);
        mob.getLookControl().setLookAt(target.x, target.y, target.z);

        Vec3 fwd = launchVelocity(mob.position(), target, hopSpeed, 0.0);
        Vec3 v = mob.getDeltaMovement();
        mob.setDeltaMovement(launchCarry.x + fwd.x, v.y, launchCarry.z + fwd.z);
        return false;
    }

    /** Clear all leap state so the owning goal can re-arm. Does not touch the mob's
     *  {@code crossingGap} flag — the owning goal clears that. */
    void reset() {
        launched = false;
        leftOrigin = false;
        flightTicks = 0;
        target = null;
        havePrevPos = false;
        measuredCarry = Vec3.ZERO;
        launchCarry = Vec3.ZERO;
        hopSpeed = SPRINT_SPEED;
    }

    /**
     * Vertical launch impulse for a gap of {@code gapWidth} blocks: a small step-over rise for a
     * measurably tight seam, the full sprint-jump impulse for a wide gap or an unmeasurable one
     * ({@link TrainConfinement#UNKNOWN_GAP}, i.e. off-train or without Dungeon Train).
     *
     * <p>Pure function of its input, so the trajectory tuning stays unit-testable — the same
     * reason {@link #launchVelocity} is static.</p>
     */
    static double hopRise(double gapWidth) {
        return isSmallGap(gapWidth) ? HOP_UP : LAUNCH_UP;
    }

    /**
     * Horizontal speed that carries the mob {@code gapWidth + LANDING_MARGIN} blocks during the
     * airtime of a {@code vy} impulse under vanilla gravity, clamped to
     * {@code [MIN_HOP_SPEED, SPRINT_SPEED]}. A wide or unmeasurable gap yields the full sprint
     * speed unchanged.
     *
     * <p>Airtime for an impulse {@code vy} is {@code 2 * vy / GRAVITY_PER_TICK} ticks (up and back
     * down to launch height), so distance is {@code speed * airtime} — invert that for the speed
     * that just covers the gap plus the landing margin.</p>
     */
    static double hopSpeed(double gapWidth, double vy) {
        if (!isSmallGap(gapWidth)) {
            return SPRINT_SPEED;
        }
        double airTicks = 2.0 * vy / GRAVITY_PER_TICK;
        double needed = gapWidth + LANDING_MARGIN;
        return Math.min(SPRINT_SPEED, Math.max(MIN_HOP_SPEED, needed / airTicks));
    }

    /** A gap small enough to step over: measurable (non-negative) and within the threshold. */
    private static boolean isSmallGap(double gapWidth) {
        return gapWidth >= 0.0 && gapWidth <= SMALL_GAP_THRESHOLD;
    }

    /**
     * Velocity that carries an entity at {@code from} toward {@code to} at
     * {@code horizontalSpeed} blocks/tick in the XZ plane, with vertical component
     * {@code vy}. Degenerate (target directly above/below) yields a purely vertical
     * vector. Pure function of its inputs — the hop's sprint aim is unit-tested through it.
     */
    static Vec3 launchVelocity(Vec3 from, Vec3 to, double horizontalSpeed, double vy) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0e-4) {
            return new Vec3(0.0, vy, 0.0);
        }
        double scale = horizontalSpeed / flat;
        return new Vec3(dx * scale, vy, dz * scale);
    }
}
