package games.brennan.playermob.fabric.client;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.PlayerMobScreen;
import games.brennan.playermob.client.VersionHudRenderer;
import games.brennan.playermob.compat.RegistryCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
//? if >=26 {
/*import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//?}
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * Fabric client-side entrypoint. Registers {@link PlayerMobRenderer} against
 * the entity type populated by {@link games.brennan.playermob.fabric.PlayerMobFabric#onInitialize()},
 * plus the dev-only build-info HUD ({@link VersionHudRenderer}) — both in-world
 * ({@code HudRenderCallback}) and on the main menu ({@code ScreenEvents}).
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

        // Dev-only build-info HUD — hidden on the `main`/release branch.
        // In-world:
        //? if >=26 {
        /*// 26.x replaced HudRenderCallback with the HudElementRegistry layer API; the element's
        // extractRenderState callback hands us a GuiGraphicsExtractor (which VersionHudRenderer.render
        // already takes on 26). addLast draws our label after the vanilla HUD elements.
        HudElementRegistry.addLast(
            RegistryCompat.id(PlayerMob.MOD_ID, "version_hud"),
            (graphics, deltaTracker) -> VersionHudRenderer.render(graphics));
        *///?} else {
        HudRenderCallback.EVENT.register(
            (graphics, tickCounter) -> VersionHudRenderer.render(graphics));
        //?}
        // Main menu (and any screen — renderOnScreen filters to the title screen):
        //? if >=26 {
        /*// 26.x renamed the per-screen post-render hook from afterRender to afterExtract; the
        // callback now hands a GuiGraphicsExtractor (which VersionHudRenderer.renderOnScreen takes).
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
            ScreenEvents.afterExtract(screen).register(
                (renderedScreen, graphics, mouseX, mouseY, tickDelta) ->
                    VersionHudRenderer.renderOnScreen(graphics, renderedScreen)));
        *///?} else {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
            ScreenEvents.afterRender(screen).register(
                (renderedScreen, graphics, mouseX, mouseY, tickDelta) ->
                    VersionHudRenderer.renderOnScreen(graphics, renderedScreen)));
        //?}
    }
}
