package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.NaturalSpawnReplacer;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
//? if >=26 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?} else {
import net.minecraft.world.entity.MobSpawnType;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The opt-in natural-spawning seam: when a vanilla mob finalizes a <em>natural</em> spawn, roll the
 * mob's configured chance ({@link games.brennan.playermob.PlayerMobConfig#naturalSpawnScale}) and, on a
 * hit, swap in a PlayerMob on the same tile via {@link NaturalSpawnReplacer}.
 *
 * <p><b>Why {@code Mob.finalizeSpawn}.</b> Every mob's own {@code finalizeSpawn} override chains up to
 * {@code Mob.finalizeSpawn} via {@code super}, so a single {@code @Inject} at its {@code HEAD} sees every
 * mob type — present and modded — with no per-loader spawn-event wiring. It lives in the shared
 * {@code mixins} array, so Fabric, Forge, and NeoForge all run it.</p>
 *
 * <p><b>How the original is suppressed.</b> The natural spawner calls {@code addFreshEntity(mob)} right
 * after {@code finalizeSpawn} returns. {@link NaturalSpawnReplacer#replace} {@link Mob#discard()
 * discards} the original before that, and {@code ServerLevel.addEntity} refuses an already-removed
 * entity — so only the PlayerMob lands. (It logs one benign "marked as removed already" warning per
 * replacement.)</p>
 *
 * <p><b>Scope.</b> Guarded to the server-thread natural spawn reasons (natural / spawner) so eggs,
 * {@code /summon}, breeding, structure, chunk-generation, and Dungeon-Train spawns are untouched; and
 * skipped when {@code this} is already a PlayerMob, so the replacement's own {@code finalizeSpawn} can
 * never re-enter this hook. Chunk-generation is excluded specifically because it runs off the server
 * thread (the replacement's random is thread-confined) — a same-thread guard enforces this regardless.</p>
 */
@Mixin(Mob.class)
public abstract class NaturalSpawnReplacerMixin {

    @Inject(method = "finalizeSpawn", at = @At("HEAD"), cancellable = true)
    private void playermob$maybeReplaceWithPlayerMob(ServerLevelAccessor world,
                                                     DifficultyInstance difficulty,
                                                     //? if >=26 {
                                                     /*EntitySpawnReason reason,
                                                     *///?} else {
                                                     MobSpawnType reason,
                                                     //?}
                                                     SpawnGroupData data,
                                                     //? if <1.21.1 {
                                                     /*net.minecraft.nbt.CompoundTag dataTag,
                                                     *///?}
                                                     CallbackInfoReturnable<SpawnGroupData> cir) {
        Object self = this;
        if (self instanceof PlayerMobEntity || !isNaturalSpawn(reason)) {
            return;
        }
        // Replace only on the server thread. The replacement PlayerMob's finalizeSpawn draws from the
        // level's main-thread-confined RandomSource, so doing this off-thread (e.g. chunk-generation
        // spawning, which runs on a worker thread) trips Minecraft's threading detector and crashes the
        // server. NATURAL/SPAWNER both run on the server thread; this also guards any off-thread caller.
        ServerLevel level = world.getLevel();
        if (!level.getServer().isSameThread()) {
            return;
        }
        Mob original = (Mob) self;
        String typeId = EntityType.getKey(original.getType()).toString();
        if (NaturalSpawnReplacer.shouldReplace(typeId, world.getRandom())) {
            NaturalSpawnReplacer.replace(original, level, difficulty, reason);
            // The original is now discarded; short-circuit its own spawn rolls. The natural
            // spawner's follow-up addFreshEntity drops it (isRemoved), leaving just the PlayerMob.
            cir.setReturnValue(data);
        }
    }

    /**
     * Whether {@code reason} is a server-thread natural spawn we replace — {@code NATURAL} (the per-tick
     * spawn cycle) or {@code SPAWNER} (mob spawners). {@code CHUNK_GENERATION} is deliberately excluded:
     * it runs on a worldgen worker thread (unsafe for the replacement's random) and its add path wouldn't
     * honour the discard-suppression anyway. Centralises the spawn-reason enum, which MC 26.x renamed
     * {@code MobSpawnType} → {@code EntitySpawnReason}.
     */
    //? if >=26 {
    /*private static boolean isNaturalSpawn(EntitySpawnReason reason) {
        return reason == EntitySpawnReason.NATURAL
            || reason == EntitySpawnReason.SPAWNER;
    }
    *///?} else {
    private static boolean isNaturalSpawn(MobSpawnType reason) {
        return reason == MobSpawnType.NATURAL
            || reason == MobSpawnType.SPAWNER;
    }
    //?}
}
