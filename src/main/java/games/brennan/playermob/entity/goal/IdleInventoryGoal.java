package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.IdleInventoryPolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Idle bag-check — a PlayerMob that has already come to rest occasionally stops to go through its
 * gear for a few seconds, the way a player stands in their inventory. Purely cosmetic: it holds
 * the mob still and flags {@link PlayerMobEntity#setIdleBrowsing}; the client draws the pose and
 * the floating panel from the synced activity.
 *
 * <p>Runs at the stroll goal's priority and claims {@code MOVE}+{@code LOOK}, so the mob doesn't
 * wander off mid-check, while every real objective (lower priority number) preempts it.</p>
 */
public final class IdleInventoryGoal extends Goal implements DescribableGoal {

    private final PlayerMobEntity mob;

    /** {@code mob.tickCount} at which the next check may start; {@code -1} = not scheduled yet. */
    private int nextAtTick = -1;
    /** Consecutive eligible {@link #canUse} checks — see {@link IdleInventoryPolicy#SETTLE_CHECKS}. */
    private int settledChecks;
    private int ticksLeft;

    public IdleInventoryGoal(PlayerMobEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!PlayerMobConfig.activityAnimations() || !isAtRest()) {
            settledChecks = 0;
            return false;
        }
        if (nextAtTick < 0 && !scheduleNext()) return false;
        settledChecks++;
        return settledChecks >= IdleInventoryPolicy.SETTLE_CHECKS && mob.tickCount >= nextAtTick;
    }

    @Override
    public boolean canContinueToUse() {
        return ticksLeft > 0 && mob.hurtTime == 0 && isAtRest() && PlayerMobConfig.activityAnimations();
    }

    @Override
    public void start() {
        ticksLeft = IdleInventoryPolicy.durationTicks(mob.getRandom().nextDouble());
        mob.getNavigation().stop();
        mob.setIdleBrowsing(true);
    }

    @Override
    public void stop() {
        mob.setIdleBrowsing(false);
        settledChecks = 0;
        scheduleNext();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticksLeft--;
    }

    @Override
    public String objective() {
        return "Checking bag";
    }

    /** Roll the next gap from now; {@code false} (and unscheduled) when the setting is off. */
    private boolean scheduleNext() {
        int gap = IdleInventoryPolicy.gapTicks(PlayerMobConfig.idleInventorySeconds(),
            mob.getRandom().nextDouble());
        if (gap == IdleInventoryPolicy.NEVER) {
            nextAtTick = -1;
            return false;
        }
        nextAtTick = mob.tickCount + gap;
        return true;
    }

    /** Standing still on the ground with nothing else going on. */
    private boolean isAtRest() {
        return mob.getTarget() == null
            && mob.getNavigation().isDone()
            && mob.onGround()
            && !mob.isCrouching()
            && !mob.isUsingItem()
            && !mob.isRecovering()
            && !mob.isDigging()
            && !mob.isCrossingGap()
            && !mob.isFleeing()
            && !mob.isOperatingDoor();
    }
}
