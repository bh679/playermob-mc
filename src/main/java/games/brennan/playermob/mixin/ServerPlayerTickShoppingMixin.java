package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ShoppingHooks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shopping signal: once a second, note every trader near the player as met
 * ({@link ShoppingHooks#onPlayerTick}) — the misses in the echo's {@code Shopping} rate.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTickShoppingMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void playermob$shoppingTick(CallbackInfo ci) {
        ShoppingHooks.onPlayerTick((ServerPlayer) (Object) this);
    }
}
