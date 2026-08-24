package games.brennan.playermob.mixin;

import games.brennan.playermob.compat.PetSnapshots;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Makes a PlayerMob a real owner, so an echo's pets behave exactly as they would for a living
 * player: follow, teleport when left behind, defend it, and share its team.
 *
 * <p>Every owner-dependent behaviour in the game funnels through one method:
 *
 * <pre>{@code
 * default LivingEntity getOwner() {
 *     UUID uuid = this.getOwnerUUID();
 *     return uuid == null ? null : this.level().getPlayerByUUID(uuid);   // players only
 * }
 * }</pre>
 *
 * <p>A pet tamed to a PlayerMob therefore has no resolvable owner, and every goal written against
 * it silently does nothing — {@code FollowOwnerGoal} never follows or teleports,
 * {@code OwnerHurtByTargetGoal} never defends, {@code SitWhenOrderedToGoal} sits the animal down
 * forever because a null owner reads as "wait here until they come back". Patching those goals one
 * at a time is whack-a-mole; this fixes the single place they all ask.
 *
 * <p>Deliberately narrow: it only fires when vanilla already returned {@code null}, and only
 * resolves the UUID if it belongs to a live {@link PlayerMobEntity}. A pet owned by a real player
 * is untouched (vanilla resolved it), and a pet whose player is merely offline still gets
 * {@code null}, because a player's UUID never names a PlayerMob. Server-side only — owner
 * resolution matters for AI, which does not run on the client.
 *
 * <p>MC 26.x resolves owners through an {@code EntityReference<LivingEntity>} rather than the player
 * list, so a PlayerMob owner already resolves there and this injection simply never fires — hence
 * the guard on the returned value rather than a version-conditional mixin.
 */
@Mixin(OwnableEntity.class)
public interface OwnableEntityOwnerMixin {

    @Inject(method = "getOwner", at = @At("RETURN"), cancellable = true)
    private void playermob$resolveAPlayerMobOwner(CallbackInfoReturnable<LivingEntity> cir) {
        if (cir.getReturnValue() != null) {
            return; // vanilla found the owner — a real player, or 26.x's entity reference
        }
        if (!(this instanceof Entity self) || !(self.level() instanceof ServerLevel level)) {
            return;
        }
        UUID owner = PetSnapshots.ownerUuid(self);
        if (owner != null && level.getEntity(owner) instanceof PlayerMobEntity mob) {
            cir.setReturnValue(mob);
        }
    }
}
