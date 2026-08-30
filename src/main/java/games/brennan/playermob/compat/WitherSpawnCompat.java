package games.brennan.playermob.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;

/**
 * Version-bridging creation of a {@link WitherBoss} for
 * {@link games.brennan.playermob.entity.goal.WitherSummonGoal}'s fallback summon — the path it takes when a
 * complete rig is standing and vanilla's own {@code WitherSkullBlock.checkSpawn} has declined to act on it.
 *
 * <p>{@code EntityType.create} gained a spawn-reason argument in 26.x:</p>
 * <ul>
 *   <li>1.20.1 / 1.21.1: {@code EntityType.WITHER.create(Level)};</li>
 *   <li>26.x: {@code EntityTypes.WITHER.create(Level, EntitySpawnReason)} — the type constants moved to
 *       {@code EntityTypes}, and the reason is {@code TRIGGERED}, the one vanilla uses for a summoned boss.</li>
 * </ul>
 *
 * <p>Placing the fresh boss differs too — {@code Entity.moveTo} became {@code snapTo} in 26.x — so
 * {@link #seat} carries that guard rather than leaking it into the goal.</p>
 *
 * <p>The summon <em>advancement</em> moved too — {@code net.minecraft.advancements.CriteriaTriggers} became
 * {@code net.minecraft.advancements.triggers.CriteriaTriggers} in 26.x (the trigger method itself is unchanged) —
 * so {@link #awardSummonCriterion} carries the same guard. Version-only types are referenced fully-qualified
 * inside their branches (mirroring {@link EndCrystalCompat} / {@link GameRuleCompat}) so neither build carries an
 * unused import.</p>
 */
public final class WitherSpawnCompat {

    private WitherSpawnCompat() {}

    /** A fresh, unplaced wither for {@code level}, or {@code null} if the entity type declines to create one. */
    public static WitherBoss create(Level level) {
        //? if >=26 {
        /*return net.minecraft.world.entity.EntityTypes.WITHER.create(
            level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        *///?} else {
        return EntityType.WITHER.create(level);
        //?}
    }

    /** Seat {@code wither} at {@code x/y/z} facing {@code yRot} — {@code moveTo} became {@code snapTo} in 26.x. */
    public static void seat(WitherBoss wither, double x, double y, double z, float yRot) {
        //? if >=26 {
        /*wither.snapTo(x, y, z, yRot, 0.0F);
        *///?} else {
        wither.moveTo(x, y, z, yRot, 0.0F);
        //?}
        wither.yBodyRot = yRot;
    }

    /**
     * Award the "summon the wither" advancement to {@code player} for {@code wither} — the same criterion vanilla
     * fires for every player within 50 blocks of a pattern-spawned boss, so a fallback summon still unlocks
     * <i>Withering Heights</i> for anyone watching.
     */
    public static void awardSummonCriterion(ServerPlayer player, WitherBoss wither) {
        //? if >=26 {
        /*net.minecraft.advancements.triggers.CriteriaTriggers.SUMMONED_ENTITY.trigger(player, wither);
        *///?} else {
        net.minecraft.advancements.CriteriaTriggers.SUMMONED_ENTITY.trigger(player, wither);
        //?}
    }
}
