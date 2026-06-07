package games.brennan.playermob.neoforge.compat;

import games.brennan.dungeontrain.client.HudText;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.playermob.client.VersionHudRenderer;
import net.minecraft.client.Minecraft;

/**
 * Client-side Dungeon Train HUD de-overlap. PlayerMob and Dungeon Train both draw
 * a dev build-info label in the top-left corner at the same {@code (4, 4)} spot,
 * so when both run from a dev branch the two labels overlap. This installs an
 * in-world top offset that pushes PlayerMob's label exactly one row below DT's.
 *
 * <p>DT's {@code VersionHudOverlay} is gated on {@code !"main".equals(BRANCH)} (it
 * mirrors PlayerMob's own hide-on-{@code main} rule), so its label is only on
 * screen when DT is on a branch — we install the offset only in that case, leaving
 * a release-build DT (no label) with no phantom gap.</p>
 *
 * <p>Like {@link DungeonTrainEnvironment}, this class imports Dungeon Train symbols
 * and is referenced only from behind the {@code ModList.isLoaded("dungeontrain")}
 * guard in the NeoForge client entrypoint, so it (and those symbols) is never
 * classloaded when DT is absent.</p>
 */
public final class DungeonTrainHud {

    private DungeonTrainHud() {}

    /**
     * If Dungeon Train is on a branch (its version label visible), push PlayerMob's
     * in-world label one DT row below it. The offset reads DT's own
     * {@link HudText#scaledLineHeight(net.minecraft.client.gui.Font)} each frame so
     * it tracks DT's run-time-configurable HUD scale; {@code +1} matches DT's
     * inter-line spacing, landing PlayerMob exactly where DT's next line would go.
     */
    public static void installVersionHudOffset() {
        if (!"main".equals(VersionInfo.BRANCH)) {
            VersionHudRenderer.setExtraTopOffset(
                () -> HudText.scaledLineHeight(Minecraft.getInstance().font) + 1);
        }
    }
}
