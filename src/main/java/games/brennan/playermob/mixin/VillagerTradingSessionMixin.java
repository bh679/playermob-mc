package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ShoppingHooks;
import net.minecraft.server.level.ServerPlayer;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times how long a player keeps a trader's screen open — the echo's {@code ShoppingSeconds}, how
 * long it lingers at a trader (see {@code ShoppingTally}, {@link ShoppingHooks#onVisitEnd}).
 *
 * <p>{@code setTradingPlayer} is where every screen opens (the player) and closes ({@code null}),
 * on villagers and wandering traders alike — {@code Villager} overrides it but calls super first.
 * Injected at HEAD so the outgoing trading player is still readable. The open time lives on the
 * merchant itself, transient and never saved.</p>
 */
@Mixin(AbstractVillager.class)
public abstract class VillagerTradingSessionMixin {

    @Unique
    private long playermob$visitStart = -1L;

    @Inject(method = "setTradingPlayer", at = @At("HEAD"))
    private void playermob$timeVisit(Player next, CallbackInfo ci) {
        AbstractVillager self = (AbstractVillager) (Object) this;
        Player current = self.getTradingPlayer();
        if (current == next) return;
        long now = self.level().getGameTime();
        if (current instanceof ServerPlayer leaving && playermob$visitStart >= 0L) {
            ShoppingHooks.onVisitEnd(leaving, now - playermob$visitStart);
        }
        playermob$visitStart = next instanceof ServerPlayer ? now : -1L;
    }
}
