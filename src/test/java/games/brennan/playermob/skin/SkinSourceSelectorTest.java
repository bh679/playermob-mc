package games.brennan.playermob.skin;

import org.junit.jupiter.api.Test;

import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure oracle for {@link SkinSourceSelector} — the per-source toggle + override rules. */
class SkinSourceSelectorTest {

    /** A float source that always returns {@code value} (drives the override roll deterministically). */
    private static DoubleSupplier rollFloat(double value) {
        return () -> value;
    }

    /** An int source that always returns {@code value} (clamped into range by the caller's bound). */
    private static IntUnaryOperator pick(int value) {
        return bound -> Math.min(value, bound - 1);
    }

    @Test
    void emptyCustomPoolAlwaysBundled() {
        // No online, no local → bundled regardless of the float roll or toggles.
        var c = SkinSourceSelector.choose(true, true, true, 0, 0, 0.4F, pick(0), rollFloat(0.0));
        assertEquals(SkinSourceSelector.Kind.BUNDLED, c.kind());
    }

    @Test
    void overrideRollKeepsBundledWhenAboveChance() {
        // float roll (0.9) >= customChance (0.4) and bundled enabled → keep bundled.
        var c = SkinSourceSelector.choose(true, true, true, 3, 2, 0.4F, pick(0), rollFloat(0.9));
        assertEquals(SkinSourceSelector.Kind.BUNDLED, c.kind());
    }

    @Test
    void overrideRollPicksCustomWhenBelowChance() {
        // float roll (0.1) < customChance → override; pick(0) lands in the online segment.
        var online = SkinSourceSelector.choose(true, true, true, 3, 2, 0.4F, pick(0), rollFloat(0.1));
        assertEquals(SkinSourceSelector.Kind.ONLINE, online.kind());
        assertEquals(0, online.index());
        // pick index 4 of a 3-online + 2-local pool → local index 1.
        var local = SkinSourceSelector.choose(true, true, true, 3, 2, 0.4F, pick(4), rollFloat(0.1));
        assertEquals(SkinSourceSelector.Kind.LOCAL, local.kind());
        assertEquals(1, local.index());
    }

    @Test
    void bundledOffForcesOverrideWhenPoolExists() {
        // bundled disabled but a custom pool exists → always override, even with a high float roll.
        var c = SkinSourceSelector.choose(false, true, true, 1, 0, 0.4F, pick(0), rollFloat(0.99));
        assertEquals(SkinSourceSelector.Kind.ONLINE, c.kind());
    }

    @Test
    void disabledSourcesAreExcludedFromPool() {
        // online disabled → only the 2 local skins are eligible; pick(0) → local index 0.
        var c = SkinSourceSelector.choose(true, false, true, 5, 2, 0.4F, pick(0), rollFloat(0.0));
        assertEquals(SkinSourceSelector.Kind.LOCAL, c.kind());
        assertEquals(0, c.index());
        // both custom sources disabled → bundled (safety), even though counts are non-zero.
        var bundled = SkinSourceSelector.choose(true, false, false, 5, 2, 0.4F, pick(0), rollFloat(0.0));
        assertEquals(SkinSourceSelector.Kind.BUNDLED, bundled.kind());
    }

    @Test
    void allSourcesOffStillYieldsBundled() {
        // Even with everything off, a mob must get some skin → bundled.
        var c = SkinSourceSelector.choose(false, false, false, 3, 3, 0.4F, pick(0), rollFloat(0.0));
        assertEquals(SkinSourceSelector.Kind.BUNDLED, c.kind());
    }
}
