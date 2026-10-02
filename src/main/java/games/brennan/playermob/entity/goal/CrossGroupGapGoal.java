package games.brennan.playermob.entity.goal;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
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
 * to the gap edge, then <em>hops</em> across via {@link GapLeap} and resumes within-group
 * marching on the far side. Both goals sit at priority 7 and share {@code Flag.MOVE}; they
 * are mutually exclusive because this one only engages once {@code nextCarriageTarget} is
 * {@code null}.</p>
 *
 * <p>The hop itself — a ballistic sprint jump that moves with the train (issue #54) — lives
 * in {@link GapLeap}, shared with the flee escape ({@link FleeFromCategoryGoal}). While
 * committed to a hop the mob declines new combat targets
 * ({@link PlayerMobEntity#setCrossingGap}) so a passing hostile can't abandon it mid-air, and
 * the latched {@link PlayerMobEntity#getTrainExploreDir()} is never reset, so the mob keeps the
 * same fixed direction on the far side. The approach follows
 * {@link PlayerMobEntity#effectiveTrainMarchDir()}, so a mob crossing toward a player it loves aims
 * at the group nearest that player.</p>
 *
 * <p>Off a train (always on Fabric/Forge, and on NeoForge without Dungeon Train)
 * {@link TrainConfinement#nextGroupTarget} is {@code null} and this goal never engages,
 * so behaviour is unchanged.</p>
 */
public final class CrossGroupGapGoal extends Goal implements DescribableGoal {

    private static final int APPROACH_TIMEOUT_TICKS = 200; // 10s to reach the gap edge
    private static final int REPATH_INTERVAL = 10;         // re-issue approach moveTo every 0.5s
    private static final int SETTLE_TICKS = 3;             // nav done at the edge this long → hop (and carry is clean)
    private static final int POST_VISIT_COOLDOWN = 10;     // 0.5s after a crossing before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 20;     // 1s between checks when off-train / not latched
    private static final int BOUNDARY_COOLDOWN = 40;       // 2s when there's no further group to cross to

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final GapLeap leap = new GapLeap();

    private int scanCooldown = 0;
    private int phaseTicks = 0;
    private int settleTicks = 0;
    private int repathCooldown = 0;
    private Vec3 target;
    /** The leap has landed on the far group; the goal is finished and hands back to the march. */
    private boolean landed = false;

    public CrossGroupGapGoal(PlayerMobEntity mob, double moveSpeed) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Crossing gap";
    }

    @Override
    public String subObjective() {
        return leap.isLaunched() ? "leaping" : "approaching edge";
    }

    @Override
    public boolean canUse() {
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        if (!TrainConfinement.isConfined(mob)) {
            scanCooldown = mob.reactTicks(EMPTY_SCAN_COOLDOWN);
            return false;
        }
        if (mob.getTarget() != null) {
            return false; // combat preempts — recheck promptly once it's over
        }
        int dir = mob.effectiveTrainMarchDir();
        if (dir == 0) {
            scanCooldown = mob.reactTicks(EMPTY_SCAN_COOLDOWN); // not latched yet, or a loved player shares our carriage (idle with them)
            return false;
        }
        if (TrainConfinement.nextCarriageTarget(mob, dir) != null) {
            // Still rooms to explore within this group — that's AdvanceCarriageGoal's job.
            scanCooldown = mob.reactTicks(EMPTY_SCAN_COOLDOWN);
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
        if (!mob.isAlive() || mob.isDeadOrDying() || landed) {
            return false;
        }
        if (!leap.isLaunched()) {
            // Walking to the gap edge: still on the origin group, no combat, not timed out.
            return mob.getTarget() == null
                && TrainConfinement.isConfined(mob)
                && phaseTicks <= APPROACH_TIMEOUT_TICKS;
        }
        // Committed to the hop: finish crossing (a bounded window) even if a target
        // appears, so the mob is never abandoned mid-gap.
        return leap.flightTicks() <= GapLeap.FLIGHT_TIMEOUT_TICKS;
    }

    @Override
    public void start() {
        phaseTicks = 0;
        settleTicks = 0;
        repathCooldown = 0;
        landed = false;
        leap.reset();
        mob.setMarchingCarriages(true); // the door reflex may assume the train axis while we walk
        issueMove();
    }

    @Override
    public void stop() {
        // After a landing the walk to the next room is already under way (see onLanded) — leave it
        // running so the march goal picks it up without the mob ever coming to a halt.
        if (!landed) {
            mob.getNavigation().stop();
        }
        mob.setCrossingGap(false);
        mob.setMarchingCarriages(false);
        leap.reset();
        target = null;
        phaseTicks = 0;
        settleTicks = 0;
        repathCooldown = 0;
        // No rescan delay after a landing either: if the far group has no further room, the next
        // gap is this goal's job again straight away.
        scanCooldown = landed ? 0 : mob.reactTicks(POST_VISIT_COOLDOWN);
        landed = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (landed) {
            return; // finished; the selector stops us on its next pass
        }
        if (leap.isLaunched()) {
            if (leap.tickFlight(mob)) {
                onLanded();
            }
        } else {
            tickApproach();
        }
    }

    /**
     * Across, on the far group (walked or leapt): keep moving. Start the walk to the next room at once and flag the
     * crossing so {@link AdvanceCarriageGoal} takes over on the selector's next pass, rather than
     * the mob standing still while that goal's boundary cooldown runs out.
     */
    private void onLanded() {
        landed = true;
        mob.setCrossingGap(false);
        mob.markGapCrossed();
        leap.reset();
        int dir = mob.effectiveTrainMarchDir();
        Vec3 next = dir == 0 ? null : TrainConfinement.nextCarriageTarget(mob, dir);
        if (next != null && !PadWalk.drive(mob, next, moveSpeed)) {
            mob.getNavigation().moveTo(next.x, next.y, next.z, moveSpeed);
        }
    }

    /**
     * Get across to the next group. A seam narrow enough to walk ({@link TrainConfinement#seamWalkable},
     * which is every seam on a current Dungeon Train) is walked: out of the end door, along the pad,
     * over the seam and along the far pad, at the mob's own gait. Only a gap too wide to walk is
     * leapt — walk to its edge, track the train's carry velocity, then hop once settled.
     */
    private void tickApproach() {
        phaseTicks++;
        leap.trackCarry(mob);

        int dir = mob.effectiveTrainMarchDir();
        // A room ahead in this group again means we are across (we now stand on the far group) —
        // or were shoved back into our own. Either way the march goal takes it from here.
        if (dir != 0 && TrainConfinement.nextCarriageTarget(mob, dir) != null) {
            onLanded();
            return;
        }
        Vec3 next = dir == 0 ? null : TrainConfinement.nextGroupTarget(mob, dir);
        if (next == null) {
            stop(); // lost the adjacent group (or a loved player now shares our carriage)
            return;
        }
        target = next;
        mob.getLookControl().setLookAt(target.x, target.y, target.z);

        if (TrainConfinement.seamWalkable(mob, dir)) {
            // On the pad: straight down the centre line and over the seam. Not there yet: path to
            // our own pad (reachable — it is on our group), opening the end door on the way.
            if (!PadWalk.drive(mob, target, moveSpeed)
                    && (--repathCooldown <= 0 || mob.getNavigation().isDone())) {
                repathCooldown = mob.reactTicks(REPATH_INTERVAL);
                Vec3 pad = TrainConfinement.endPadTarget(mob, dir);
                Vec3 walkTo = pad != null ? pad : target;
                boolean ok = mob.getNavigation().moveTo(walkTo.x, walkTo.y, walkTo.z, moveSpeed);
                tracePath(walkTo, pad != null, ok);
            }
            return;
        }

        // Vanilla navigation can't path over the gap, so it stops at the near edge and
        // reports done. A few ticks of "done at the edge" means we're as close as we can
        // walk (and the carry reading is clean) — hop from here.
        if (mob.getNavigation().isDone()) {
            if (++settleTicks >= SETTLE_TICKS) {
                leap.launch(mob, target, TrainConfinement.groupGapWidth(mob, dir), /* urgent */ false);
            }
            return;
        }
        settleTicks = 0;
        if (--repathCooldown <= 0) {
            repathCooldown = mob.reactTicks(REPATH_INTERVAL);
            issueMove();
        }
    }

    /** Seam-walk diagnostics, gated on {@code debugSpawnLog} like the other Dungeon-Train traces. */
    private void tracePath(Vec3 walkTo, boolean toPad, boolean ok) {
        if (!PlayerMobConfig.debugSpawnLog()) {
            return;
        }
        Path path = mob.getNavigation().getPath();
        String desc = path == null ? "null"
            : "nodes=" + path.getNodeCount() + " next=" + path.getNextNodeIndex()
                + " end=" + (path.getEndNode() == null ? "?" : path.getEndNode().asBlockPos().toShortString())
                + " reach=" + path.canReach();
        LogUtils.getLogger().info(
            "[CrossGap] mob={} pos=({}, {}, {}) walkTo=({}, {}, {}) toPad={} ok={} path[{}] padSide={}",
            mob.getId(), String.format("%.2f", mob.getX()), String.format("%.2f", mob.getY()), String.format("%.2f", mob.getZ()),
            String.format("%.2f", walkTo.x), String.format("%.2f", walkTo.y), String.format("%.2f", walkTo.z),
            toPad, ok, desc, TrainConfinement.padSide(mob));
    }

    private void issueMove() {
        if (target != null) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, moveSpeed);
        }
    }
}
