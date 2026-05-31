package games.brennan.playermob.forge.client;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.VersionHudRenderer;
import games.brennan.playermob.forge.PlayerMobForge;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Forge client-side wiring. On the mod event bus, registers
 * {@link PlayerMobRenderer} ({@link EntityRenderersEvent.RegisterRenderers}) and
 * the in-world build-info HUD ({@link AddGuiOverlayLayersEvent}). The main-menu
 * copy uses {@link ScreenEvent.Render.Post}, which fires on the game event bus.
 *
 * <p>Forge 52.x dropped {@code IGuiOverlay}/{@code RegisterGuiOverlaysEvent} in
 * favour of Mojang's layered draw system, so the in-world HUD is added to the
 * {@code ForgeLayeredDraw} stack rather than registered as an overlay.</p>
 *
 * <p>Instantiated from {@link PlayerMobForge#PlayerMobForge(IEventBus)} only
 * inside the {@code FMLEnvironment.dist == Dist.CLIENT} guard — never loads
 * on a dedicated server boot.</p>
 */
public final class PlayerMobForgeClient {

    private PlayerMobForgeClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PlayerMobForgeClient::onRegisterRenderers);
        modBus.addListener(PlayerMobForgeClient::onAddGuiOverlayLayers);
        // Screen render events fire on the game bus, not the mod bus.
        MinecraftForge.EVENT_BUS.addListener(PlayerMobForgeClient::onScreenRender);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PlayerMobForge.PLAYER_MOB.get(), PlayerMobRenderer::new);
    }

    private static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
        LayeredDraw.Layer layer = (graphics, deltaTracker) -> VersionHudRenderer.render(graphics);
        event.getLayeredDraw().add(
            ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, "version_hud"), layer);
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        VersionHudRenderer.renderOnScreen(event.getGuiGraphics(), event.getScreen());
    }
}
