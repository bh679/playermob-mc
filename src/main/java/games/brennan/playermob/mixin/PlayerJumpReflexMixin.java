package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReflexHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reaction-speed signal: a jump is an action. The server calls {@code jumpFromGround} on the
 * player when a movement packet shows them leaving the ground upward; the hook ignores anyone
 * but a survival {@code ServerPlayer} ({@link ReflexHooks#onAction}).
 *
 * <p>Targets {@link LivingEntity}, which declares {@code jumpFromGround} on every supported
 * version — 26.x's {@code Player} no longer overrides it. Mobs jump through here too; they cost
 * one {@code instanceof}.</p>
 */
@Mixin(LivingEntity.class)
public abstract class PlayerJumpReflexMixin {

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void playermob$countJump(CallbackInfo ci) {
        if ((Object) this instanceof Player player) {
            ReflexHooks.onAction(player);
        }
    }
}
