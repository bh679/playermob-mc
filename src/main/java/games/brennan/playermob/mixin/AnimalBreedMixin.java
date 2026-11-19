package games.brennan.playermob.mixin;

import games.brennan.playermob.player.PlayerLifeStore;
import games.brennan.playermob.player.TamingTally;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Credits one breeding to the player whose food put this pair in love, for the echo's Taming
 * hobby (see {@link TamingTally}).
 *
 * <p>{@code finalizeSpawnChildFromBreeding} is where vanilla awards the {@code ANIMALS_BRED} stat
 * and advancement, and every breeding path — horses' own {@code spawnChildFromBreeding} override
 * included — funnels through it, so it counts each baby exactly once. The love cause is whoever fed
 * {@code this} parent, falling back to the partner's, exactly as vanilla resolves it. A pair put in
 * love by an echo has no love cause, so an echo's breeding credits nobody.</p>
 */
@Mixin(Animal.class)
public abstract class AnimalBreedMixin {

    @Inject(method = "finalizeSpawnChildFromBreeding", at = @At("HEAD"))
    private void playermob$creditBreed(ServerLevel level, Animal partner, AgeableMob baby,
                                       CallbackInfo ci) {
        ServerPlayer cause = ((Animal) (Object) this).getLoveCause();
        if (cause == null && partner != null) {
            cause = partner.getLoveCause();
        }
        if (cause != null) {
            PlayerLifeStore.recordTaming(cause, TamingTally::withBreed);
        }
    }
}
