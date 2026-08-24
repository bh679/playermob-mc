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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The horse half of {@link TamableAnimalTameMixin}: horses, donkeys, mules, llamas and camels
 * descend from {@code AbstractHorse}, not {@code TamableAnimal}, and are tamed through
 * {@link AbstractHorse#tameWithName}. Credits the same
 * {@link PlayerLifeRecord.Signal#TAME} kindness so a life spent gentling horses reincarnates as
 * welcoming as one spent on wolves.
 *
 * <p>MC 26.x moved {@code AbstractHorse} from {@code entity.animal.horse} to
 * {@code entity.animal.equine}; the import is version-bridged above. The method itself is
 * unchanged across every supported version.</p>
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseTameMixin {

    @Inject(method = "tameWithName", at = @At("TAIL"))
    private void playermob$creditTame(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayer serverPlayer) {
            PlayerLifeStore.record(serverPlayer, PlayerLifeRecord.Signal.TAME, 0);
        }
    }
}
