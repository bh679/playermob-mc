package games.brennan.playermob.forge.client;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.PlayerMobScreen;
import games.brennan.playermob.client.VersionHudRenderer;
import games.brennan.playermob.forge.PlayerMobForge;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
//? if >=1.21.1 {
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
//?}
//? if <1.21.1 {
/*import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
*///?}

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
 * <p>Instantiated from {@link PlayerMobForge} only inside the
 * {@code FMLEnvironment.dist == Dist.CLIENT} guard — never loads on a
 * dedicated server boot.</p>
 */
public final class PlayerMobForgeClient {

    private PlayerMobForgeClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(PlayerMobForgeClient::onRegisterRenderers);
        modBus.addListener(PlayerMobForgeClient::onClientSetup);
        //? if >=1.21.1 {
        modBus.addListener(PlayerMobForgeClient::onAddGuiOverlayLayers);
        //?} else {
        /*modBus.addListener(PlayerMobForgeClient::onRegisterGuiOverlays);*/
        //?}
        // Screen render events fire on the game bus, not the mod bus.
        MinecraftForge.EVENT_BUS.addListener(PlayerMobForgeClient::onScreenRender);
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

    //? if >=1.21.1 {
    private static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
        LayeredDraw.Layer layer = (graphics, deltaTracker) -> VersionHudRenderer.render(graphics);
        event.getLayeredDraw().add(
            ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, "version_hud"), layer);
    }
    //?} else {
    /*// Forge 47.x (1.20.1) registers an in-world HUD via the legacy IGuiOverlay system,
    // before the Mojang LayeredDraw stack existed. Drawn above every vanilla overlay.
    private static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("version_hud",
            (forgeGui, graphics, partialTick, screenWidth, screenHeight) ->
                VersionHudRenderer.render(graphics));
    }*///?}

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        VersionHudRenderer.renderOnScreen(event.getGuiGraphics(), event.getScreen());
    }
}
