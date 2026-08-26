package games.brennan.playermob.entity;

import games.brennan.playermob.PlayerMobRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
//? if >=26 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?} else {
import net.minecraft.world.entity.MobSpawnType;
//?}

/**
 * Creates and spawns a single {@link PlayerMobEntity} from code under a <em>command</em> spawn
 * reason — the back-end of {@code /playermob summon}. Kept out of the command class so the
 * loader-version guards for {@code EntityType.create} / {@code finalizeSpawn} live beside the
 * other spawn helper ({@link NaturalSpawnCompanion}).
 *
 * <p>Optional traits are pinned <em>before</em> {@code finalizeSpawn} (via
 * {@link PlayerMobEntity#setExplicitTraits}), so the spawn roll keeps them; a {@code null} trait is
 * rolled normally. The skin is intentionally <b>not</b> set here — the caller resolves the named
 * player's skin asynchronously and applies it once ready, so the mob first shows its rolled skin
 * and then flips to the resolved one (mirroring the client's in-flight skin behaviour).</p>
 */
public final class PlayerMobSummon {

    private PlayerMobSummon() {}

    /** A command-issued spawn — never {@code EVENT}, so it neither reincarnates nor spawns a friend pair. */
    //? if >=26 {
    /*private static final EntitySpawnReason SUMMON_REASON = EntitySpawnReason.COMMAND;
    *///?} else {
    private static final MobSpawnType SUMMON_REASON = MobSpawnType.COMMAND;
    //?}

    /**
     * Spawn a PlayerMob at {@code (x, y, z)} facing {@code yRot}, with optional pinned traits.
     * Returns the spawned mob, or {@code null} if the entity could not be created.
     */
    public static PlayerMobEntity summon(ServerLevel level, double x, double y, double z, float yRot,
                                         Integer fightFlight, Integer friendliness, Integer reactionSpeed) {
        PlayerMobEntity mob = create(level);
        if (mob == null) {
            return null;
        }
        //? if >=26 {
        /*mob.snapTo(x, y, z, yRot, 0.0F);
        *///?} else {
        mob.moveTo(x, y, z, yRot, 0.0F);
        //?}
        mob.setExplicitTraits(fightFlight, friendliness, reactionSpeed);
        // The caller applies the real skin AFTER finalizeSpawn (a local file synchronously, a player name
        // asynchronously), so defer auto-naming to it — naming here would label the mob off the rolled skin.
        mob.setDeferAutoName(true);
        DifficultyInstance difficulty = level.getCurrentDifficultyAt(BlockPos.containing(x, y, z));
        //? if >=1.21.1 {
        mob.finalizeSpawn(level, difficulty, SUMMON_REASON, null);
        //?} else {
        /*mob.finalizeSpawn(level, difficulty, SUMMON_REASON, null, null);*///?}
        level.addFreshEntity(mob);
        return mob;
    }

    private static PlayerMobEntity create(ServerLevel level) {
        //? if >=26 {
        /*return PlayerMobRegistry.PLAYER_MOB.create(level, SUMMON_REASON);
        *///?} else {
        return PlayerMobRegistry.PLAYER_MOB.create(level);
        //?}
    }
}
