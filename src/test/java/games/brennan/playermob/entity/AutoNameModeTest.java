package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import games.brennan.playermob.entity.AutoNameMode.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure oracle for {@link AutoNameMode} — value parsing, spawn-reason classification, and coverage. */
class AutoNameModeTest {

    @Test
    void fromStringParsesEveryTokenCaseInsensitively() {
        assertEquals(AutoNameMode.OFF, AutoNameMode.fromString("off", AutoNameMode.ALL));
        assertEquals(AutoNameMode.NATURAL, AutoNameMode.fromString("Natural", AutoNameMode.OFF));
        assertEquals(AutoNameMode.EGG, AutoNameMode.fromString("  EGG ", AutoNameMode.OFF));
        assertEquals(AutoNameMode.ALL, AutoNameMode.fromString("all", AutoNameMode.OFF));
    }

    @Test
    void fromStringFallsBackOnNullOrUnknown() {
        assertEquals(AutoNameMode.OFF, AutoNameMode.fromString(null, AutoNameMode.OFF));
        assertEquals(AutoNameMode.NATURAL, AutoNameMode.fromString("sometimes", AutoNameMode.NATURAL));
    }

    @Test
    void categorizeMapsReasonNames() {
        assertEquals(Category.NATURAL, AutoNameMode.categorize("NATURAL"));
        assertEquals(Category.NATURAL, AutoNameMode.categorize("CHUNK_GENERATION"));
        assertEquals(Category.EGG, AutoNameMode.categorize("SPAWN_EGG"));
        assertEquals(Category.EGG, AutoNameMode.categorize("SPAWNER"));
        assertEquals(Category.EGG, AutoNameMode.categorize("COMMAND"));
        assertEquals(Category.EGG, AutoNameMode.categorize("DISPENSER"));
        assertEquals(Category.EVENT, AutoNameMode.categorize("EVENT"));
        assertEquals(Category.OTHER, AutoNameMode.categorize("STRUCTURE"));
        assertEquals(Category.OTHER, AutoNameMode.categorize(null));
    }

    @Test
    void offCoversNothing() {
        for (Category c : Category.values()) {
            assertFalse(AutoNameMode.OFF.covers(c), "OFF should not cover " + c);
        }
    }

    @Test
    void naturalAndEggCoverOnlyTheirOwnCategory() {
        assertTrue(AutoNameMode.NATURAL.covers(Category.NATURAL));
        assertFalse(AutoNameMode.NATURAL.covers(Category.EGG));
        assertFalse(AutoNameMode.NATURAL.covers(Category.EVENT));

        assertTrue(AutoNameMode.EGG.covers(Category.EGG));
        assertFalse(AutoNameMode.EGG.covers(Category.NATURAL));
        assertFalse(AutoNameMode.EGG.covers(Category.OTHER));
    }

    @Test
    void allCoversEverythingExceptDungeonTrainEvents() {
        assertTrue(AutoNameMode.ALL.covers(Category.NATURAL));
        assertTrue(AutoNameMode.ALL.covers(Category.EGG));
        assertTrue(AutoNameMode.ALL.covers(Category.OTHER));
        assertFalse(AutoNameMode.ALL.covers(Category.EVENT), "DT events keep their own naming");
    }

    @Test
    void tokenRoundTripsThroughFromString() {
        for (AutoNameMode mode : AutoNameMode.values()) {
            assertEquals(mode, AutoNameMode.fromString(mode.token(), AutoNameMode.OFF));
        }
    }
}
