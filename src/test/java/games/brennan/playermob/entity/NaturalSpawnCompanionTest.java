package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link NaturalSpawnCompanion#rolls} — the companion-spawn roll boundary, isolated
 * from the world RNG and the live config statics (those paths are exercised in-game / by
 * {@code PlayerMobConfigTest}).
 */
class NaturalSpawnCompanionTest {

    @Test
    void rollUnderChanceSpawns() {
        assertTrue(NaturalSpawnCompanion.rolls(0.5F, 0.0F));
        assertTrue(NaturalSpawnCompanion.rolls(0.5F, 0.4999F));
        assertTrue(NaturalSpawnCompanion.rolls(1.0F, 0.999F), "chance 1.0 spawns for any roll in [0,1)");
    }

    @Test
    void rollAtOrAboveChanceDoesNot() {
        assertFalse(NaturalSpawnCompanion.rolls(0.5F, 0.5F), "roll == chance does not spawn");
        assertFalse(NaturalSpawnCompanion.rolls(0.5F, 0.75F));
        assertFalse(NaturalSpawnCompanion.rolls(0.05F, 0.06F));
    }
}
