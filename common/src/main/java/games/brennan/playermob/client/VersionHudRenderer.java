package games.brennan.playermob.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Client-only build-info HUD: draws {@code "PlayerMob v<version> (<branch>)"} in
 * the top-left corner, both <b>in-world</b> and on the <b>main menu</b>. The draw
 * + show/hide decision are shared here; each loader registers its own native
 * hooks and delegates:
 * <ul>
 *   <li>In-world HUD — Fabric {@code HudRenderCallback}, NeoForge
 *       {@code RegisterGuiLayersEvent}, Forge {@code AddGuiOverlayLayersEvent}
 *       → {@link #render(GuiGraphics)}</li>
 *   <li>Main menu — Fabric {@code ScreenEvents}, NeoForge/Forge
 *       {@code ScreenEvent.Render.Post} → {@link #renderOnScreen(GuiGraphics, Screen)}</li>
 * </ul>
 *
 * <p>The in-world path respects F1 (hideGui); the F3 debug overlay draws over it,
 * which is fine.</p>
 */
@Environment(EnvType.CLIENT)
public final class VersionHudRenderer {

    /** Top-left inset, in GUI pixels. */
    private static final int MARGIN = 4;
    /** Opaque white (0xAARRGGBB). */
    private static final int TEXT_ARGB = 0xFFFFFFFF;

    private VersionHudRenderer() {}

    /**
     * The dev-vs-release signal. This HUD exists for testing builds only, so it
     * is hidden when the build was produced from the {@code main} branch — the
     * branch every public release is built from (see {@code release.yml}, which
     * checks out {@code ref: main}). Feature / worktree branches bake their own
     * name and therefore show it.
     *
     * <p>Pure and side-effect free so it can be unit tested without a client.</p>
     */
    public static boolean shouldDisplay(String branch) {
        return branch != null && !"main".equals(branch);
    }

    /**
     * In-world HUD pass. No-op when the GUI is hidden (F1) or when this is a
     * release ({@code main}) build.
     */
    public static void render(GuiGraphics graphics) {
        if (Minecraft.getInstance().options.hideGui) {
            return;
        }
        draw(graphics);
    }

    /**
     * Screen render pass — draws on the main menu ({@link TitleScreen}) only;
     * all other screens are skipped. F1/hideGui is an in-world toggle, so it is
     * intentionally not consulted here (the menu label should not depend on an
     * in-world setting).
     */
    public static void renderOnScreen(GuiGraphics graphics, Screen screen) {
        if (screen instanceof TitleScreen) {
            draw(graphics);
        }
    }

    /** Shared draw: the {@code main}-branch gate plus the actual text. */
    private static void draw(GuiGraphics graphics) {
        if (!shouldDisplay(VersionInfo.BRANCH)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        graphics.drawString(mc.font, VersionInfo.DISPLAY, MARGIN, MARGIN, TEXT_ARGB, true);
    }
}
