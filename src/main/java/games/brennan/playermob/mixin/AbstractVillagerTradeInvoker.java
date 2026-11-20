package games.brennan.playermob.mixin;

//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@link AbstractVillager}'s protected {@code rewardTradeXp}, so an echo's trade
 * (see {@code VillagerTrading}) can grant the villager its trade XP — levelling it up and
 * dropping the XP orb — <em>without</em> going through {@code notifyTrade}. That method also
 * fires the trade advancement and (on Forge/NeoForge) {@code TradeWithVillagerEvent} with the
 * trading <em>player</em>, which an echo isn't.
 */
@Mixin(AbstractVillager.class)
public interface AbstractVillagerTradeInvoker {

    @Invoker("rewardTradeXp")
    void playermob$rewardTradeXp(MerchantOffer offer);
}
