package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReflexHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reaction-speed signal: a mob dying settles any first hit a player had open on it — clean when
 * that player got the kill without being hit back ({@link ReflexHooks#onDie}). {@code HEAD} of
 * {@code LivingEntity.die}, which every mob's override reaches through {@code super}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingDeathReflexMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void playermob$settleFirstHits(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        //? if >=26 {
        /*if (!self.level().isClientSide()) {
        *///?} else {
        if (!self.level().isClientSide) {
        //?}
            ReflexHooks.onDie(self, source);
        }
    }
}
