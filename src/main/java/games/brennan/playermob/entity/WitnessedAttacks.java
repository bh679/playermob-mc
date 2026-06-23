package games.brennan.playermob.entity;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Dispatches the witnessed-attack social event: when attacker A lands real damage on victim V,
 * every nearby {@link PlayerMobEntity} forms a feeling toward A — admiration for violence it
 * approves of, resentment for harming someone it loves (see
 * {@link DispositionResolver#witnessedAttackDelta}).
 *
 * <p>Driven by a server-side {@code LivingEntity.hurt} hook
 * ({@link games.brennan.playermob.mixin.LivingHurtWitnessMixin}) so it fires once per hit that
 * <em>landed</em>. This replaces the old per-tick poll of the victim's single
 * {@code getLastHurtByMob} slot, which lost hits to invulnerability frames, the one-attacker slot,
 * and victims fleeing scan range — so admiration now accumulates per real hit.</p>
 */
public final class WitnessedAttacks {

    private WitnessedAttacks() {}

    /**
     * Credit nearby PlayerMob witnesses for {@code victim} having just been damaged by
     * {@code source}. No-op unless server-side and both the attacker and the victim are
     * players/PlayerMobs (the only valid parties for a witnessed attack); the attacker and the
     * victim themselves are never counted as witnesses.
     */
    public static void onHurt(LivingEntity victim, DamageSource source) {
        Level level = victim.level();
        //? if >=26 {
        /*if (level.isClientSide()) {
        *///?} else {
        if (level.isClientSide) {
        //?}
            return;
        }
        if (!(source.getEntity() instanceof LivingEntity attacker)
                || attacker == victim
                || TargetCategory.classify(attacker) != TargetCategory.PLAYERS
                || TargetCategory.classify(victim) != TargetCategory.PLAYERS) {
            return;
        }
        List<PlayerMobEntity> witnesses = level.getEntitiesOfClass(PlayerMobEntity.class,
            victim.getBoundingBox().inflate(DispositionResolver.MAX_RANGE),
            m -> m.isAlive() && m != attacker && m != victim);
        for (PlayerMobEntity witness : witnesses) {
            witness.witnessAttack(attacker, victim);
        }
    }
}
