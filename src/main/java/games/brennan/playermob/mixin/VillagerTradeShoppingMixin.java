package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.ShoppingPolicy;
import games.brennan.playermob.player.PlayerLifeStore;
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
 * Credits the trading player's current life with one villager trade — the evidence behind the
 * echo's {@code Shopping} score (see {@code ShoppingTally}).
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
        try {
            if (((AbstractVillager) (Object) this).getTradingPlayer() instanceof ServerPlayer player
                    && !player.isCreative() && !player.isSpectator()) {
                int spent = ShoppingPolicy.emeraldValue(offer.getCostA())
                    + ShoppingPolicy.emeraldValue(offer.getCostB());
                int earned = ShoppingPolicy.emeraldValue(offer.getResult());
                PlayerLifeStore.recordTrade(player, spent, earned);
            }
        } catch (RuntimeException e) {
            // Never break a trade over a stat.
            com.mojang.logging.LogUtils.getLogger().warn("[playermob] Failed to read a trade for shopping", e);
        }
    }
}
