package games.brennan.playermob.mixin;

import games.brennan.playermob.compat.PetSnapshots;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Keeps an echo's returned pets on their feet.
 *
 * <p>Vanilla's {@link SitWhenOrderedToGoal#canUse()} sits a tamed animal down whenever its owner
 * cannot be found — "wait here until they come back". On 1.21.x {@code TamableAnimal.getOwner()}
 * resolves the owner UUID through the <em>player</em> list only, so a pet tamed to a PlayerMob has
 * no findable owner and sits down one tick after it spawns, even though nothing ever ordered it to.
 * Worse, it thrashes: {@code canContinueToUse()} reads the (unset) sit order and stops the goal
 * again the very next tick, so the pose flickers on and off forever.
 *
 * <p>So a pet that came back with an echo would sit at its feet like scenery, and no one could get
 * it up — the sit order can only be given by an owner the world can resolve.
 *
 * <p>This cancels the goal for exactly that case: an animal whose owner UUID belongs to a live
 * PlayerMob, that was never actually ordered to sit. A real sit order still wins, and an ordinary
 * pet waiting on an offline player still sits, because its owner is not a PlayerMob.
 * {@code ServerLevel.getEntity(UUID)} is a map lookup, so the check is cheap enough for a goal poll.
 */
@Mixin(SitWhenOrderedToGoal.class)
public abstract class SitWhenOrderedToGoalMixin {

    @Shadow @Final private TamableAnimal mob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void playermob$dontSitForAnEchoOwner(CallbackInfoReturnable<Boolean> cir) {
        if (mob.isOrderedToSit()) {
            return; // actually told to sit — that still stands (so to speak)
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        UUID owner = PetSnapshots.ownerUuid(mob);
        if (owner != null && level.getEntity(owner) instanceof PlayerMobEntity) {
            cir.setReturnValue(false);
        }
    }
}
