package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.DispositionResolver;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Reload-strafe-and-fire combat goal for a <b>modded ranged weapon</b> (a MusketMod-style firearm the admin
 * registered via {@code moddedRangedWeapons}). Structurally a sibling of {@link PlayerMobBowAttackGoal} — same
 * approach / line-of-sight / strafe movement — but each shot is preceded by a <em>real</em> reload: the mob
 * {@code startUsingItem}s the weapon so the mod's own {@code onUseTick} runs its staged loading (with the load
 * animation + sounds) over {@code holdTicks}, then {@link ModdedGunFire} delivers the shot of the now-loaded
 * gun. This mirrors how the weapon reloads for a player — a musketeer must reload before every shot — rather
 * than firing instantly.
 *
 * <p>Selected per tick by {@link WeaponAwareAttackGoal} whenever the mob's main hand holds a configured modded
 * firearm it has ammo for; it never runs standalone. After each shot it waits the fightFlight cadence beat
 * ({@link DispositionResolver#rangedAttackExtraDelayTicks}) before starting the next reload.</p>
 */
public final class ModdedRangedAttackGoal extends Goal {

    private final PlayerMobEntity mob;
    private final double speedModifier;
    private final float attackRadiusSqr;

    /** True while the mob is mid-reload (using the item); the shot fires when {@link #reloadTicksLeft} hits 0. */
    private boolean reloading;
    /** Ticks left in the current reload. */
    private int reloadTicksLeft;
    /** Post-shot cadence delay before the next reload may begin. */
    private int postShotCooldown;
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
        return this.mob.getTarget() != null && isHoldingModdedGun()
            && this.mob.hasRangedAmmo(this.mob.getMainHandItem());
    }

    @Override
    public boolean canContinueToUse() {
        if (!isHoldingModdedGun()) return false;
        // Always finish an in-progress reload+shot, even if it consumed the last round.
        if (this.reloading) return true;
        return (this.canUse() || !this.mob.getNavigation().isDone())
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
        if (this.mob.isUsingItem()) {
            this.mob.stopUsingItem(); // cancel a half-done reload when combat ends
        }
        this.reloading = false;
        this.reloadTicksLeft = 0;
        this.postShotCooldown = 0;
        this.seeTime = 0;
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

        // --- reload → fire state machine ---
        boolean inRange = distSqr <= (double) this.attackRadiusSqr;

        if (this.reloading) {
            // The mob is using the weapon; the mod's onUseTick runs its staged reload (animation + sounds).
            if (!this.mob.isUsingItem()) {
                this.reloading = false; // reload interrupted (another goal stopped the use) — retry next tick
            } else if (--this.reloadTicksLeft <= 0) {
                this.mob.stopUsingItem();
                this.reloading = false;
                if (canSee && inRange) {
                    ModdedGunFire.fire(this.mob, target);
                    this.mob.consumeOneModdedAmmo(this.mob.getMainHandItem()); // one cartridge per shot
                }
                this.postShotCooldown = DispositionResolver.rangedAttackExtraDelayTicks(this.mob.fightFlight());
            }
            return;
        }

        if (this.postShotCooldown > 0) {
            this.postShotCooldown--;
            return;
        }

        // Begin a fresh reload once we can shoot: use the weapon so the mod loads it over real ticks.
        if (canSee && inRange && this.mob.hasRangedAmmo(this.mob.getMainHandItem())) {
            this.mob.startUsingItem(InteractionHand.MAIN_HAND);
            this.reloading = true;
            this.reloadTicksLeft = PlayerMobConfig.moddedRanged().holdTicks(this.mob.getMainHandItem());
        }
    }
}
