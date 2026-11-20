package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReadingHooks;
import games.brennan.playermob.player.ReflexHooks;
import games.brennan.playermob.player.ReflexTracker.ActionKind;
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
 * action — counts once ({@link ReflexHooks#onAction}); a held button's auto-repeat swings and
 * uses are filtered out by the tracker. Injected at {@code RETURN}: a packet
 * handler first runs on the network thread and throws to re-queue itself onto the server
 * thread, so {@code RETURN} is reached exactly once, on the server thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketReflexMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleAnimate", at = @At("RETURN"))
    private void playermob$countSwing(CallbackInfo ci) {
        ReflexHooks.onAction(player, ActionKind.SWING);
    }

    /**
     * A use in the air — the only packet that opens a held book's screen. Counted like any use, then
     * offered to the Reading signal as a possible book open (after, so the count closes any book that
     * was already open first).
     */
    @Inject(method = "handleUseItem", at = @At("RETURN"))
    private void playermob$countUseItem(CallbackInfo ci) {
        ReflexHooks.onAction(player, ActionKind.USE);
        ReadingHooks.onUseItem(player);
    }

    /** A use on a block — a chest, a door, a lectern — never opens the book in hand. */
    @Inject(method = "handleUseItemOn", at = @At("RETURN"))
    private void playermob$countUseOn(CallbackInfo ci) {
        ReflexHooks.onAction(player, ActionKind.USE);
    }

    @Inject(method = {"handleSetCarriedItem", "handlePlayerCommand", "handlePlayerAction"},
        at = @At("RETURN"))
    private void playermob$countOther(CallbackInfo ci) {
        ReflexHooks.onAction(player, ActionKind.OTHER);
    }
}
