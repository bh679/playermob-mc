package games.brennan.playermob.menu;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-agnostic seam for opening a {@link PlayerMobMenu} server-side.
 *
 * <p>Opening a container menu with a custom data payload (here: the target
 * mob's entity id) is done differently on each loader — Fabric uses an
 * {@code ExtendedScreenHandlerFactory}, Forge/NeoForge use
 * {@code ServerPlayer.openMenu(provider, bufWriter)}. The common entity
 * ({@link PlayerMobEntity#mobInteract}) can't reference any of those, so each
 * loader assigns an implementation of this interface to
 * {@link games.brennan.playermob.PlayerMobRegistry#MENU_OPENER} during boot —
 * mirroring how {@code PlayerMobRegistry.PLAYER_MOB} is back-filled per
 * loader.</p>
 */
@FunctionalInterface
public interface PlayerMobMenuOpener {

    /**
     * Open the PlayerMob inventory menu for {@code player}, editing {@code mob}.
     * Always invoked server-side (caller guards on {@link ServerPlayer}).
     */
    void open(ServerPlayer player, PlayerMobEntity mob);
}
