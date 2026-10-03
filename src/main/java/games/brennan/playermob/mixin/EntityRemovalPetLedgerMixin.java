package games.brennan.playermob.mixin;

import games.brennan.playermob.player.PlayerLifeStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps each life's {@link games.brennan.playermob.player.PetLedger} current: whenever an ownable
 * animal leaves the world — its chunk unloads, it changes dimension, or it dies — its remembered
 * snapshot is refreshed, so an echo returns with the pet as it was last seen rather than as it was
 * the moment it was tamed (unnamed puppy vs. the named, collared wolf it grew into).
 *
 * <p>Hooked at the {@code HEAD} of {@link Entity#setRemoved}, the one method every removal path
 * funnels through, while the entity's data is still intact. {@code KILLED} and {@code DISCARDED}
 * mark the pet dead; it stays in the ledger and can still return. Every entity removal passes
 * here, so the first check is the cheap {@code OwnableEntity} test.</p>
 */
@Mixin(Entity.class)
public abstract class EntityRemovalPetLedgerMixin {

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void playermob$refreshRememberedPet(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof OwnableEntity) || !(self.level() instanceof ServerLevel level)) {
            return;
        }
        boolean died = reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED;
        PlayerLifeStore.onPetRemoved(level, self, died);
    }
}
