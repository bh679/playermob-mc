package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReflexHooks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reaction-speed signal: once a second, sample the player's pace along the train and expire
 * stale reflex timers; every {@code FLUSH_TICKS}, flush the action and pace windows into the
 * live record ({@link ReflexHooks#onPlayerTick}).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTickReflexMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void playermob$reflexTick(CallbackInfo ci) {
        ReflexHooks.onPlayerTick((ServerPlayer) (Object) this);
    }
}
