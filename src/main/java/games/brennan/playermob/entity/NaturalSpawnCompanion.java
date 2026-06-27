package games.brennan.playermob.entity;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.PlayerMobRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;
//? if >=26 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?} else {
import net.minecraft.world.entity.MobSpawnType;
//?}

/**
 * Spawns a PlayerMob <em>alongside</em> a naturally-spawning mob, per the opt-in natural-spawn config
 * ({@link PlayerMobConfig#naturalSpawnScale}). The mob is never replaced — a hit just adds an extra
 * PlayerMob next to it. Pure decision + a small world mutation, kept out of the mixins so the roll is
 * unit-testable and the loader-version guards live in one place.
 *
 * <p>Driven from two mixins: {@link games.brennan.playermob.mixin.NaturalSpawnCompanionMixin} hooks
 * {@code Mob.finalizeSpawn} for natural / spawner / chunk-generation spawns, and
 * {@link games.brennan.playermob.mixin.WorldGenRegionMixin} catches village villagers + iron golems
 * (which are placed at worldgen and skip {@code finalizeSpawn}).</p>
 *
 * <p>All level access (random + add) goes through the {@link ServerLevelAccessor} the spawn came in on:
 * the live {@code ServerLevel} for natural/spawner spawns, or the worldgen region for chunk-generation /
 * village placement. Using its random (not the level's) keeps the draw on the correct thread, so the
 * off-thread worldgen path is safe. The companion PlayerMob runs its own {@code finalizeSpawn} with a
 * {@code NATURAL} reason — never {@code EVENT}, so it just rolls a normal mob's skin/traits and neither
 * reincarnates nor spawns a Dungeon-Train friend pair.</p>
 */
public final class NaturalSpawnCompanion {

    private NaturalSpawnCompanion() {}

    /**
     * Whether a mob with id {@code typeId} (e.g. {@code "minecraft:zombie"}) should get a PlayerMob spawned
     * beside it — {@code true} iff its configured chance is positive and the roll lands under it. With
     * natural spawning off the chance is always {@code 0}, so this is always {@code false}.
     */
    public static boolean shouldSpawn(String typeId, RandomSource rng) {
        float chance = PlayerMobConfig.naturalSpawnScale(typeId);
        if (chance <= 0.0F) {
            return false;       // short-circuit BEFORE drawing — disabled/zero mobs never touch the world RNG
        }
        return rolls(chance, rng.nextFloat());
    }

    /** Pure roll test (chance already known positive): a uniform {@code roll} in [0,1) lands a companion. */
    static boolean rolls(float chance, float roll) {
        return roll < chance;
    }

    /**
     * Spawn a PlayerMob at {@code origin}'s exact position/rotation and add it via {@code world}. The
     * {@code origin} mob is untouched — this is purely additive. Called from the {@code finalizeSpawn} hook
     * once {@link #shouldSpawn} has passed.
     */
    public static void spawnAlongside(Mob origin, ServerLevelAccessor world, DifficultyInstance difficulty,
                                      //? if >=26 {
                                      /*EntitySpawnReason reason
                                      *///?} else {
                                      MobSpawnType reason
                                      //?}
                                      ) {
        materialise(world, difficulty, reason, origin.getX(), origin.getY(), origin.getZ(),
            origin.getYRot(), origin.getXRot());
    }

    /** The spawn reason the companion PlayerMob is finalized under (a normal, non-event roll). */
    //? if >=26 {
    /*private static final EntitySpawnReason COMPANION_REASON = EntitySpawnReason.NATURAL;
    *///?} else {
    private static final MobSpawnType COMPANION_REASON = MobSpawnType.NATURAL;
    //?}

    /**
     * For a mob placed at worldgen that skips {@code finalizeSpawn} (a village villager / iron golem):
     * roll its own configured chance and, on a hit, spawn a PlayerMob beside it. Runs on the worldgen
     * worker thread; like {@link #spawnAlongside} it draws from {@code world}'s (the region's) random, so
     * it stays thread-safe.
     */
    public static void maybeSpawnAlongsideAtWorldgen(ServerLevelAccessor world, Entity origin) {
        String typeId = EntityType.getKey(origin.getType()).toString();
        if (!shouldSpawn(typeId, world.getRandom())) {
            return;
        }
        DifficultyInstance difficulty = world.getLevel().getCurrentDifficultyAt(origin.blockPosition());
        materialise(world, difficulty, COMPANION_REASON, origin.getX(), origin.getY(), origin.getZ(),
            origin.getYRot(), origin.getXRot());
    }

    /**
     * Create a PlayerMob at the given pose, run its normal spawn rolls under {@code reason} via {@code world}
     * (the spawn accessor — thread-correct random), and add it. {@code true} once it's added; {@code false}
     * if the entity couldn't be created.
     */
    private static boolean materialise(ServerLevelAccessor world, DifficultyInstance difficulty,
                                       //? if >=26 {
                                       /*EntitySpawnReason reason,
                                       *///?} else {
                                       MobSpawnType reason,
                                       //?}
                                       double x, double y, double z, float yRot, float xRot) {
        PlayerMobEntity mob = create(world.getLevel()
            //? if >=26 {
            /*, reason*///?}
            );
        if (mob == null) {
            return false;
        }
        //? if >=26 {
        /*mob.snapTo(x, y, z, yRot, xRot);
        *///?} else {
        mob.moveTo(x, y, z, yRot, xRot);
        //?}
        //? if >=1.21.1 {
        mob.finalizeSpawn(world, difficulty, reason, null);
        //?} else {
        /*mob.finalizeSpawn(world, difficulty, reason, null, null);*///?}
        maybeAutoName(mob);
        world.addFreshEntity(mob);
        return true;
    }

    /**
     * If {@link PlayerMobConfig#autoNameNaturalSpawns()} is on and {@code mob} isn't already named, give it a
     * nameplate drawn from its skin source — the local-folder filename or the online-skin {@code displayName}
     * (see {@link games.brennan.playermob.skin.SkinDisplayName}). Runs after {@code finalizeSpawn} has rolled
     * the skin, so the {@code SkinTextureUrl} is set. A bundled-skin mob has no source name and stays unnamed.
     */
    private static void maybeAutoName(PlayerMobEntity mob) {
        if (!PlayerMobConfig.autoNameNaturalSpawns() || mob.hasCustomName()) {
            return;
        }
        games.brennan.playermob.skin.SkinDisplayName.resolve(mob.getSkinTextureUrl())
            .ifPresent(name -> {
                mob.setCustomName(net.minecraft.network.chat.Component.literal(name));
                mob.setCustomNameVisible(true);
            });
    }

    /**
     * Build a fresh {@code PlayerMobEntity} for {@code level}. MC 26.x replaced
     * {@code EntityType.create(Level)} with {@code create(Level, EntitySpawnReason)}; pass the companion
     * spawn reason through so it's created with the same provenance it finalizes under.
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
