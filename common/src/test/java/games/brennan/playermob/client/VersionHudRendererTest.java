package games.brennan.playermob.client;

import org.junit.jupiter.api.Test;

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
}
