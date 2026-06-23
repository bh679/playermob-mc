package games.brennan.playermob.neoforge.compat;

//? if >=26 {
/*// Dungeon Train ({@code dungeontrain}) is a NeoForge-1.21.1-only optional compile dependency and
// does not exist for MC 26.x (see neoforge/build.gradle.kts). The real DT HUD de-overlap below
// imports DT client symbols that can't resolve on 26, so on >=26 this is a never-called stub:
// PlayerMobNeoForgeClient.installDungeonTrainHudOffset() (its only caller) is guarded to <26.
// The class must still compile in the source set, so installVersionHudOffset() is a no-op here.
// Line comments only inside this >=26 block (no nested block comments).
public final class DungeonTrainHud {
    private DungeonTrainHud() {}
    public static void installVersionHudOffset() {}
}
*///?} else {
import games.brennan.dungeontrain.client.HudText;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.version.VersionStatusButton;
import games.brennan.playermob.client.VersionHudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client-side Dungeon Train HUD de-overlap. PlayerMob and Dungeon Train both put a
 * dev build-info label in the top-left corner at the same {@code (4, 4)} spot, so
 * they collide when shown together. This pushes PlayerMob's label clear of DT's in
 * both places DT draws there:
 * <ul>
 *   <li><b>In-world</b> — DT's {@code VersionHudOverlay} draws a version line, gated
 *       on {@code !"main".equals(BRANCH)} (it mirrors PlayerMob's hide-on-{@code main}
 *       rule). We install an in-world offset only on a dev branch, one DT row tall
 *       ({@link HudText#scaledLineHeight}, which tracks DT's configurable HUD scale).</li>
 *   <li><b>Main menu</b> — DT adds a clickable {@code VersionStatusButton} at the
 *       title-screen top-left whenever DT is loaded (any branch). We install a screen
 *       supplier that finds that button and returns its bottom edge, so our label sits
 *       just under it — and returns {@code 0} (no change) if DT isn't showing it.</li>
 * </ul>
 *
 * <p>Like {@link DungeonTrainEnvironment}, this class imports Dungeon Train symbols
 * and is referenced only from behind the {@code ModList.isLoaded("dungeontrain")}
 * guard in the NeoForge client entrypoint, so it (and those symbols) is never
 * classloaded when DT is absent.</p>
 */
public final class DungeonTrainHud {

    /** Vertical gap (GUI px) between DT's title-screen button and our label below it. */
    private static final int MENU_GAP = 2;

    private DungeonTrainHud() {}

    /**
     * Install the de-overlap offsets (see class doc). Called once at client setup,
     * only when {@code dungeontrain} is present.
     */
    public static void installVersionHudOffset() {
        VersionHudRenderer.setMenuTopSupplier(DungeonTrainHud::menuTopForScreen);
        if (!"main".equals(VersionInfo.BRANCH)) {
            VersionHudRenderer.setExtraTopOffset(
                () -> HudText.scaledLineHeight(Minecraft.getInstance().font) + 1);
        }
    }

    /**
     * Absolute top Y for our menu label: just below DT's {@link VersionStatusButton}
     * if the current screen has one, else {@code 0} so the renderer keeps its default.
     */
    private static int menuTopForScreen(Screen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof VersionStatusButton button) {
                return button.getY() + button.getHeight() + MENU_GAP;
            }
        }
        return 0;
    }
}
//?}
