package games.brennan.playermob.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets {@link Entity#discard() discarded} entities be suppressed during world generation, so the
 * natural-spawn replacement ({@link NaturalSpawnReplacerMixin}) works at {@code CHUNK_GENERATION} too.
 *
 * <p><b>Why it's needed.</b> On the live server a removed entity is dropped by
 * {@code ServerLevel.addEntity} (it checks {@code isRemoved}). The chunk-generation path is different:
 * {@code NaturalSpawner.spawnMobsForChunkGeneration} routes through {@link WorldGenRegion#addFreshEntity},
 * which serialises the entity straight into the proto-chunk with no removed-check — so a PlayerMob
 * replacement that discarded the original would still see the original baked into the chunk (a duplicate).
 * This guard makes {@code WorldGenRegion} skip an already-removed entity, matching the live-level
 * behaviour the replacement relies on.</p>
 *
 * <p>Narrowly scoped: it only drops entities that are <em>already</em> removed, which never happens during
 * normal worldgen — so for every spawn except a replacement-suppressed original this is a no-op. Shared
 * (common) mixin, so it applies on all three loaders.</p>
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void playermob$dropRemovedEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity.isRemoved()) {
            cir.setReturnValue(false);
        }
    }
}
