package games.brennan.playermob.mixin;

import games.brennan.playermob.player.GlobalLifeStore;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces any queued {@link GlobalLifeStore} write to disk whenever the server saves its worlds.
 * {@link MinecraftServer#saveAllChunks} runs on autosave, {@code /save-all}, and from
 * {@code stopServer} (dedicated shutdown and singleplayer quit alike), so the death log is never
 * behind the world save. Same signature ({@code (ZZZ)Z}) on 1.20.1, 1.21.1 and 26.2.
 *
 * <p>Common {@code mixins} array (not {@code server}) so the integrated server is covered too —
 * same reasoning as {@link PlayerDeathMixin}.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSaveMixin {

    @Inject(method = "saveAllChunks", at = @At("RETURN"))
    private void playermob$flushGlobalLives(boolean suppressLog, boolean flush, boolean forced,
                                            CallbackInfoReturnable<Boolean> cir) {
        GlobalLifeStore.flushPendingWrites();
    }
}
