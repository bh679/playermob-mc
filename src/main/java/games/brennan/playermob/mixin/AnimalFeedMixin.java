package games.brennan.playermob.mixin;

import games.brennan.playermob.player.PlayerLifeRecord;
import games.brennan.playermob.player.PlayerLifeStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Credits {@link PlayerLifeRecord.Signal#FEED} when a player feeds an animal by hand — breeding
 * it, growing its young, or healing a tamed wolf or cat — so a life spent tending animals
 * reincarnates a little friendlier.
 *
 * <p>{@link Animal#usePlayerItem} is where vanilla spends the food, and it only reaches it once
 * the animal has accepted it, so a refused item (a sated animal, the wrong food) credits
 * nothing. The weight and its per-life cap live in {@code PlayerLifeRecord}.</p>
 *
 * <p>Horses and their kin eat through their own path — see {@link AbstractHorseFeedMixin}.</p>
 */
@Mixin(Animal.class)
public abstract class AnimalFeedMixin {

    @Inject(method = "usePlayerItem", at = @At("HEAD"))
    private void playermob$creditFeed(Player player, InteractionHand hand, ItemStack stack,
                                      CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            PlayerLifeStore.record(serverPlayer, PlayerLifeRecord.Signal.FEED, 1.0F);
        }
    }
}
