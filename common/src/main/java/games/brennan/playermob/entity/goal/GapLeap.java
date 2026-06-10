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
    private static final double SPRINT_SPEED = 0.38; // horizontal sprint toward the target, on top of carry
    private static final double LAUNCH_UP = 0.42;    // vanilla jump impulse; gravity arcs the rest

    private boolean launched = false;
    private boolean leftOrigin = false;
    private int flightTicks = 0;
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
     * Sprint-jump takeoff toward {@code target}: a sprint toward it plus the frozen train carry,
     * with a vanilla jump up. From here gravity does the arc (see {@link #tickFlight}) — a normal
     * player sprint jump. Marks the mob as crossing so the leap can't be preempted mid-air.
     */
    void launch(PlayerMobEntity mob, Vec3 target) {
        this.launched = true;
        this.leftOrigin = false;
        this.flightTicks = 0;
        this.target = target;
        this.launchCarry = measuredCarry; // freeze the train's carry velocity for the airborne phase
        mob.setCrossingGap(true);
        mob.getNavigation().stop();
        Vec3 fwd = launchVelocity(mob.position(), target, SPRINT_SPEED, 0.0);
        mob.setDeltaMovement(launchCarry.x + fwd.x, LAUNCH_UP, launchCarry.z + fwd.z);
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

        Vec3 fwd = launchVelocity(mob.position(), target, SPRINT_SPEED, 0.0);
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
