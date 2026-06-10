package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * On a Dungeon Train, march the mob from the carriage room it's finished
 * exploring to the next room, in a fixed direction chosen on boarding.
 *
 * <p><b>Why this is so small.</b> Intra-room exploration — looting chests,
 * collecting drops, fighting mobs — is already done by the (train-confined)
 * {@link RaidContainersGoal}, {@link CollectFloorItemsGoal},
 * {@link HarvestCropsGoal}, and {@link WeaponAwareAttackGoal}, all registered at
 * a higher priority than this goal. So whenever there's anything to do in the
 * current room, one of those runs and this goal can't start (they share
 * {@code Flag.MOVE}). This goal only fires once the room is clear within those
 * goals' reach, and its job is purely to walk to the next room — at which point
 * the higher-priority goals see the new room's contents and take over again, or,
 * if the room is empty, this goal re-fires and hops to the room after. That
 * priority interplay is what produces "fully explore the room, then advance."</p>
 *
 * <p><b>Direction.</b> Read from {@link PlayerMobEntity#getTrainExploreDir()},
 * which is latched once on boarding (march toward carriage 0 and past it) and
 * kept thereafter, so the mob travels consistently even after crossing 0.</p>
 *
 * <p><b>Moving target.</b> The waypoint — the centre of the next room — is
 * re-resolved every tick via {@link TrainConfinement#nextCarriageTarget}, because
 * the carriage is a moving Sable sub-level. Pathing reuses the same
 * {@code GroundPathNavigation} that already loots/fights inside a moving carriage,
 * with {@link PlayerMobDoorGoal} opening interior doors en route.</p>
 *
 * <p><b>Scope.</b> Within a single carriage <em>group</em>. When the next room
 * would be in another group (a physical gap, no longer the same sub-level),
 * {@code nextCarriageTarget} returns {@code null} and this goal yields;
 * {@link CrossGroupGapGoal} (same priority) then leaps the gap to the adjacent
 * group, after which this goal resumes there. Off a train (always on Fabric/Forge,
 * and on NeoForge without Dungeon Train) {@link TrainConfinement#isConfined} is
 * false and this goal never engages, so behaviour is unchanged.</p>
 */
public final class AdvanceCarriageGoal extends Goal implements DescribableGoal {

    /** ~2.5 blocks: close enough to the next room's centre to count as "entered". */
    private static final double REACH_DISTANCE_SQR = 6.25;
    private static final int PATH_TIMEOUT_TICKS = 200;   // 10s to reach the next room (moving target + doors)
    private static final int REPATH_INTERVAL = 10;       // re-issue moveTo every 0.5s to track the moving room
    private static final int POST_VISIT_COOLDOWN = 10;   // 0.5s after a hop before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 20;   // 1s between checks when off-train / not yet latched
    private static final int BOUNDARY_COOLDOWN = 40;     // 2s at a group boundary (CrossGroupGapGoal leaps it)

    private final PlayerMobEntity mob;
    private final double moveSpeed;

    private int scanCooldown = 0;
    private int phaseTicks = 0;
    private int repathCooldown = 0;
    private Vec3 target;

    public AdvanceCarriageGoal(PlayerMobEntity mob, double moveSpeed) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Exploring train";
    }

    @Override
    public String subObjective() {
        return "next carriage";
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
        Vec3 next = TrainConfinement.nextCarriageTarget(mob, dir);
        if (next == null) {
            scanCooldown = BOUNDARY_COOLDOWN; // group boundary (behaviour #2) or unresolved
            return false;
        }
        target = next;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null
            && mob.isAlive()
            && !mob.isDeadOrDying()
            && mob.getTarget() == null
            && TrainConfinement.isConfined(mob)
            && phaseTicks <= PATH_TIMEOUT_TICKS;
    }

    @Override
    public void start() {
        phaseTicks = 0;
        repathCooldown = 0;
        issueMove();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
        phaseTicks = 0;
        repathCooldown = 0;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        // Re-resolve every tick: the carriage moves, so last tick's world point
        // has drifted. A null now means we crossed out of the group or lost the
        // carriage — give up this hop.
        Vec3 next = TrainConfinement.nextCarriageTarget(mob, mob.getTrainExploreDir());
        if (next == null) {
            stop();
            return;
        }
        target = next;
        mob.getLookControl().setLookAt(target.x, target.y, target.z);

        if (mob.distanceToSqr(target.x, target.y, target.z) < REACH_DISTANCE_SQR) {
            // Reached the next room's centre — well inside it. Stop so the
            // higher-priority raid/collect/combat goals can act on its contents;
            // if it's empty, canUse re-fires and hops to the following room.
            stop();
            return;
        }
        if (--repathCooldown <= 0 || mob.getNavigation().isDone()) {
            repathCooldown = REPATH_INTERVAL;
            issueMove();
        }
    }

    private void issueMove() {
        if (target != null) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, moveSpeed);
        }
    }
}
