package games.brennan.playermob.fabric;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.menu.PlayerMobMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Fabric menu provider for {@link PlayerMobMenu}. Implements Fabric's
 * {@link ExtendedScreenHandlerFactory} so the target mob's entity id is
 * serialised to the client (via the {@code StreamCodec} registered on the
 * {@code ExtendedScreenHandlerType}) when the screen opens.
 *
 * <p>Lives in the fabric source set — {@code fabric-api} is not on the common
 * compile classpath, so this can't live alongside {@code PlayerMobMenu}.</p>
 */
public final class PlayerMobMenuProvider implements ExtendedScreenHandlerFactory<Integer> {

    private final PlayerMobEntity mob;

    public PlayerMobMenuProvider(PlayerMobEntity mob) {
        this.mob = mob;
    }

    @Override
    public Integer getScreenOpeningData(ServerPlayer player) {
        return mob.getId();
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
        return new PlayerMobMenu(syncId, inventory, mob);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.playermob.player_mob");
    }
}
