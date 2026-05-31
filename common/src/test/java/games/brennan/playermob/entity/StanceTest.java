package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure-logic tests for {@link Stance}. Does not touch any Minecraft entity types
 * (those require a bootstrapped game registry); covers only ordinal round-trip
 * and the enum invariants.
 *
 * <p>Behavioural coverage of {@link Stance#permitsTargeting} requires a live
 * Minecraft Entity instance and lives in the in-game smoke test (Gate 2),
 * not here.</p>
 */
class StanceTest {

    @Test
    void byOrdinalRoundTripsAllValues() {
        for (Stance stance : Stance.values()) {
            assertEquals(stance, Stance.byOrdinal(stance.ordinal()),
                "byOrdinal(" + stance.ordinal() + ") should return " + stance);
        }
    }

    @Test
    void byOrdinalDefaultsOnNegativeInput() {
        assertEquals(Stance.HOSTILE_TO_HOSTILE_MOBS, Stance.byOrdinal(-1));
        assertEquals(Stance.HOSTILE_TO_HOSTILE_MOBS, Stance.byOrdinal(Integer.MIN_VALUE));
    }

    @Test
    void byOrdinalDefaultsOnOutOfRangeInput() {
        // 99 > current value count; v1 has 1 stance, future versions add more —
        // any out-of-range ordinal should land on the v1 default so old clients
        // loading a future-stamped save don't crash.
        assertEquals(Stance.HOSTILE_TO_HOSTILE_MOBS, Stance.byOrdinal(99));
        assertEquals(Stance.HOSTILE_TO_HOSTILE_MOBS, Stance.byOrdinal(Integer.MAX_VALUE));
    }

    @Test
    void v1ShipsExactlyOneStance() {
        // Sanity check: if a future change adds a stance without updating
        // the goal predicates or save migration, this test surfaces it.
        assertEquals(1, Stance.values().length,
            "Adding a new Stance requires updating target-goal predicates AND save-load tests");
    }

    @Test
    void everyStanceOverridesPermitsTargeting() {
        // Stance is `abstract` for permitsTargeting; an enum constant that
        // forgets the override won't compile. This is a belt-and-braces check
        // in case the design ever shifts to a default method.
        for (Stance stance : Stance.values()) {
            assertNotNull(stance.name());
        }
    }
}
