package games.brennan.playermob.neoforge.client;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.PlayerMobScreen;
import games.brennan.playermob.client.VersionHudRenderer;
import games.brennan.playermob.neoforge.PlayerMobNeoForge;
//? if >=26 {
/*// 26.x removed Mojang's net.minecraft.client.gui.LayeredDraw; NeoForge's GUI layer type is now
// net.neoforged.neoforge.client.gui.GuiLayer (render takes a GuiGraphicsExtractor). ResourceLocation
// was also renamed to Identifier.
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.gui.GuiLayer;
*///?} else {
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
//?}
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
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
        modBus.addListener(PlayerMobNeoForgeClient::onRegisterScreens);
        modBus.addListener(PlayerMobNeoForgeClient::onRegisterGuiLayers);
        modBus.addListener(PlayerMobNeoForgeClient::onClientSetup);
        // Screen render events fire on the game bus, not the mod bus.
        NeoForge.EVENT_BUS.addListener(PlayerMobNeoForgeClient::onScreenRender);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PlayerMobNeoForge.PLAYER_MOB.get(), PlayerMobRenderer::new);
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(PlayerMobNeoForge.PLAYER_MOB_MENU.get(), PlayerMobScreen::new);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        //? if >=26 {
        /*// 26.x: the HUD layer is a NeoForge GuiLayer whose render(GuiGraphicsExtractor, DeltaTracker)
        // hands the extractor the common VersionHudRenderer.render(GuiGraphicsExtractor) already takes
        // (it's guarded to that signature on 26). registerAboveAll now keys on an Identifier.
        GuiLayer layer = (guiGraphicsExtractor, deltaTracker) -> VersionHudRenderer.render(guiGraphicsExtractor);
        event.registerAboveAll(
            Identifier.fromNamespaceAndPath(PlayerMob.MOD_ID, "version_hud"), layer);
        *///?} else {
        LayeredDraw.Layer layer = (graphics, deltaTracker) -> VersionHudRenderer.render(graphics);
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, "version_hud"), layer);
        //?}
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        VersionHudRenderer.renderOnScreen(event.getGuiGraphics(), event.getScreen());
    }

    /**
     * Optional Dungeon Train HUD de-overlap. Gate on DT's presence so the
     * DT-touching helper (and its Dungeon Train imports) is only classloaded when
     * DT is installed — mirrors {@link PlayerMobNeoForge}'s {@code installDungeonTrain()}.
     */
    private static void onClientSetup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("dungeontrain")) {
            installDungeonTrainHudOffset();
        }
    }

    /**
     * Isolated so {@code DungeonTrainHud} and the Dungeon Train symbols it imports
     * are never classloaded unless {@code dungeontrain} is present.
     */
    private static void installDungeonTrainHudOffset() {
        //? if <26 {
        // Dungeon Train is NeoForge-1.21.1-only (see build.gradle.kts) and can't load on MC 26.x, so
        // on >=26 this is an empty no-op — the DT HUD de-overlap never runs and DungeonTrainHud is not
        // referenced. The runtime ModList.isLoaded("dungeontrain") guard at the call site already
        // prevents the call; this drops the compile reference on 26 as well.
        games.brennan.playermob.neoforge.compat.DungeonTrainHud.installVersionHudOffset();
        //?}
    }
}
