package games.brennan.playermob.fabric.client;

import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.PlayerMobScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * Fabric client-side entrypoint. Registers {@link PlayerMobRenderer} against
 * the entity type populated by {@link games.brennan.playermob.fabric.PlayerMobFabric#onInitialize()}.
 *
 * <p>Registered via {@code fabric.mod.json} under {@code entrypoints.client}.</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerMobFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(
            PlayerMobRegistry.PLAYER_MOB,
            PlayerMobRenderer::new);

        // Client screen for the Creative inventory menu. onInitializeClient runs
        // after onInitialize, so PLAYER_MOB_MENU is already registered.
        MenuScreens.register(PlayerMobRegistry.PLAYER_MOB_MENU, PlayerMobScreen::new);
    }
}
