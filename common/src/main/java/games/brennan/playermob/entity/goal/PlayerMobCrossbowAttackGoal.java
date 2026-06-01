package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;

import java.util.EnumSet;

/**
 * Crossbow charge-and-fire combat goal for {@link PlayerMobEntity}.
 *
 * <p>This is a faithful port of vanilla {@code RangedCrossbowAttackGoal}. The
 * only reason we can't use the vanilla class directly is its generic bound
 * {@code T extends Monster & RangedAttackMob & CrossbowAttackMob}: PlayerMob
 * deliberately extends {@link net.minecraft.world.entity.PathfinderMob} (NOT
 * {@code Monster}) so iron golems don't attack it on sight. Dropping the
 * {@code Monster} bound and binding directly to {@link PlayerMobEntity} is the
 * only change — every method called here is declared on {@code Mob} /
 * {@code LivingEntity} / {@code RangedAttackMob} / {@code CrossbowAttackMob},
 * none on {@code Monster}, so behaviour is identical to vanilla.</p>
 */
public final class PlayerMobCrossbowAttackGoal extends Goal {

    /** Re-path cadence while closing distance, in ticks (1–2s). Matches vanilla. */
    private static final UniformInt PATHFINDING_DELAY_RANGE = TimeUtil.rangeOfSeconds(1, 2);

    private final PlayerMobEntity mob;
    private final double speedModifier;
    private final float attackRadiusSqr;

    private CrossbowState crossbowState = CrossbowState.UNCHARGED;
    private int seeTime;
    private int attackDelay;
    private int updatePathDelay;

    public PlayerMobCrossbowAttackGoal(PlayerMobEntity mob, double speedModifier, float attackRadius) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.isValidTarget() && this.isHoldingCrossbow();
    }

    @Override
    public boolean canContinueToUse() {
        return this.isValidTarget()
            && (this.canUse() || !this.mob.getNavigation().isDone())
            && this.isHoldingCrossbow();
    }

    private boolean isHoldingCrossbow() {
        return this.mob.isHolding(stack -> stack.getItem() instanceof CrossbowItem);
    }

    private boolean isValidTarget() {
        return this.mob.getTarget() != null && this.mob.getTarget().isAlive();
    }

    @Override
    public void stop() {
        super.stop();
        this.seeTime = 0;
        if (this.mob.isUsingItem()) {
            this.mob.stopUsingItem();
            this.mob.setChargingCrossbow(false);
            // 1.21.1: charged state lives in the CHARGED_PROJECTILES component
            // (vanilla's CrossbowItem.setCharged(stack, false) was removed).
            // Clearing it to EMPTY is the equivalent of "uncharged".
            this.mob.getUseItem().set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
        }
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

        double distSqr = this.mob.distanceToSqr(target);
        boolean shouldClose = (distSqr > (double) this.attackRadiusSqr || this.seeTime < 5)
            && this.attackDelay == 0;
        if (shouldClose) {
            --this.updatePathDelay;
            if (this.updatePathDelay <= 0) {
                this.mob.getNavigation().moveTo(target,
                    this.canRun() ? this.speedModifier : this.speedModifier * 0.5);
                this.updatePathDelay = PATHFINDING_DELAY_RANGE.sample(this.mob.getRandom());
            }
        } else {
            this.updatePathDelay = 0;
            this.mob.getNavigation().stop();
        }

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (this.crossbowState == CrossbowState.UNCHARGED) {
            if (!shouldClose) {
                this.mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, Items.CROSSBOW));
                this.crossbowState = CrossbowState.CHARGING;
                this.mob.setChargingCrossbow(true);
            }
        } else if (this.crossbowState == CrossbowState.CHARGING) {
            if (!this.mob.isUsingItem()) {
                this.crossbowState = CrossbowState.UNCHARGED;
            }
            int ticksUsing = this.mob.getTicksUsingItem();
            ItemStack inUse = this.mob.getUseItem();
            if (ticksUsing >= CrossbowItem.getChargeDuration(inUse, this.mob)) {
                this.mob.releaseUsingItem();
                this.crossbowState = CrossbowState.CHARGED;
                this.attackDelay = 20 + this.mob.getRandom().nextInt(20);
                this.mob.setChargingCrossbow(false);
            }
        } else if (this.crossbowState == CrossbowState.CHARGED) {
            --this.attackDelay;
            if (this.attackDelay == 0) {
                this.crossbowState = CrossbowState.READY_TO_ATTACK;
            }
        } else if (this.crossbowState == CrossbowState.READY_TO_ATTACK && canSee) {
            this.mob.performRangedAttack(target, 1.0F);
            // The shooting path already empties CHARGED_PROJECTILES; clear it
            // again defensively so the state machine and item agree on uncharged.
            ItemStack fired = this.mob.getItemInHand(
                ProjectileUtil.getWeaponHoldingHand(this.mob, Items.CROSSBOW));
            fired.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
            this.crossbowState = CrossbowState.UNCHARGED;
        }
    }

    private boolean canRun() {
        return this.crossbowState == CrossbowState.UNCHARGED;
    }

    private enum CrossbowState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        READY_TO_ATTACK
    }
}
