package games.brennan.playermob.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link VersionHudRenderer#shouldDisplay(String)} — the
 * dev-vs-release gate. Does not touch any Minecraft types (no running client),
 * so the core "hidden on main / shown on a feature branch" requirement is
 * verified deterministically without launching the game.
 */
class VersionHudRendererTest {

    @Test
    void hiddenOnMainReleaseBranch() {
        // The whole point: public releases build from `main` → HUD must hide.
        assertFalse(VersionHudRenderer.shouldDisplay("main"));
    }

    @Test
    void shownOnFeatureAndWorktreeBranches() {
        assertTrue(VersionHudRenderer.shouldDisplay("claude/heuristic-bhabha-5ccdba"));
        assertTrue(VersionHudRenderer.shouldDisplay("dev/version-hud"));
        // A detached-HEAD build bakes the short SHA, which is not `main` → shown.
        assertTrue(VersionHudRenderer.shouldDisplay("6759744"));
    }

    @Test
    void hiddenWhenBranchMissing() {
        // Defensive: a null branch (resource absent) should not render a label.
        assertFalse(VersionHudRenderer.shouldDisplay(null));
    }

    // ---- Dungeon Train HUD de-overlap (in-world offset + main-menu top) -------
    // Static holders, so reset after each case to avoid cross-test leakage.
    @AfterEach
    void clearOffsets() {
        VersionHudRenderer.setExtraTopOffset(null);
        VersionHudRenderer.setMenuTopSupplier(null);
    }

    @Test
    void noOffsetByDefault() {
        // Fabric/Forge, or NeoForge without DT (on a branch): label stays put.
        assertEquals(0, VersionHudRenderer.extraTopOffsetPx());
    }

    @Test
    void installedSupplierIsApplied() {
        // NeoForge installs a supplier when DT is on a branch — read each frame.
        VersionHudRenderer.setExtraTopOffset(() -> 11);
        assertEquals(11, VersionHudRenderer.extraTopOffsetPx());
    }

    @Test
    void nullSupplierResetsToZero() {
        VersionHudRenderer.setExtraTopOffset(() -> 11);
        VersionHudRenderer.setExtraTopOffset(null);
        assertEquals(0, VersionHudRenderer.extraTopOffsetPx());
    }

    @Test
    void menuUsesDefaultMarginWhenNoSupplier() {
        // No DT → default supplier returns 0 → renderer falls back to MARGIN (4).
        assertEquals(4, VersionHudRenderer.menuTopFor(null));
    }

    @Test
    void menuSupplierProvidesAbsoluteTop() {
        // DT present → label dropped to the supplied absolute Y (below DT's button).
        VersionHudRenderer.setMenuTopSupplier(screen -> 30);
        assertEquals(30, VersionHudRenderer.menuTopFor(null));
    }

    @Test
    void menuNonPositiveFallsBackToMargin() {
        // DT loaded but its button isn't on this screen → 0 → default MARGIN.
        VersionHudRenderer.setMenuTopSupplier(screen -> 0);
        assertEquals(4, VersionHudRenderer.menuTopFor(null));
    }
}
