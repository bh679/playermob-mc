package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * On a Dungeon Train, carry the mob across the physical gap between two carriage
 * groups, so it keeps marching toward carriage 0 past a group boundary.
 *
 * <p><b>Where this fits.</b> {@link AdvanceCarriageGoal} walks the mob room-to-room
 * <em>within</em> a group and stops when the next room would be in another group
 * ({@link TrainConfinement#nextCarriageTarget} returns {@code null} — a physical gap,
 * separate Sable sub-levels). This goal takes over at exactly that point: it aims at
 * the adjacent group's nearest room ({@link TrainConfinement#nextGroupTarget}), walks
 * to the gap edge, then <em>hops</em> across and resumes within-group marching on the
 * far side. Both goals sit at priority 7 and share {@code Flag.MOVE}; they are mutually
 * exclusive because this one only engages once {@code nextCarriageTarget} is
 * {@code null}.</p>
 *
 * <p><b>Move <em>with</em> the train.</b> The train translates through world space every
 * tick, and an airborne mob is no longer carried by the ship — so driving the leap with
 * raw world-space velocity flings it off the train. Instead, the goal first <em>measures
 * the train's carry velocity</em> from the mob's own per-tick displacement while it stands
 * at the gap edge (a riding mob standing still is moved only by the train), then drives
 * the hop as {@code carry + a gentle drift toward the target + a floor-height controller}.
 * The carry keeps the mob locked to the train (it doesn't speed off or fall behind), the
 * drift is small (the mob crosses at roughly walking pace, not a boost), and the floor
 * controller keeps it level so it can never strand in the gap. No block-placement /
 * fall-recovery (behaviour #2) is needed.</p>
 *
 * <p>While committed to a hop the mob declines new combat targets
 * ({@link PlayerMobEntity#setCrossingGap}) so a passing hostile can't abandon it mid-air,
 * and the latched {@link PlayerMobEntity#getTrainExploreDir()} is never reset, so the mob
 * keeps the same direction on the far side.</p>
 *
 * <p>Off a train (always on Fabric/Forge, and on NeoForge without Dungeon Train)
 * {@link TrainConfinement#nextGroupTarget} is {@code null} and this goal never engages,
 * so behaviour is unchanged.</p>
 */
public final class CrossGroupGapGoal extends Goal {

    /** ~2.5 blocks: close enough to the next group's room centre to count as "arrived". */
    private static final double REACH_DISTANCE_SQR = 6.25;
    private static final int APPROACH_TIMEOUT_TICKS = 200; // 10s to reach the gap edge
    private static final int FLIGHT_TIMEOUT_TICKS = 120;   // 6s airborne backstop
    private static final int REPATH_INTERVAL = 10;         // re-issue approach moveTo every 0.5s
    private static final int SETTLE_TICKS = 3;             // nav done at the edge this long → hop (and carry is clean)
    private static final int POST_VISIT_COOLDOWN = 10;     // 0.5s after a crossing before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 20;     // 1s between checks when off-train / not latched
    private static final int BOUNDARY_COOLDOWN = 40;       // 2s when there's no further group to cross to

    // Hop tuning — the drift is the mob's speed *relative to the train*, so it stays gentle
    // regardless of how fast the train itself moves. Refined in-game at Gate 2.
    private static final double DRIFT_SPEED = 0.15; // blocks/tick toward the target, on top of the train carry
    private static final double LAUNCH_UP = 0.42;   // one modest upward hop to clear the carriage lip
    private static final double LIFT_GAIN = 0.30;   // proportional pull back toward the launch floor
    private static final double LIFT_MIN = -0.20;
    private static final double LIFT_MAX = 0.40;

    private final PlayerMobEntity mob;
    private final double moveSpeed;

    private int scanCooldown = 0;
    private int phaseTicks = 0;
    private int flightTicks = 0;
    private int settleTicks = 0;
    private int repathCooldown = 0;
    private boolean launched = false;
    private boolean leftOrigin = false;
    private double launchFloorY = 0.0;
    private Vec3 target;

    // Train carry tracking: the mob's world displacement per tick while riding == the
    // train's velocity. Measured during the approach/settle, frozen at launch, then added
    // to the flight velocity so the hop moves with the train.
    private boolean havePrevPos = false;
    private double prevX, prevY, prevZ;
    private Vec3 measuredCarry = Vec3.ZERO;
    private Vec3 launchCarry = Vec3.ZERO;

    public CrossGroupGapGoal(PlayerMobEntity mob, double moveSpeed) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        if (!TrainConfinement.isConfined(mob)) {
            scanCooldown = EMPTY_SCAN_COOLDOWN;
            return false;
        }
        if (mob.getTarget() != null) {
            return false; // combat preempts — recheck promptly once it's over
        }
        int dir = mob.getTrainExploreDir();
        if (dir == 0) {
            scanCooldown = EMPTY_SCAN_COOLDOWN; // not latched yet (geometry not yet trusted)
            return false;
        }
        if (TrainConfinement.nextCarriageTarget(mob, dir) != null) {
            // Still rooms to explore within this group — that's AdvanceCarriageGoal's job.
            scanCooldown = EMPTY_SCAN_COOLDOWN;
            return false;
        }
        Vec3 next = TrainConfinement.nextGroupTarget(mob, dir);
        if (next == null) {
            scanCooldown = BOUNDARY_COOLDOWN; // genuine end of the train this way
            return false;
        }
        target = next;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!mob.isAlive() || mob.isDeadOrDying()) {
            return false;
        }
        if (!launched) {
            // Walking to the gap edge: still on the origin group, no combat, not timed out.
            return mob.getTarget() == null
                && TrainConfinement.isConfined(mob)
                && phaseTicks <= APPROACH_TIMEOUT_TICKS;
        }
        // Committed to the hop: finish crossing (a bounded window) even if a target
        // appears, so the mob is never abandoned mid-gap.
        return flightTicks <= FLIGHT_TIMEOUT_TICKS;
    }

    @Override
    public void start() {
        phaseTicks = 0;
        flightTicks = 0;
        settleTicks = 0;
        repathCooldown = 0;
        launched = false;
        leftOrigin = false;
        havePrevPos = false;
        measuredCarry = Vec3.ZERO;
        launchCarry = Vec3.ZERO;
        issueMove();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.setCrossingGap(false);
        target = null;
        launched = false;
        leftOrigin = false;
        havePrevPos = false;
        measuredCarry = Vec3.ZERO;
        launchCarry = Vec3.ZERO;
        phaseTicks = 0;
        flightTicks = 0;
        settleTicks = 0;
        repathCooldown = 0;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (launched) {
            tickFlight();
        } else {
            tickApproach();
        }
    }

    /** Walk to the gap edge, tracking the train's carry velocity, then hop once settled. */
    private void tickApproach() {
        phaseTicks++;
        // World displacement since last tick == the train's carry (plus any walking; while
        // settled at the edge the mob isn't walking, so it's pure carry — which is what we
        // freeze at launch).
        if (havePrevPos) {
            measuredCarry = new Vec3(mob.getX() - prevX, mob.getY() - prevY, mob.getZ() - prevZ);
        }
        prevX = mob.getX();
        prevY = mob.getY();
        prevZ = mob.getZ();
        havePrevPos = true;

        Vec3 next = TrainConfinement.nextGroupTarget(mob, mob.getTrainExploreDir());
        if (next == null) {
            stop(); // lost the adjacent group
            return;
        }
        target = next;
        mob.getLookControl().setLookAt(target.x, target.y, target.z);

        // Vanilla navigation can't path over the gap, so it stops at the near edge and
        // reports done. A few ticks of "done at the edge" means we're as close as we can
        // walk (and the carry reading is clean) — hop from here.
        if (mob.getNavigation().isDone()) {
            if (++settleTicks >= SETTLE_TICKS) {
                launch();
            }
            return;
        }
        settleTicks = 0;
        if (--repathCooldown <= 0) {
            repathCooldown = REPATH_INTERVAL;
            issueMove();
        }
    }

    /** Drive the mob across the gap: move with the train (carry) + a gentle drift, floor-tracked. */
    private void tickFlight() {
        flightTicks++;

        // Landed? Check first, against last tick's target. Either we re-boarded a group
        // after leaving the origin's footprint, or we reached the target room (covers a gap
        // small enough we never left a box). Re-boarding is the primary signal and needs no
        // target precision.
        if (!TrainConfinement.isConfined(mob)) {
            leftOrigin = true;
        } else if (leftOrigin
                || (target != null && mob.distanceToSqr(target.x, target.y, target.z) < REACH_DISTANCE_SQR)) {
            stop();
            return;
        }

        // Re-aim. While the mob is still close enough for its carriage to resolve, track the
        // live group. Once it's out over the gap, carriageAt can't resolve it and
        // nextGroupTarget returns null — so advance the last target by the train carry, which
        // is exactly how fast the (rigidly attached) next group is moving, keeping the aim
        // locked on it instead of on a world-fixed stale point.
        Vec3 next = TrainConfinement.nextGroupTarget(mob, mob.getTrainExploreDir());
        if (next != null) {
            target = next;
        } else if (target != null) {
            target = target.add(launchCarry);
        }
        if (target == null) {
            stop();
            return;
        }
        mob.getLookControl().setLookAt(target.x, target.y, target.z);

        applyHopVelocity(Mth.clamp((launchFloorY - mob.getY()) * LIFT_GAIN, LIFT_MIN, LIFT_MAX));
    }

    private void launch() {
        launched = true;
        leftOrigin = false;
        flightTicks = 0;
        launchFloorY = mob.getY();
        launchCarry = measuredCarry; // freeze the train's carry velocity for the airborne phase
        mob.setCrossingGap(true);
        mob.getNavigation().stop();
        applyHopVelocity(LAUNCH_UP);
    }

    /**
     * Set the mob's velocity to {@code trainCarry + gentleDriftTowardTarget}, with the given
     * vertical component added to the carry's vertical. Keeps the hop moving with the train
     * rather than adding a large world-space velocity on top of it.
     */
    private void applyHopVelocity(double verticalComponent) {
        Vec3 drift = (target == null)
            ? Vec3.ZERO
            : launchVelocity(mob.position(), target, DRIFT_SPEED, 0.0); // horizontal drift only
        mob.setDeltaMovement(
            launchCarry.x + drift.x,
            launchCarry.y + verticalComponent,
            launchCarry.z + drift.z);
    }

    private void issueMove() {
        if (target != null) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, moveSpeed);
        }
    }

    /**
     * Velocity that carries an entity at {@code from} toward {@code to} at
     * {@code horizontalSpeed} blocks/tick in the XZ plane, with vertical component
     * {@code vy}. Degenerate (target directly above/below) yields a purely vertical
     * vector. Pure function of its inputs — the hop's drift aim is unit-tested through it.
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
