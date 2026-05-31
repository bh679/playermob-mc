package games.brennan.playermob.forge.client;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.VersionHudRenderer;
import games.brennan.playermob.forge.PlayerMobForge;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Forge client-side wiring. On the mod event bus, registers
 * {@link PlayerMobRenderer} ({@link EntityRenderersEvent.RegisterRenderers}) and
 * the dev-only build-info HUD ({@link AddGuiOverlayLayersEvent}).
 *
 * <p>Forge 52.x dropped {@code IGuiOverlay}/{@code RegisterGuiOverlaysEvent} in
 * favour of Mojang's layered draw system, so the HUD is added to the
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
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PlayerMobForge.PLAYER_MOB.get(), PlayerMobRenderer::new);
    }

    private static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
        LayeredDraw.Layer layer = (graphics, deltaTracker) -> VersionHudRenderer.render(graphics);
        event.getLayeredDraw().add(
            ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, "version_hud"), layer);
    }
}
