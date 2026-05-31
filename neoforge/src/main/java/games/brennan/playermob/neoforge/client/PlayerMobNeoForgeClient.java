package games.brennan.playermob.neoforge.client;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.VersionHudRenderer;
import games.brennan.playermob.neoforge.PlayerMobNeoForge;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client-side wiring. On the mod event bus, registers
 * {@link PlayerMobRenderer} ({@link EntityRenderersEvent.RegisterRenderers}) and
 * the in-world build-info HUD ({@link RegisterGuiLayersEvent}). The main-menu
 * copy uses {@link ScreenEvent.Render.Post}, which fires on the game event bus.
 *
 * <p>Instantiated from {@link PlayerMobNeoForge#PlayerMobNeoForge(IEventBus)}
 * inside an {@code FMLEnvironment.dist == Dist.CLIENT} guard so the class
 * never loads on a dedicated server. Pure client-side.</p>
 */
public final class PlayerMobNeoForgeClient {

    private PlayerMobNeoForgeClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PlayerMobNeoForgeClient::onRegisterRenderers);
        modBus.addListener(PlayerMobNeoForgeClient::onRegisterGuiLayers);
        // Screen render events fire on the game bus, not the mod bus.
        NeoForge.EVENT_BUS.addListener(PlayerMobNeoForgeClient::onScreenRender);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PlayerMobNeoForge.PLAYER_MOB.get(), PlayerMobRenderer::new);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        LayeredDraw.Layer layer = (graphics, deltaTracker) -> VersionHudRenderer.render(graphics);
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, "version_hud"), layer);
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        VersionHudRenderer.renderOnScreen(event.getGuiGraphics(), event.getScreen());
    }
}
