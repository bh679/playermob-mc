package games.brennan.playermob.mixin;

import games.brennan.playermob.player.PlayerLifeRecord;
import games.brennan.playermob.player.PlayerLifeStore;
import net.minecraft.server.level.ServerPlayer;
//? if >=26 {
/*import net.minecraft.world.entity.animal.equine.AbstractHorse;
*///?} else {
import net.minecraft.world.entity.animal.horse.AbstractHorse;
//?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The horse half of {@link AnimalFeedMixin}: horses, donkeys, mules, llamas and camels eat
 * through {@link AbstractHorse#handleEating} rather than {@code Animal#usePlayerItem}. It returns
 * {@code true} only when the food did something (healed, grew, tempered, bred), so only that
 * credits {@link PlayerLifeRecord.Signal#FEED}.
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseFeedMixin {

    @Inject(method = "handleEating", at = @At("RETURN"))
    private void playermob$creditFeed(Player player, ItemStack stack,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && player instanceof ServerPlayer serverPlayer) {
            PlayerLifeStore.record(serverPlayer, PlayerLifeRecord.Signal.FEED, 1.0F);
        }
    }
}
