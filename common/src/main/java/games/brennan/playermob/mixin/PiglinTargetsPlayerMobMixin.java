package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.mixin.support.PlayerMobTargets;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Makes piglins hunt a {@link PlayerMobEntity} exactly as they hunt a human player — including
 * the gold-armor exemption: a PlayerMob wearing any gold armour is left alone.
 *
 * <p><b>Where.</b> A piglin's target is chosen by {@code PiglinAi.findNearestValidAttackTarget},
 * which (after anger / nemesis checks) returns the nearest player from the
 * {@code NEAREST_TARGETABLE_PLAYER_NOT_WEARING_GOLD} memory. That memory is {@code Player}-typed,
 * so a PlayerMob can never enter it. We inject at the method's {@code RETURN}: only when vanilla
 * found <em>no</em> target (so a real anger / nemesis / player target always wins) do we offer
 * the nearest attackable non-gold-wearing PlayerMob.</p>
 *
 * <p><b>Faithful.</b> Validity comes from {@link PlayerMobTargets#nearestAttackable} (vanilla's
 * {@code Sensor.isEntityAttackable}) and {@link PiglinAi#isWearingGold} — the same checks
 * vanilla applies to the player. The {@code isNearZombified} guard is mirrored so a piglin near
 * a zombifying source stays passive. Admiring / baby states are already gated by the brain's
 * activities, so a returned target is only acted on when the piglin can actually fight.</p>
 *
 * <p>Server-side brain AI. Registered in the {@code mixins} array of {@code playermob.mixins.json}.</p>
 */
@Mixin(PiglinAi.class)
public abstract class PiglinTargetsPlayerMobMixin {

    @Shadow
    private static boolean isNearZombified(Piglin piglin) {
        throw new AssertionError("@Shadow stub — replaced at mixin apply time");
    }

    @Inject(method = "findNearestValidAttackTarget", at = @At("RETURN"), cancellable = true)
    private static void playermob$targetPlayerMobs(
            Piglin piglin, CallbackInfoReturnable<Optional<? extends LivingEntity>> cir) {
        if (cir.getReturnValue().isPresent() || isNearZombified(piglin) || piglin.isBaby()) {
            return;
        }
        PlayerMobEntity target = PlayerMobTargets.nearestAttackable(
                piglin, candidate -> !PiglinAi.isWearingGold(candidate));
        if (target != null) {
            cir.setReturnValue(Optional.of(target));
        }
    }
}
