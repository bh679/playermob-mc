package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReflexHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reaction-speed signal: a jump is an action. The server calls {@code jumpFromGround} on the
 * player when a movement packet shows them leaving the ground upward; the hook ignores anyone
 * but a survival {@code ServerPlayer} ({@link ReflexHooks#onAction}).
 */
@Mixin(Player.class)
public abstract class PlayerJumpReflexMixin {

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void playermob$countJump(CallbackInfo ci) {
        ReflexHooks.onAction((Player) (Object) this);
    }
}
