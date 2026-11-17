package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReflexHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reaction-speed signal: every block a player actually places feeds speed-bridge and jump-place
 * detection ({@link ReflexHooks#onBlockPlaced}). {@code RETURN} of {@code BlockItem.place},
 * counted only when the placement consumed the action. The position is the block's own (on a
 * Sable carriage, the carriage's coordinates), which is all the detection compares.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceReflexMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void playermob$reflexPlace(BlockPlaceContext context,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getPlayer() != null && cir.getReturnValue() != null
                && cir.getReturnValue().consumesAction()) {
            BlockPos pos = context.getClickedPos();
            ReflexHooks.onBlockPlaced(context.getPlayer(), pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
