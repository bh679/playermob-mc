package games.brennan.playermob.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Client-only build-info HUD: draws {@code "PlayerMob v<version> (<branch>)"} in
 * the top-left corner in-game. The drawing + show/hide decision are shared here;
 * each loader registers its own native HUD hook and delegates to {@link #render}:
 * <ul>
 *   <li>Fabric — {@code HudRenderCallback}</li>
 *   <li>NeoForge — {@code RegisterGuiLayersEvent}</li>
 *   <li>Forge — {@code AddGuiOverlayLayersEvent}</li>
 * </ul>
 *
 * <p>Respects F1 (hideGui). The F3 debug overlay draws over this, which is fine.</p>
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
     * Draws the build-info line. No-op when the GUI is hidden (F1) or when this
     * is a release ({@code main}) build. Called from each loader's HUD hook.
     */
    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        if (!shouldDisplay(VersionInfo.BRANCH)) {
            return;
        }
        graphics.drawString(mc.font, VersionInfo.DISPLAY, MARGIN, MARGIN, TEXT_ARGB, true);
    }
}
