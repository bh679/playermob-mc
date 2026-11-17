package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReflexHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reaction-speed signal: actions per second. Each deliberate input the server receives — a
 * swing, a hotbar swap, a sneak/sprint toggle, an item use, a use on a block, a dig/drop/swap
 * action — counts once ({@link ReflexHooks#onAction}). Injected at {@code RETURN}: a packet
 * handler first runs on the network thread and throws to re-queue itself onto the server
 * thread, so {@code RETURN} is reached exactly once, on the server thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketReflexMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = {"handleAnimate", "handleSetCarriedItem", "handlePlayerCommand",
        "handleUseItem", "handleUseItemOn", "handlePlayerAction"}, at = @At("RETURN"))
    private void playermob$countAction(CallbackInfo ci) {
        ReflexHooks.onAction(player);
    }
}
