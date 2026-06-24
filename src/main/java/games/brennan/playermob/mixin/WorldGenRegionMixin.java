package games.brennan.playermob.mixin;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.NaturalSpawnCompanion;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives village villagers + iron golems a chance at a PlayerMob companion. They're placed by village
 * structures at world generation and added through {@link WorldGenRegion#addFreshEntity}, which
 * <em>skips</em> {@code finalizeSpawn} — so {@link NaturalSpawnCompanionMixin} never sees them, and they
 * need this hook instead.
 *
 * <p>On each added entity whose type id is in the {@link PlayerMobConfig.SpawnGroup#VILLAGER VILLAGER}
 * group, roll that mob's configured chance and (on a hit) spawn a PlayerMob beside it — additive, the
 * original is added as normal. Matching by type-id group membership (rather than {@code instanceof
 * Villager}) avoids the 26.x {@code Villager}-package split and keeps villager + iron_golem in one rule.</p>
 *
 * <p>No double-fire: VILLAGER mobs don't go through {@code finalizeSpawn}, and other worldgen mobs aren't
 * in the VILLAGER group. The companion PlayerMob re-enters {@code addFreshEntity}, but it's not a VILLAGER
 * group mob, so it just gets added. Shared (common) mixin — applies on all three loaders.</p>
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void playermob$maybeVillageCompanion(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        String typeId = EntityType.getKey(entity.getType()).toString();
        if (PlayerMobConfig.groupOf(typeId) == PlayerMobConfig.SpawnGroup.VILLAGER) {
            NaturalSpawnCompanion.maybeSpawnAlongsideAtWorldgen((ServerLevelAccessor) (Object) this, entity);
        }
    }
}
