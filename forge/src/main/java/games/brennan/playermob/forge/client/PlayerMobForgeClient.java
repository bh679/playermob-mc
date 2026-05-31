package games.brennan.playermob.forge.client;

import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.PlayerMobScreen;
import games.brennan.playermob.forge.PlayerMobForge;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Forge client-side wiring. Registers {@link PlayerMobRenderer} on
 * {@link EntityRenderersEvent.RegisterRenderers} (mod event bus).
 *
 * <p>Instantiated from {@link PlayerMobForge#PlayerMobForge(IEventBus)} only
 * inside the {@code FMLEnvironment.dist == Dist.CLIENT} guard — never loads
 * on a dedicated server boot.</p>
 */
public final class PlayerMobForgeClient {

    private PlayerMobForgeClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PlayerMobForgeClient::onRegisterRenderers);
        modBus.addListener(PlayerMobForgeClient::onClientSetup);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PlayerMobForge.PLAYER_MOB.get(), PlayerMobRenderer::new);
    }

    /**
     * Screen registration runs in enqueueWork — {@code MenuScreens.register}
     * writes a shared static map and Forge dispatches client setup on parallel
     * mod-loading threads, so a direct call here would race.
     */
    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
            MenuScreens.register(PlayerMobForge.PLAYER_MOB_MENU.get(), PlayerMobScreen::new));
    }
}
