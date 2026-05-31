package games.brennan.playermob.forge.client;

import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.forge.PlayerMobForge;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;

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
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PlayerMobForge.PLAYER_MOB.get(), PlayerMobRenderer::new);
    }
}
