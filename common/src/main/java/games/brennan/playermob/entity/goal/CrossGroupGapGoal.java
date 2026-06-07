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
 * to the gap edge, then <em>leaps</em> across and resumes within-group marching on the
 * far side. Both goals sit at priority 7 and share {@code Flag.MOVE}; they are mutually
 * exclusive because this one only engages once {@code nextCarriageTarget} is
 * {@code null}.</p>
 *
 * <p><b>Self-contained, never strands.</b> The leap is a driven hop, not free
 * ballistics: horizontal velocity is re-aimed at the (moving) target every tick, so the
 * mob always makes forward progress and crosses any gap width; vertical velocity tracks
 * the launch floor with a proportional controller, so the mob can never fall into the
 * gap. It therefore needs no block-placement / fall-recovery (behaviour #2). While
 * committed to a leap the mob declines new combat targets ({@code setCrossingGap}), so a
 * passing hostile can't abandon it mid-air. The march direction
 * {@link PlayerMobEntity#getTrainExploreDir()} is never reset, so it is preserved across
 * the jump and the mob keeps heading the same way on the far side.</p>
 *
 * <p>Off a train (always on Fabric/Forge, and on NeoForge without Dungeon Train)
 * {@link TrainConfinement#nextGroupTarget} is {@code null} and this goal never engages,
 * so behaviour is unchanged.</p>
 */
public final class CrossGroupGapGoal extends Goal {

    /** ~2.5 blocks: close enough to the next group's room centre to count as "arrived". */
    private static final double REACH_DISTANCE_SQR = 6.25;
    private static final int APPROACH_TIMEOUT_TICKS = 200; // 10s to reach the gap edge
    private static final int FLIGHT_TIMEOUT_TICKS = 60;    // 3s airborne backstop
    private static final int REPATH_INTERVAL = 10;         // re-issue approach moveTo every 0.5s
    private static final int SETTLE_TICKS = 3;             // nav done at the edge this long → launch
    private static final int POST_VISIT_COOLDOWN = 10;     // 0.5s after a crossing before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 20;     // 1s between checks when off-train / not latched
    private static final int BOUNDARY_COOLDOWN = 40;       // 2s when there's no further group to cross to

    // Leap tuning — sized for a guaranteed crossing; refined in-game at Gate 2.
    private static final double GLIDE_SPEED = 0.55; // horizontal blocks/tick while crossing the gap
    private static final double LAUNCH_UP = 0.5;    // initial upward hop impulse
    private static final double LIFT_GAIN = 0.35;   // proportional pull back toward the launch floor
    private static final double LIFT_MIN = -0.25;
    private static final double LIFT_MAX = 0.5;

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
        // Committed to the leap: finish crossing (a bounded window) even if a target
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
        issueMove();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.setCrossingGap(false);
        target = null;
        launched = false;
        leftOrigin = false;
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

    /** Walk to the gap edge, then launch once navigation can get no closer. */
    private void tickApproach() {
        phaseTicks++;
        Vec3 next = TrainConfinement.nextGroupTarget(mob, mob.getTrainExploreDir());
        if (next == null) {
            stop(); // lost the adjacent group
            return;
        }
        target = next;
        mob.getLookControl().setLookAt(target.x, target.y, target.z);

        // Vanilla navigation can't path over the gap, so it stops at the near edge and
        // reports done. A few ticks of "done at the edge" means we're as close as we can
        // walk — launch from here.
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

    /** Drive the mob across the gap: guaranteed horizontal progress, floor-tracking height. */
    private void tickFlight() {
        flightTicks++;
        Vec3 next = TrainConfinement.nextGroupTarget(mob, mob.getTrainExploreDir());
        if (next != null) {
            target = next; // re-aim at the moving group each tick
        }
        if (target == null) {
            stop();
            return;
        }
        mob.getLookControl().setLookAt(target.x, target.y, target.z);

        // Landed? Either we re-boarded a group after leaving the origin's footprint, or
        // we reached the target room (covers a gap small enough we never left a box).
        if (!TrainConfinement.isConfined(mob)) {
            leftOrigin = true;
        } else if (leftOrigin || mob.distanceToSqr(target.x, target.y, target.z) < REACH_DISTANCE_SQR) {
            stop();
            return;
        }

        double vy = Mth.clamp((launchFloorY - mob.getY()) * LIFT_GAIN, LIFT_MIN, LIFT_MAX);
        mob.setDeltaMovement(launchVelocity(mob.position(), target, GLIDE_SPEED, vy));
    }

    private void launch() {
        launched = true;
        leftOrigin = false;
        flightTicks = 0;
        launchFloorY = mob.getY();
        mob.setCrossingGap(true);
        mob.getNavigation().stop();
        if (target != null) {
            mob.setDeltaMovement(launchVelocity(mob.position(), target, GLIDE_SPEED, LAUNCH_UP));
        }
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
     * vector. Pure function of its inputs — the leap aim is unit-tested through it.
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
