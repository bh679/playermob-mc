package games.brennan.playermob.mixin;

import games.brennan.playermob.player.PlayerReincarnation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Snapshots a player's "life" the instant they die, so it can later be reincarnated
 * as a PlayerMob. Injected at the {@code HEAD} of {@link ServerPlayer#die} —
 * <em>before</em> vanilla drops the inventory — so the gear they died with (and
 * would drop) is still intact for the snapshot.
 *
 * <p>Targets {@link ServerPlayer} (the server-authoritative player), so it runs on
 * the dedicated server and the integrated server alike. Lives in the common
 * {@code mixins} array (not {@code server}) precisely so single-player — whose
 * integrated server runs on the physical client — is covered too. All real work is
 * delegated to {@link PlayerReincarnation#onDeath}, which is failure-isolated.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerDeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void playermob$snapshotReincarnation(DamageSource source, CallbackInfo ci) {
        PlayerReincarnation.onDeath((ServerPlayer) (Object) this);
    }
}
