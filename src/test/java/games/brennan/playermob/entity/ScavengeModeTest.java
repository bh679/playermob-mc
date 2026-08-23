package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link ScavengeMode} — config/command parsing, the allow rule, and which
 * spawn reasons count as "arrived on its own". No Minecraft types involved.
 */
class ScavengeModeTest {

    @Test
    void parsesEveryToken() {
        assertEquals(ScavengeMode.ENABLED, ScavengeMode.fromString("enabled", ScavengeMode.DISABLED));
        assertEquals(ScavengeMode.DISABLED, ScavengeMode.fromString("disabled", ScavengeMode.ENABLED));
        assertEquals(ScavengeMode.ONLY_NATURALLY_SPAWNING,
            ScavengeMode.fromString("onlynaturallyspawning", ScavengeMode.ENABLED));
    }

    @Test
    void parseIsCaseAndWhitespaceInsensitive() {
        assertEquals(ScavengeMode.ONLY_NATURALLY_SPAWNING,
            ScavengeMode.fromString("  OnlyNaturallySpawning  ", ScavengeMode.ENABLED));
        assertEquals(ScavengeMode.DISABLED, ScavengeMode.fromString("DISABLED", ScavengeMode.ENABLED));
    }

    @Test
    void parseAcceptsShorthandsAndBooleans() {
        assertEquals(ScavengeMode.ONLY_NATURALLY_SPAWNING,
            ScavengeMode.fromString("onlynatural", ScavengeMode.ENABLED));
        assertEquals(ScavengeMode.ONLY_NATURALLY_SPAWNING,
            ScavengeMode.fromString("natural", ScavengeMode.ENABLED));
        assertEquals(ScavengeMode.ENABLED, ScavengeMode.fromString("true", ScavengeMode.DISABLED));
        assertEquals(ScavengeMode.ENABLED, ScavengeMode.fromString("on", ScavengeMode.DISABLED));
        assertEquals(ScavengeMode.DISABLED, ScavengeMode.fromString("false", ScavengeMode.ENABLED));
        assertEquals(ScavengeMode.DISABLED, ScavengeMode.fromString("off", ScavengeMode.ENABLED));
    }

    @Test
    void unknownAndNullFallBack() {
        assertEquals(ScavengeMode.ENABLED, ScavengeMode.fromString("sometimes", ScavengeMode.ENABLED));
        assertEquals(ScavengeMode.DISABLED, ScavengeMode.fromString("", ScavengeMode.DISABLED));
        assertEquals(ScavengeMode.DISABLED, ScavengeMode.fromString(null, ScavengeMode.DISABLED));
    }

    @Test
    void tokenRoundTrips() {
        for (ScavengeMode mode : ScavengeMode.values()) {
            assertEquals(mode, ScavengeMode.fromString(mode.token(), null), mode.token());
        }
        assertEquals("onlynaturallyspawning", ScavengeMode.ONLY_NATURALLY_SPAWNING.token());
    }

    @Test
    void allowsFollowsOriginOnlyForTheNarrowMode() {
        assertTrue(ScavengeMode.ENABLED.allows(true));
        assertTrue(ScavengeMode.ENABLED.allows(false));
        assertFalse(ScavengeMode.DISABLED.allows(true));
        assertFalse(ScavengeMode.DISABLED.allows(false));
        assertTrue(ScavengeMode.ONLY_NATURALLY_SPAWNING.allows(true));
        assertFalse(ScavengeMode.ONLY_NATURALLY_SPAWNING.allows(false));
    }

    @Test
    void playerPlacedSpawnsAreNotNaturalOrigin() {
        assertFalse(ScavengeMode.isNaturalOrigin("SPAWN_EGG"));
        assertFalse(ScavengeMode.isNaturalOrigin("COMMAND"));
        assertFalse(ScavengeMode.isNaturalOrigin("DISPENSER"));
    }

    @Test
    void wildSpawnerAndEventSpawnsAreNaturalOrigin() {
        assertTrue(ScavengeMode.isNaturalOrigin("NATURAL"));
        assertTrue(ScavengeMode.isNaturalOrigin("CHUNK_GENERATION"));
        assertTrue(ScavengeMode.isNaturalOrigin("SPAWNER"));
        assertTrue(ScavengeMode.isNaturalOrigin("EVENT"));
    }

    @Test
    void unknownReasonsCountAsNatural() {
        // Fail-open: a spawn path this doesn't know about keeps scavenging rather than silently losing it.
        assertTrue(ScavengeMode.isNaturalOrigin("SOME_FUTURE_REASON"));
        assertTrue(ScavengeMode.isNaturalOrigin(null));
    }
}
