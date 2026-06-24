package games.brennan.playermob.entity;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.PlayerMobRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;
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
 * <p>The replacement PlayerMob runs its own {@code finalizeSpawn} with the original spawn reason —
 * never {@code EVENT}, so it just rolls a normal mob's skin/traits and neither reincarnates nor spawns a
 * Dungeon-Train friend pair. All level access (random + add) goes through the same {@link
 * ServerLevelAccessor} the spawn was finalized with: the live {@code ServerLevel} for NATURAL/SPAWNER, or
 * the worldgen region for chunk-generation. That keeps the random thread-correct — the region's RNG is
 * thread-confined to the worldgen worker, so reusing it (rather than the level's main-thread RNG) is what
 * makes off-thread chunk-generation replacement safe.</p>
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
     * {@code reason}, add it through {@code world}, then discard {@code original}.
     *
     * <p>{@code world} is the accessor the spawn was finalized with — the live {@code ServerLevel} for
     * NATURAL/SPAWNER, or the worldgen region for chunk-generation. Using its random (not the level's)
     * keeps the draw on the correct thread. Discarding the original before the spawner adds it means it's
     * dropped: {@code ServerLevel.addEntity} skips removed entities, and {@code WorldGenRegionMixin} does
     * the same for the worldgen path.</p>
     */
    public static void replace(Mob original, ServerLevelAccessor world, DifficultyInstance difficulty,
                               //? if >=26 {
                               /*EntitySpawnReason reason
                               *///?} else {
                               MobSpawnType reason
                               //?}
                               ) {
        PlayerMobEntity mob = create(world.getLevel()
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
        mob.finalizeSpawn(world, difficulty, reason, null);
        //?} else {
        /*mob.finalizeSpawn(world, difficulty, reason, null, null);*///?}
        world.addFreshEntity(mob);
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
