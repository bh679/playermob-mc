package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.NaturalSpawnReplacer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.Villager;
*///?} else {
import net.minecraft.world.entity.npc.Villager;
//?}
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two world-generation hooks on {@link WorldGenRegion#addFreshEntity}, the path structure/chunk-gen
 * entities are added through:
 *
 * <ol>
 *   <li><b>Drop removed entities</b> — lets {@link Entity#discard() discarded} entities be suppressed
 *       during worldgen, so the natural-spawn replacement ({@link NaturalSpawnReplacerMixin}) works at
 *       {@code CHUNK_GENERATION}. On the live server a removed entity is dropped by
 *       {@code ServerLevel.addEntity}; the worldgen path instead serialises the entity straight into the
 *       proto-chunk with no removed-check, so without this a replaced original would be baked in as a
 *       duplicate. Narrowly scoped — entities are never already-removed here except a suppressed original,
 *       so this is otherwise a no-op.</li>
 *   <li><b>Village companions</b> — a village places its villagers through this method at generation, so
 *       each villager gets a {@link games.brennan.playermob.PlayerMobConfig#villageCompanionChance} roll
 *       (when natural spawning is on) to <em>also</em> spawn a PlayerMob beside it. Additive: the villager
 *       is added as normal; the PlayerMob is an extra. See
 *       {@link NaturalSpawnReplacer#maybeSpawnVillageCompanion}.</li>
 * </ol>
 *
 * <p>Shared (common) mixin — applies on all three loaders. Adding the companion re-enters
 * {@code addFreshEntity}, but a PlayerMob is neither removed nor a villager, so it just gets added.</p>
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void playermob$worldGenAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity.isRemoved()) {
            cir.setReturnValue(false);
            return;
        }
        if (entity instanceof Villager) {
            NaturalSpawnReplacer.maybeSpawnVillageCompanion((ServerLevelAccessor) (Object) this, entity);
        }
    }
}
