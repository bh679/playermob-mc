package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ReflexHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Reaction-speed signal: dodges. A projectile owned by a mob that targets a player remembers
 * that player and its closest pass to them; when it first hits something it reports either a
 * hit on its target or, if it passed close and missed, a dodge
 * ({@link ReflexHooks#onProjectileResolved}). Projectiles not aimed at a player by a mob are
 * never tracked, so the per-tick cost is one owner check.
 */
@Mixin(Projectile.class)
public abstract class ProjectileReflexMixin {

    @Unique private UUID playermob$aimedAt;
    @Unique private boolean playermob$checkedAim;
    @Unique private boolean playermob$resolved;
    @Unique private double playermob$closest = Double.MAX_VALUE;

    @Inject(method = "tick", at = @At("TAIL"))
    private void playermob$trackPass(CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (playermob$resolved || self.level().isClientSide()) {
            return;
        }
        if (!playermob$checkedAim) {
            playermob$checkedAim = true;
            ServerPlayer target = ReflexHooks.aimedAt(self);
            playermob$aimedAt = target == null ? null : target.getUUID();
        }
        if (playermob$aimedAt == null) {
            return;
        }
        Player target = self.level().getPlayerByUUID(playermob$aimedAt);
        if (target != null) {
            playermob$closest = Math.min(playermob$closest,
                ReflexHooks.distanceTo(target, self.position()));
        }
    }

    @Inject(method = "onHit", at = @At("HEAD"))
    private void playermob$resolve(HitResult result, CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (playermob$resolved || playermob$aimedAt == null || self.level().isClientSide()) {
            return;
        }
        playermob$resolved = true;
        if (!(self.level().getPlayerByUUID(playermob$aimedAt) instanceof ServerPlayer target)) {
            return;
        }
        boolean hitTarget = result instanceof EntityHitResult hit && hit.getEntity() == target;
        double closest = hitTarget ? 0.0
            : Math.min(playermob$closest, ReflexHooks.distanceTo(target, result.getLocation()));
        ReflexHooks.onProjectileResolved(target, closest, hitTarget);
    }
}
