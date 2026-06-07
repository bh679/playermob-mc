package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * Bow draw-strafe-and-fire combat goal for {@link PlayerMobEntity}.
 *
 * <p>Faithful port of vanilla {@code RangedBowAttackGoal}. As with
 * {@link PlayerMobCrossbowAttackGoal}, the only change is dropping the vanilla
 * {@code T extends Monster & RangedAttackMob} generic bound and binding
 * directly to {@link PlayerMobEntity} — PlayerMob extends
 * {@link net.minecraft.world.entity.PathfinderMob}, not {@code Monster}, so it
 * can't satisfy the vanilla bound. All calls here are {@code Mob} /
 * {@code LivingEntity} / {@code RangedAttackMob} APIs, so behaviour is
 * identical to vanilla skeleton bow combat.</p>
 */
public final class PlayerMobBowAttackGoal extends Goal {

    private final PlayerMobEntity mob;
    private final double speedModifier;
    private final int attackIntervalMin;
    private final float attackRadiusSqr;

    private int attackTime = -1;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;

    public PlayerMobBowAttackGoal(PlayerMobEntity mob, double speedModifier,
                                  int attackInterval, float attackRadius) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.attackIntervalMin = attackInterval;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.mob.getTarget() != null && this.isHoldingBow();
    }

    @Override
    public boolean canContinueToUse() {
        return (this.canUse() || !this.mob.getNavigation().isDone()) && this.isHoldingBow();
    }

    private boolean isHoldingBow() {
        return this.mob.isHolding(Items.BOW);
    }

    @Override
    public void start() {
        super.start();
        this.mob.setAggressive(true);
    }

    @Override
    public void stop() {
        super.stop();
        this.mob.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
        this.mob.stopUsingItem();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }

        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean canSee = this.mob.getSensing().hasLineOfSight(target);
        boolean hadSee = this.seeTime > 0;
        if (canSee != hadSee) {
            this.seeTime = 0;
        }
        if (canSee) {
            ++this.seeTime;
        } else {
            --this.seeTime;
        }

        if (!(distSqr > (double) this.attackRadiusSqr) && this.seeTime >= 20) {
            this.mob.getNavigation().stop();
            ++this.strafingTime;
        } else {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
            this.strafingTime = -1;
        }

        if (this.strafingTime >= 20) {
            if (this.mob.getRandom().nextFloat() < 0.3F) {
                this.strafingClockwise = !this.strafingClockwise;
            }
            if (this.mob.getRandom().nextFloat() < 0.3F) {
                this.strafingBackwards = !this.strafingBackwards;
            }
            this.strafingTime = 0;
        }

        if (this.strafingTime > -1) {
            if (distSqr > (double) (this.attackRadiusSqr * 0.75F)) {
                this.strafingBackwards = false;
            } else if (distSqr < (double) (this.attackRadiusSqr * 0.25F)) {
                this.strafingBackwards = true;
            }
            this.mob.getMoveControl().strafe(this.strafingBackwards ? -0.5F : 0.5F,
                this.strafingClockwise ? 0.5F : -0.5F);
            this.mob.lookAt(target, 30.0F, 30.0F);
        } else {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }

        if (this.mob.isUsingItem()) {
            if (!canSee && this.seeTime < -60) {
                this.mob.stopUsingItem();
            } else if (canSee) {
                int ticksUsing = this.mob.getTicksUsingItem();
                if (ticksUsing >= 20) {
                    this.mob.stopUsingItem();
                    this.mob.performRangedAttack(target, BowItem.getPowerForTime(ticksUsing));
                    this.attackTime = this.attackIntervalMin;
                }
            }
        } else if (--this.attackTime <= 0 && this.seeTime >= -60) {
            this.mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, Items.BOW));
        }
    }
}
