package games.brennan.playermob.mixin;

import games.brennan.playermob.player.ShoppingHooks;
import net.minecraft.server.level.ServerPlayer;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Credits the trading player's current life with one trade with this villager — the hits in the
 * echo's {@code Shopping} rate (see {@code ShoppingTally}, {@link ShoppingHooks}).
 *
 * <p>{@code notifyTrade} runs once per trade a player takes from the merchant screen (shift-click
 * included — once per result), on villagers and wandering traders alike, on every loader. It is
 * the same point NeoForge posts {@code TradeWithVillagerEvent} from, but hooking it here keeps the
 * stat in common code. Injected at HEAD so the offer's price still reads as what was paid. Echo
 * trades never come through here ({@code VillagerTrading} bypasses it), and only a real survival
 * player counts.</p>
 */
@Mixin(AbstractVillager.class)
public abstract class VillagerTradeShoppingMixin {

    @Inject(method = "notifyTrade", at = @At("HEAD"))
    private void playermob$creditShopping(MerchantOffer offer, CallbackInfo ci) {
        AbstractVillager self = (AbstractVillager) (Object) this;
        if (self.getTradingPlayer() instanceof ServerPlayer player) {
            ShoppingHooks.onTrade(player, self, offer);
        }
    }
}
