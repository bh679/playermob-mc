package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.DispositionResolver;
import games.brennan.playermob.entity.FollowLovedOnePolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Makes a {@link PlayerMobEntity} catch up to an individual it has come to love
 * (feeling ≥ {@link DispositionResolver#FEELING_LOVE}) — a player or another PlayerMob —
 * when they drift too far apart, then rejoin normal behaviour once it's back together.
 *
 * <p><b>Catch-up, not a constant shadow.</b> Following only engages once the loved one is
 * <em>more than {@value FollowLovedOnePolicy#FOLLOW_CARRIAGES} carriages</em> away on a
 * Dungeon Train (separation = {@code carriageIndex} difference, monotonic along the whole
 * train), or more than {@link FollowLovedOnePolicy#FOLLOW_BLOCKS} blocks away anywhere else.
 * The mob then <b>sprints</b> ({@link FollowLovedOnePolicy#CATCH_UP_SPEED}) to close the gap
 * and releases once caught up ({@value FollowLovedOnePolicy#KEEP_CARRIAGES} carriage /
 * {@link FollowLovedOnePolicy#KEEP_BLOCKS} blocks). Inside that distance it just does its own
 * thing (raiding, exploring carriages, strolling) — so a pair travels the train together, each
 * doing its own thing, whoever falls behind sprinting to rejoin.</p>
 *
 * <p><b>Priority &amp; combat.</b> goalSelector priority 2, after the attack goal; mutually
 * exclusive with it via the {@code target == null} self-gate (target → fight, none → maybe
 * chase). At priority 2 a catch-up preempts the autonomous explore/stroll goals (3–8); when
 * <em>not</em> catching up it isn't running, so those drive normal behaviour. Yields to combat,
 * to the priority-1 social goals, and to {@link TrainRecoveryGoal} — a mob that fell off gets
 * back aboard first ({@code !isRecovering()}), and a loved one who fell off fails the same-train
 * {@code allowsTarget} gate, so they never chase each other off the train.</p>
 *
 * <p><b>Doors.</b> Crossing closed doors — and iron doors via their button/lever — to catch up
 * needs no code here: {@code PlayerMobEntity} opens any blocking door every tick while on a
 * train, whichever goal owns movement.</p>
 *
 * <p><b>Who, and mutual love.</b> The loved one is {@link PlayerMobEntity#findFollowTarget()};
 * for a mutual mob-pair that helper hands the chase role to just one of them (the higher-UUID
 * mob) so the other leads — see {@link FollowLovedOnePolicy#leads}.</p>
 */
public final class FollowLovedOneGoal extends Goal implements DescribableGoal {

    /** Re-path cadence: the loved one moves, so re-issue moveTo every ½s. */
    private static final int REPATH_INTERVAL = 10;
    /** Throttle the (wide) loved-one scan when there's no one far enough to chase (1s). */
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
        if (mob.getTarget() != null || mob.isRecovering() || mob.isCrossingGap()) {
            return false; // fight / get-back-aboard / mid-leap take precedence
        }
        LivingEntity candidate = mob.findFollowTarget();
        if (candidate == null || !tooFarFrom(candidate)) {
            scanCooldown = mob.reactTicks(IDLE_SCAN_COOLDOWN); // no one to chase right now — ease off the wide scan
            return false;
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
            && TrainConfinement.allowsTarget(mob, loved) // dropped if the loved one leaves the train
            && stillCatchingUp(loved);
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
            repathCooldown = mob.reactTicks(REPATH_INTERVAL);
            issueMove();
        }
    }

    /** Sprint toward the loved one's live position — following only ever happens when far. */
    private void issueMove() {
        if (loved != null) {
            mob.getNavigation().moveTo(loved, FollowLovedOnePolicy.CATCH_UP_SPEED);
        }
    }

    /** Whether the loved one is far enough to start a catch-up (carriages on a train, else blocks). */
    private boolean tooFarFrom(LivingEntity other) {
        int carriages = carriageSeparation(other);
        return carriages >= 0
            ? FollowLovedOnePolicy.followByCarriages(carriages)
            : FollowLovedOnePolicy.followByBlocks(mob.distanceTo(other));
    }

    /** Whether to keep catching up — not yet rejoined. */
    private boolean stillCatchingUp(LivingEntity other) {
        int carriages = carriageSeparation(other);
        return carriages >= 0
            ? FollowLovedOnePolicy.keepByCarriages(carriages)
            : FollowLovedOnePolicy.keepByBlocks(mob.distanceTo(other));
    }

    /**
     * Carriage separation along the train, or {@code -1} when either the mob or the loved one
     * isn't on a train (the block fallback applies then). {@code carriageIndex} is the room
     * index, monotonic along the whole train, so the absolute difference is literally how many
     * carriages apart they are.
     */
    private int carriageSeparation(LivingEntity other) {
        int mine = TrainConfinement.carriageIndex(mob);
        int theirs = TrainConfinement.carriageIndex(other);
        if (mine == TrainConfinement.NO_CARRIAGE || theirs == TrainConfinement.NO_CARRIAGE) {
            return -1;
        }
        return Math.abs(mine - theirs);
    }
}
