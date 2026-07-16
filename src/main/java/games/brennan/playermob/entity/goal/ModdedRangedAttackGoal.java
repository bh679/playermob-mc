package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.DispositionResolver;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Draw-strafe-and-fire combat goal for a <b>modded ranged weapon</b> (a MusketMod-style firearm the admin
 * registered via {@code moddedRangedWeapons}). Structurally a sibling of {@link PlayerMobBowAttackGoal} — same
 * approach / line-of-sight / strafe movement — but the shot itself is delivered by {@link ModdedGunFire}, which
 * drives the modded item's own firing logic through a fake player rather than calling
 * {@link PlayerMobEntity#performRangedAttack} (which only knows vanilla bows/crossbows).
 *
 * <p>Selected per tick by {@link WeaponAwareAttackGoal} whenever the mob's main hand holds a configured modded
 * firearm it has ammo for; it never runs standalone. Between shots it waits the weapon's configured hold time
 * plus the fightFlight cadence beat ({@link DispositionResolver#rangedAttackExtraDelayTicks}) — the same
 * disposition-driven firerate the vanilla ranged goals use.</p>
 */
public final class ModdedRangedAttackGoal extends Goal {

    private final PlayerMobEntity mob;
    private final double speedModifier;
    private final float attackRadiusSqr;

    /** Ticks until the next shot; -1 means "fire as soon as in range + seen". */
    private int attackTime = -1;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;

    public ModdedRangedAttackGoal(PlayerMobEntity mob, double speedModifier, float attackRadius) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean isHoldingModdedGun() {
        return PlayerMobConfig.moddedRanged().isRangedWeapon(this.mob.getMainHandItem());
    }

    @Override
    public boolean canUse() {
        ItemStack mainhand = this.mob.getMainHandItem();
        return this.mob.getTarget() != null && isHoldingModdedGun() && this.mob.hasRangedAmmo(mainhand);
    }

    @Override
    public boolean canContinueToUse() {
        return (this.canUse() || !this.mob.getNavigation().isDone())
            && isHoldingModdedGun()
            && this.mob.hasRangedAmmo(this.mob.getMainHandItem());
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
        this.strafingTime = -1;
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

        // Fire on cadence, only within range and with line of sight.
        if (--this.attackTime <= 0) {
            boolean inRange = distSqr <= (double) this.attackRadiusSqr;
            if (canSee && inRange && this.mob.hasRangedAmmo(this.mob.getMainHandItem())) {
                ModdedGunFire.fireOnce(this.mob, target);
                int hold = PlayerMobConfig.moddedRanged().holdTicks(this.mob.getMainHandItem());
                this.attackTime = hold + DispositionResolver.rangedAttackExtraDelayTicks(this.mob.fightFlight());
            } else {
                this.attackTime = 0; // keep re-checking each tick until we can fire
            }
        }
    }
}
