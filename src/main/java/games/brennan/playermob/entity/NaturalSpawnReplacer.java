package games.brennan.playermob.entity;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.PlayerMobRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
//? if >=26 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?} else {
import net.minecraft.world.entity.MobSpawnType;
//?}

/**
 * Turns an about-to-spawn vanilla mob into a PlayerMob, per the opt-in natural-spawn config
 * ({@link PlayerMobConfig#naturalSpawnScale}). Pure decision + a small world mutation, kept out
 * of the mixin so the roll is unit-testable and the loader-version guards live in one place.
 *
 * <p>Driven from {@link games.brennan.playermob.mixin.NaturalSpawnReplacerMixin}, which hooks the
 * HEAD of {@code Mob.finalizeSpawn} on natural spawns: {@link #shouldReplace} rolls the mob's
 * configured chance and, on a hit, {@link #replace} materialises a PlayerMob on the original's tile
 * and {@link Mob#discard() discards} the original so only the PlayerMob lands.</p>
 *
 * <p>Server-thread only (finalizeSpawn runs there). The replacement PlayerMob runs its own
 * {@code finalizeSpawn} with the original spawn reason — never {@code EVENT}, so it just rolls a
 * normal mob's skin/traits and neither reincarnates nor spawns a Dungeon-Train friend pair.</p>
 */
public final class NaturalSpawnReplacer {

    private NaturalSpawnReplacer() {}

    /**
     * Whether a mob with id {@code typeId} (e.g. {@code "minecraft:zombie"}) should be replaced by a
     * PlayerMob on this spawn — {@code true} iff its configured chance is positive and the roll lands
     * under it. With natural spawning off the chance is always {@code 0}, so this is always {@code false}.
     */
    public static boolean shouldReplace(String typeId, RandomSource rng) {
        float chance = PlayerMobConfig.naturalSpawnScale(typeId);
        if (chance <= 0.0F) {
            return false;       // short-circuit BEFORE drawing — disabled/zero mobs never touch the world RNG
        }
        return rolls(chance, rng.nextFloat());
    }

    /** Pure roll test (chance already known positive): a uniform {@code roll} in [0,1) lands a replacement. */
    static boolean rolls(float chance, float roll) {
        return roll < chance;
    }

    /**
     * Spawn a PlayerMob on {@code original}'s exact position/rotation, run its normal spawn rolls with
     * {@code reason}, add it to {@code level}, then discard {@code original}. The discard marks the
     * original removed before the natural spawner adds it, so {@code ServerLevel.addEntity} drops it and
     * only the PlayerMob remains.
     */
    public static void replace(Mob original, ServerLevel level, DifficultyInstance difficulty,
                               //? if >=26 {
                               /*EntitySpawnReason reason
                               *///?} else {
                               MobSpawnType reason
                               //?}
                               ) {
        PlayerMobEntity mob = create(level
            //? if >=26 {
            /*, reason*///?}
            );
        if (mob == null) {
            return;
        }
        //? if >=26 {
        /*mob.snapTo(original.getX(), original.getY(), original.getZ(), original.getYRot(), original.getXRot());
        *///?} else {
        mob.moveTo(original.getX(), original.getY(), original.getZ(), original.getYRot(), original.getXRot());
        //?}
        //? if >=1.21.1 {
        mob.finalizeSpawn(level, difficulty, reason, null);
        //?} else {
        /*mob.finalizeSpawn(level, difficulty, reason, null, null);*///?}
        level.addFreshEntity(mob);
        original.discard();
    }

    /**
     * Build a fresh {@code PlayerMobEntity} for {@code level}. MC 26.x replaced
     * {@code EntityType.create(Level)} with {@code create(Level, EntitySpawnReason)}; pass the original
     * spawn reason through so the replacement is created with the same provenance it finalizes under.
     */
    private static PlayerMobEntity create(ServerLevel level
                                          //? if >=26 {
                                          /*, EntitySpawnReason reason*///?}
                                          ) {
        //? if >=26 {
        /*return PlayerMobRegistry.PLAYER_MOB.create(level, reason);
        *///?} else {
        return PlayerMobRegistry.PLAYER_MOB.create(level);
        //?}
    }
}
