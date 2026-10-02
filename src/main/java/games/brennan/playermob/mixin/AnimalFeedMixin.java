package games.brennan.playermob.mixin;

import games.brennan.playermob.player.PlayerLifeRecord;
import games.brennan.playermob.player.PlayerLifeStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
//? if >=26 {
/*import net.minecraft.world.entity.Mob;
*///?}
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
 * <p>{@code usePlayerItem} is where vanilla spends the food, and it only reaches it once
 * the animal has accepted it, so a refused item (a sated animal, the wrong food) credits
 * nothing. The weight and its per-life cap live in {@code PlayerLifeRecord}.</p>
 *
 * <p>Minecraft 26.x moved {@code usePlayerItem} from {@code Animal} up to {@code Mob}, and an
 * injector only finds methods declared on its target — so the target follows the method. Other
 * mobs spend items through it there too (tadpoles, copper golems), hence the {@link Animal}
 * check, which keeps what counts as a feed the same on every version.</p>
 *
 * <p>Horses and their kin eat through their own path — see {@link AbstractHorseFeedMixin}.</p>
 */
//? if >=26 {
/*@Mixin(Mob.class)
*///?} else {
@Mixin(Animal.class)
//?}
public abstract class AnimalFeedMixin {

    @Inject(method = "usePlayerItem", at = @At("HEAD"))
    private void playermob$creditFeed(Player player, InteractionHand hand, ItemStack stack,
                                      CallbackInfo ci) {
        if ((Object) this instanceof Animal && player instanceof ServerPlayer serverPlayer) {
            PlayerLifeStore.record(serverPlayer, PlayerLifeRecord.Signal.FEED, 1.0F);
        }
    }
}
