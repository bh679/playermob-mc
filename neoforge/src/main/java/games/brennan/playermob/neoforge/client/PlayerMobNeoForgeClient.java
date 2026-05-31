package games.brennan.playermob.neoforge.client;

import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.neoforge.PlayerMobNeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * NeoForge client-side wiring. Registers {@link PlayerMobRenderer} on the
 * mod event bus' {@link EntityRenderersEvent.RegisterRenderers} hook —
 * the only point at which client-side renderer registration is permitted.
 *
 * <p>Instantiated from {@link PlayerMobNeoForge#PlayerMobNeoForge(IEventBus)}
 * inside an {@code FMLEnvironment.dist == Dist.CLIENT} guard so the class
 * never loads on a dedicated server. Pure client-side.</p>
 */
public final class PlayerMobNeoForgeClient {

    private PlayerMobNeoForgeClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PlayerMobNeoForgeClient::onRegisterRenderers);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PlayerMobNeoForge.PLAYER_MOB.get(), PlayerMobRenderer::new);
    }
}
