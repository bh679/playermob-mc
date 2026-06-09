package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.mixin.support.PlayerMobTargets;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinBruteAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Makes piglin brutes hunt a {@link PlayerMobEntity} like a human player. Brutes are
 * <em>unconditionally</em> hostile to players (no gold-armor exemption), so this is the same
 * hook as {@link PiglinTargetsPlayerMobMixin} minus the gold filter.
 *
 * <p>Injected at {@code RETURN} of {@code PiglinBruteAi.findNearestValidAttackTarget}; only
 * when vanilla found no target do we offer the nearest attackable PlayerMob (a real
 * anger / nemesis / player target keeps priority). Validity via vanilla's
 * {@code Sensor.isEntityAttackable} (see {@link PlayerMobTargets}).</p>
 *
 * <p>Server-side brain AI. Registered in the {@code mixins} array of {@code playermob.mixins.json}.</p>
 */
@Mixin(PiglinBruteAi.class)
public abstract class PiglinBruteTargetsPlayerMobMixin {

    @Inject(method = "findNearestValidAttackTarget", at = @At("RETURN"), cancellable = true)
    private static void playermob$targetPlayerMobs(
            AbstractPiglin brute, CallbackInfoReturnable<Optional<? extends LivingEntity>> cir) {
        if (cir.getReturnValue().isPresent() || brute.isBaby()) {
            return;
        }
        PlayerMobEntity target = PlayerMobTargets.nearestAttackable(brute, null);
        if (target != null) {
            cir.setReturnValue(Optional.of(target));
        }
    }
}
