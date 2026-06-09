package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.ai.PlayerMobTargets;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.hoglin.HoglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Makes hoglins charge a {@link PlayerMobEntity} like a human player. Adult hoglins are
 * unconditionally hostile to players; babies don't attack — mirrored with an {@code isBaby}
 * guard.
 *
 * <p>Injected at {@code RETURN} of {@code HoglinAi.findNearestValidAttackTarget} (which reads
 * the {@code Player}-typed {@code NEAREST_VISIBLE_ATTACKABLE_PLAYER} memory). Only when vanilla
 * found no target do we offer the nearest attackable PlayerMob, validated by vanilla's
 * {@code Sensor.isEntityAttackable} (see {@link PlayerMobTargets}).</p>
 *
 * <p>Server-side brain AI. Registered in the {@code mixins} array of {@code playermob.mixins.json}.</p>
 */
@Mixin(HoglinAi.class)
public abstract class HoglinTargetsPlayerMobMixin {

    @Inject(method = "findNearestValidAttackTarget", at = @At("RETURN"), cancellable = true)
    private static void playermob$targetPlayerMobs(
            Hoglin hoglin, CallbackInfoReturnable<Optional<? extends LivingEntity>> cir) {
        if (cir.getReturnValue().isPresent() || hoglin.isBaby()) {
            return;
        }
        PlayerMobEntity target = PlayerMobTargets.nearestAttackable(hoglin, null);
        if (target != null) {
            cir.setReturnValue(Optional.of(target));
        }
    }
}
