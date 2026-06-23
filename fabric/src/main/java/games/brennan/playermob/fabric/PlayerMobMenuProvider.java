package games.brennan.playermob.fabric;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.menu.PlayerMobMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

//? if <1.21.1 {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}

/**
 * Fabric menu provider for {@link PlayerMobMenu}. Implements Fabric's
 * {@link ExtendedScreenHandlerFactory} so the target mob's entity id reaches the
 * client when the screen opens.
 *
 * <p>The serialisation shape changed with the MC 1.21 networking rewrite. On 1.21.1
 * the factory is generic ({@code ExtendedScreenHandlerFactory<Integer>}) and the id
 * is returned from {@code getScreenOpeningData}, serialised by the type's
 * {@code StreamCodec}. On 1.20.1 the factory is non-generic and writes the id straight
 * into a {@code FriendlyByteBuf} via {@code writeScreenOpeningData}.</p>
 *
 * <p>Lives in the fabric source set — {@code fabric-api} is not on the common compile
 * classpath, so this can't live alongside {@code PlayerMobMenu}.</p>
 */
//? if >=1.21.1 {
public final class PlayerMobMenuProvider implements ExtendedScreenHandlerFactory<Integer> {
//?} else {
/*public final class PlayerMobMenuProvider implements ExtendedScreenHandlerFactory {
*///?}

    private final PlayerMobEntity mob;

    public PlayerMobMenuProvider(PlayerMobEntity mob) {
        this.mob = mob;
    }

    //? if >=1.21.1 {
    @Override
    public Integer getScreenOpeningData(ServerPlayer player) {
        return mob.getId();
    }
    //?} else {
    /*@Override
    public void writeScreenOpeningData(ServerPlayer player, FriendlyByteBuf buf) {
        buf.writeVarInt(mob.getId());
    }
    *///?}

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
        return new PlayerMobMenu(syncId, inventory, mob);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.playermob.player_mob");
    }
}
