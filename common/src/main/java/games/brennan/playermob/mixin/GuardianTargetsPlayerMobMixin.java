package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes (elder) guardians beam a {@link PlayerMobEntity} like a human player.
 *
 * <p>A guardian targets via {@code NearestAttackableTargetGoal(LivingEntity.class, …,
 * GuardianAttackSelector)} — the goal's {@code targetType} is {@code LivingEntity}, so the
 * Phase-1 {@code NearestAttackableTargetGoal} chokepoint (scoped to {@code Player.class} goals)
 * doesn't apply. The actual filter is the inner {@code GuardianAttackSelector} predicate, which
 * accepts {@code Player}/{@code Squid}/{@code Axolotl} at more than 3 blocks
 * ({@code distanceToSqr > 9.0}). We extend it to also accept a {@link PlayerMobEntity} under the
 * same minimum-distance rule. Targeted by name since the selector is a private inner class.
 * Covers elder guardians (same selector).</p>
 *
 * <p>Server-side AI. Registered in the {@code mixins} array of {@code playermob.mixins.json}.</p>
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Guardian$GuardianAttackSelector")
public abstract class GuardianTargetsPlayerMobMixin {

    @Shadow
    @Final
    private Guardian guardian;

    @Inject(method = "test(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("RETURN"), cancellable = true)
    private void playermob$alsoTargetPlayerMobs(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return; // vanilla already accepts this target
        }
        if (entity instanceof PlayerMobEntity && entity.distanceToSqr(this.guardian) > 9.0D) {
            cir.setReturnValue(true);
        }
    }
}
