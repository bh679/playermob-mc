package games.brennan.playermob.entity;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the {@link TargetCategory} allow-matrix, defaults, and
 * random picks. {@link TargetCategory#classify} needs live entities, so its
 * coverage is deferred to the in-game Gate 2 smoke test (these tests never call
 * it, keeping them free of a registry bootstrap).
 */
class TargetCategoryTest {

    @Test
    void playersAllowEveryPersonality() {
        for (Personality p : Personality.values()) {
            assertTrue(TargetCategory.PLAYERS.allows(p),
                "Players should allow " + p);
        }
    }

    @Test
    void hostileMobsDisallowFriendly() {
        assertFalse(TargetCategory.HOSTILE_MOBS.allows(Personality.FRIENDLY),
            "You don't befriend a creeper");
        // …but the wary/defensive ones are valid toward hostiles.
        assertTrue(TargetCategory.HOSTILE_MOBS.allows(Personality.SKEPTICAL));
        assertTrue(TargetCategory.HOSTILE_MOBS.allows(Personality.SHY));
        assertTrue(TargetCategory.HOSTILE_MOBS.allows(Personality.AGGRESSIVE));
    }

    @Test
    void animalsAndVillagersDisallowSkepticalAndShy() {
        for (TargetCategory c : new TargetCategory[]{TargetCategory.ANIMALS, TargetCategory.VILLAGERS}) {
            assertFalse(c.allows(Personality.SKEPTICAL), c + " should not allow Skeptical");
            assertFalse(c.allows(Personality.SHY), c + " should not allow Shy");
            assertTrue(c.allows(Personality.AGGRESSIVE));
            assertTrue(c.allows(Personality.FRIENDLY));
            assertTrue(c.allows(Personality.PASSIVE));
        }
    }

    @Test
    void defaultsReproduceV1Behaviour() {
        // Hostile→Aggressive (the old single stance), everything else ignored.
        assertEquals(Personality.AGGRESSIVE, TargetCategory.HOSTILE_MOBS.defaultPersonality());
        assertEquals(Personality.PASSIVE, TargetCategory.PLAYERS.defaultPersonality());
        assertEquals(Personality.PASSIVE, TargetCategory.ANIMALS.defaultPersonality());
        assertEquals(Personality.PASSIVE, TargetCategory.VILLAGERS.defaultPersonality());
    }

    @Test
    void everyCategoryHasNonEmptyAllowedAndAllowsItsDefault() {
        for (TargetCategory c : TargetCategory.values()) {
            assertFalse(c.allowed().isEmpty(), c + " allowed set must be non-empty");
            assertTrue(c.allows(c.defaultPersonality()),
                c + " default must be a member of its allowed set");
        }
    }

    @Test
    void randomAllowedAlwaysReturnsAnAllowedValue() {
        RandomSource random = RandomSource.create(123456789L);
        for (TargetCategory c : TargetCategory.values()) {
            for (int i = 0; i < 200; i++) {
                Personality p = c.randomAllowed(random);
                assertTrue(c.allows(p), c + ".randomAllowed returned disallowed " + p);
            }
        }
    }
}
