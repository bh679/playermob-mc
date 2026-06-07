package games.brennan.playermob.client;

import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
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
 *
 * <p>Both copies can be pushed down when another mod draws in the same top-left
 * corner — currently Dungeon Train. In-world, DT's version HUD sits at the
 * identical spot on a dev branch ({@link #setExtraTopOffset}); on the main menu,
 * DT adds a version button there whenever it is loaded ({@link
 * #setMenuTopSupplier}, which positions our label below DT's actual widget).</p>
 */
@Environment(EnvType.CLIENT)
public final class VersionHudRenderer {

    /** Top-left inset, in GUI pixels. */
    private static final int MARGIN = 4;
    /** Opaque white (0xAARRGGBB). */
    private static final int TEXT_ARGB = 0xFFFFFFFF;

    /**
     * Extra top inset for the <b>in-world</b> HUD only, in GUI pixels. Installed
     * by a loader when another mod's HUD occupies the same top-left corner.
     * Defaults to no offset and is never set on Fabric/Forge, when Dungeon Train
     * is absent, or when DT is on {@code main} (its label hidden) — so behaviour
     * in all of those cases is unchanged.
     */
    private static volatile IntSupplier extraTopOffset = () -> 0;

    /**
     * Absolute top Y (GUI px) for the <b>main-menu</b> label, as a function of the
     * current screen, or {@code <= 0} to fall back to {@link #MARGIN}. Installed by
     * a loader when another mod owns the title-screen top-left — Dungeon Train adds
     * a version button there — so our label can sit directly below that mod's
     * actual widget. Defaults to no change; never set on Fabric/Forge or without DT.
     */
    private static volatile ToIntFunction<Screen> menuTopSupplier = screen -> 0;

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
     * Install a supplier for the in-world HUD's extra top offset (GUI px), or
     * {@code null} to clear it. Called once at loader boot — see
     * {@code DungeonTrainHud} on NeoForge, which pushes the label one row below
     * Dungeon Train's own version HUD. The supplier is read each frame, so the
     * offset can track another mod's run-time-configurable HUD scale.
     */
    public static void setExtraTopOffset(IntSupplier supplier) {
        extraTopOffset = (supplier != null) ? supplier : () -> 0;
    }

    /** Current in-world extra top offset in GUI px (0 unless a loader installed one). */
    static int extraTopOffsetPx() {
        return extraTopOffset.getAsInt();
    }

    /**
     * Install a supplier of the main-menu label's absolute top Y (GUI px) for the
     * given screen, or {@code null} to clear it. Used by {@code DungeonTrainHud} on
     * NeoForge to drop the label below Dungeon Train's title-screen version button.
     * A return {@code <= 0} means "no other mod is there" → default {@link #MARGIN}.
     */
    public static void setMenuTopSupplier(ToIntFunction<Screen> supplier) {
        menuTopSupplier = (supplier != null) ? supplier : (screen -> 0);
    }

    /** Resolved main-menu top Y: the supplier's value if positive, else {@link #MARGIN}. */
    static int menuTopFor(Screen screen) {
        int top = menuTopSupplier.applyAsInt(screen);
        return top > 0 ? top : MARGIN;
    }

    /**
     * In-world HUD pass. No-op when the GUI is hidden (F1) or when this is a
     * release ({@code main}) build. Pushed down by {@link #extraTopOffsetPx()}
     * when a loader installed an offset (Dungeon Train's HUD sharing the corner).
     */
    public static void render(GuiGraphics graphics) {
        if (Minecraft.getInstance().options.hideGui) {
            return;
        }
        draw(graphics, MARGIN + extraTopOffsetPx());
    }

    /**
     * Screen render pass — draws on the main menu ({@link TitleScreen}) only;
     * all other screens are skipped. F1/hideGui is an in-world toggle, so it is
     * intentionally not consulted here (the menu label should not depend on an
     * in-world setting). Dropped below another mod's title-screen widget via
     * {@link #menuTopFor(Screen)} (Dungeon Train's version button); else {@link #MARGIN}.
     */
    public static void renderOnScreen(GuiGraphics graphics, Screen screen) {
        if (screen instanceof TitleScreen) {
            draw(graphics, menuTopFor(screen));
        }
    }

    /** Shared draw: the {@code main}-branch gate plus the actual text at top inset {@code top}. */
    private static void draw(GuiGraphics graphics, int top) {
        if (!shouldDisplay(VersionInfo.BRANCH)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        graphics.drawString(mc.font, VersionInfo.DISPLAY, MARGIN, top, TEXT_ARGB, true);
    }
}
