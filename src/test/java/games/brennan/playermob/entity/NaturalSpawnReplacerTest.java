package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link NaturalSpawnReplacer#rolls} — the replacement roll boundary, isolated
 * from the world RNG and the live config statics (those paths are exercised in-game / by
 * {@code PlayerMobConfigTest}).
 */
class NaturalSpawnReplacerTest {

    @Test
    void rollUnderChanceReplaces() {
        assertTrue(NaturalSpawnReplacer.rolls(0.5F, 0.0F));
        assertTrue(NaturalSpawnReplacer.rolls(0.5F, 0.4999F));
        assertTrue(NaturalSpawnReplacer.rolls(1.0F, 0.999F), "chance 1.0 replaces any roll in [0,1)");
    }

    @Test
    void rollAtOrAboveChanceKeepsOriginal() {
        assertFalse(NaturalSpawnReplacer.rolls(0.5F, 0.5F), "roll == chance does not replace");
        assertFalse(NaturalSpawnReplacer.rolls(0.5F, 0.75F));
        assertFalse(NaturalSpawnReplacer.rolls(0.05F, 0.06F));
    }
}
