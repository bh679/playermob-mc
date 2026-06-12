package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.DispositionResolver;
import games.brennan.playermob.entity.FollowLovedOnePolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Makes a {@link PlayerMobEntity} tag along with an individual it has come to love
 * (feeling ≥ {@link DispositionResolver#FEELING_LOVE}) — a player or another PlayerMob.
 * Companionship: it trails the loved one, sprints to catch up when they pull away, and
 * parks nearby when close so its own goals (raiding, exploring, strolling) get a turn.
 *
 * <p><b>Priority &amp; combat.</b> Registered at goalSelector priority 2, right after
 * {@link WeaponAwareAttackGoal}. Both want the MOVE/LOOK flags, but they're mutually
 * exclusive: the attack goal needs a target and this goal self-gates on {@code target ==
 * null}. So a combat target means fight; no target means follow. Sitting at priority 2 lets
 * following preempt every "own task" (raid 3, harvest 6, train-advance 7, stroll 8) while
 * still yielding to combat and the priority-1 social/recovery goals — exactly "deprioritise
 * its own tasks to follow the one it loves." <b>Joining its fights</b> then needs no code
 * here: following puts the mob beside the loved one, where the existing target goals
 * ({@code NearestAttackableTargetGoal} / {@link DefendLovedOneGoal}) acquire a foe and the
 * attack goal engages.</p>
 *
 * <p><b>Who, and mutual love.</b> The loved one is chosen by
 * {@link PlayerMobEntity#findFollowTarget()} (nearest, most-loved, train-allowed). For a
 * mutual mob-pair that helper hands the follow role to just one of them (the higher-UUID
 * mob) so they travel together instead of freezing face-to-face — see
 * {@link FollowLovedOnePolicy#leads}.</p>
 *
 * <p><b>Distances</b> ({@link FollowLovedOnePolicy}): start following past {@code START}
 * (6), keep going until parked within {@code STOP} (3), sprint past {@code SPRINT} (10), and
 * lose the loved one past {@code SCAN} (24). Not train-gated — it works in any world; the
 * Dungeon Train only adds the carriage-exploring feel (the follower trails its loved one
 * room-to-room while the leader's {@link AdvanceCarriageGoal} drives the pair forward).</p>
 */
public final class FollowLovedOneGoal extends Goal implements DescribableGoal {

    /** Re-path cadence: the loved one moves, so re-issue moveTo every ½s (as AdvanceCarriageGoal). */
    private static final int REPATH_INTERVAL = 10;
    /** Throttle the loved-one scan in {@link #canUse()} when there's no one to follow (1s). */
    private static final int IDLE_SCAN_COOLDOWN = 20;

    private final PlayerMobEntity mob;

    private LivingEntity loved;
    private int scanCooldown;
    private int repathCooldown;

    public FollowLovedOneGoal(PlayerMobEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Following";
    }

    @Override
    public String subObjective() {
        return loved == null ? null : loved.getName().getString();
    }

    @Override
    public boolean canUse() {
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        if (mob.getTarget() != null) {
            return false; // a target means fight — the attack goal owns this slot
        }
        if (mob.isRecovering() || mob.isCrossingGap()) {
            return false; // getting back aboard / mid-leap takes precedence
        }
        LivingEntity candidate = mob.findFollowTarget();
        if (candidate == null) {
            scanCooldown = IDLE_SCAN_COOLDOWN;
            return false;
        }
        if (!FollowLovedOnePolicy.wantsToFollow(mob.distanceTo(candidate))) {
            return false; // already close enough — parked, let the own-task goals run
        }
        this.loved = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return loved != null
            && loved.isAlive()
            && mob.getTarget() == null
            && !mob.isRecovering()
            && !mob.isCrossingGap()
            && mob.feelingToward(loved) >= DispositionResolver.FEELING_LOVE
            && TrainConfinement.allowsTarget(mob, loved) // don't trail a loved one off the train
            && FollowLovedOnePolicy.keepFollowing(mob.distanceTo(loved));
    }

    @Override
    public void start() {
        repathCooldown = 0;
        issueMove();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        loved = null;
        repathCooldown = 0;
        scanCooldown = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (loved == null) {
            return;
        }
        mob.getLookControl().setLookAt(loved, 30.0F, 30.0F);
        if (--repathCooldown <= 0 || mob.getNavigation().isDone()) {
            repathCooldown = REPATH_INTERVAL;
            issueMove();
        }
    }

    /** Walk (or sprint, when far) toward the loved one's live position. */
    private void issueMove() {
        if (loved != null) {
            double speed = FollowLovedOnePolicy.speedFor(mob.distanceTo(loved));
            mob.getNavigation().moveTo(loved, speed);
        }
    }
}
