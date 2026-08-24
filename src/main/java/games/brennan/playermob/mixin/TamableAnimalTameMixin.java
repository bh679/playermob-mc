package games.brennan.playermob.mixin;

import games.brennan.playermob.player.PlayerLifeRecord;
import games.brennan.playermob.player.PlayerLifeStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Credits {@link PlayerLifeRecord.Signal#TAME} when a player tames a wolf, cat, parrot or any
 * other {@link TamableAnimal} — the kindest single act a life can record, and so the heaviest
 * push toward a friendly reincarnation.
 *
 * <p>Injected at the {@code TAIL} of {@link TamableAnimal#tame}, so the credit lands only once
 * the taming has actually happened. Vanilla calls {@code tame} exactly once per animal (the
 * already-tamed paths never reach it), so a life cannot farm kindness off one wolf.</p>
 *
 * <p>Horses and their kin are <em>not</em> {@link TamableAnimal}s — they take the parallel
 * {@link AbstractHorseTameMixin} path.</p>
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalTameMixin {

    @Inject(method = "tame", at = @At("TAIL"))
    private void playermob$creditTame(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            PlayerLifeStore.record(serverPlayer, PlayerLifeRecord.Signal.TAME, 0);
        }
    }
}
