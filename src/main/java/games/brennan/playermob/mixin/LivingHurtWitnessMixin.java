package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.WitnessedAttacks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns every <em>landed</em> hit into a witnessed-attack social event: when a
 * {@link LivingEntity} actually takes damage from a player/PlayerMob, nearby
 * {@link games.brennan.playermob.entity.PlayerMobEntity}s form a feeling toward the attacker
 * (see {@link games.brennan.playermob.entity.WitnessedAttacks}).
 *
 * <p><b>Why a hook, not a poll.</b> The previous design scanned each PlayerMob every few ticks and
 * read the victim's single {@code getLastHurtByMob} slot, which missed most hits — invulnerability
 * frames coalesce rapid blows, the victim only remembers one attacker at a time, and a fleeing
 * victim leaves scan range. Crediting at the damage event itself counts every real hit.</p>
 *
 * <p>Injected at {@code RETURN} of {@code hurt} and gated on a {@code true} return value, so it
 * fires once per hit that actually dealt damage — i-frame-absorbed swings return {@code false} and
 * are skipped. {@link WitnessedAttacks#onHurt} then returns immediately unless both the victim and
 * the source are players/PlayerMobs, so the common (non-player) damage case costs one check.</p>
 *
 * <p>Server-side behaviour only — registered in the {@code mixins} (not {@code client}) array of
 * {@code playermob.mixins.json}. {@code hurt} is the 1.21.1 Mojmap name, matching the other
 * mixins in this package.</p>
 *
 * <p><b>26.x target split.</b> In 1.21.2+/26.x the damage entry point was split: {@code hurt}
 * became a {@code final void} dispatcher on {@code Entity} and the server-authoritative,
 * boolean-returning logic moved to {@code LivingEntity.hurtServer(ServerLevel, DamageSource,
 * float)}. We retarget there on 26+ (still {@code RETURN}-gated on the boolean, so it still
 * fires once per hit that dealt damage). The hook now runs strictly server-side — exactly where
 * a witnessed-attack social event belongs — whereas the 1.21.1 {@code hurt} also ran (and
 * returned early) on the client. Behaviour is equivalent for our purposes: a landed hit credits
 * once on the server in both versions.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingHurtWitnessMixin {

    //? if >=26 {
    /*@Inject(method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("RETURN"))
    private void playermob$witnessAttack(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            WitnessedAttacks.onHurt((LivingEntity) (Object) this, source);
        }
    }
    *///?} else {
    @Inject(method = "hurt", at = @At("RETURN"))
    private void playermob$witnessAttack(DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            WitnessedAttacks.onHurt((LivingEntity) (Object) this, source);
        }
    }
    //?}
}
