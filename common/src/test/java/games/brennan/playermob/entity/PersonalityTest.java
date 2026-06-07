package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link Personality} — ordinal round-trip and the
 * graceful-fallback contract that the spawn-egg / {@code /summon} NBT paths
 * rely on. Behavioural coverage lives in the in-game Gate 2 smoke test.
 */
class PersonalityTest {

    @Test
    void byOrdinalRoundTripsAllValues() {
        for (Personality p : Personality.values()) {
            assertEquals(p, Personality.byOrdinal(p.ordinal()),
                "byOrdinal(" + p.ordinal() + ") should return " + p);
        }
    }

    @Test
    void byOrdinalDefaultsToPassiveOnNegativeInput() {
        assertEquals(Personality.PASSIVE, Personality.byOrdinal(-1));
        assertEquals(Personality.PASSIVE, Personality.byOrdinal(Integer.MIN_VALUE));
    }

    @Test
    void byOrdinalDefaultsToPassiveOnOutOfRangeInput() {
        // A future-stamped save (more personalities) loaded on this version must
        // land on the safe "ignore" disposition rather than crash.
        assertEquals(Personality.PASSIVE, Personality.byOrdinal(99));
        assertEquals(Personality.PASSIVE, Personality.byOrdinal(Integer.MAX_VALUE));
    }

    @Test
    void ordinalsAreStable() {
        // The spawn-egg entity_data + /summon NBT reference these ordinals.
        // Changing them is a save-format break — this pins the contract.
        assertEquals(0, Personality.AGGRESSIVE.ordinal());
        assertEquals(1, Personality.FRIENDLY.ordinal());
        assertEquals(2, Personality.PASSIVE.ordinal());
        assertEquals(3, Personality.SKEPTICAL.ordinal());
        assertEquals(4, Personality.SHY.ordinal());
    }
}
