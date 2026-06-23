package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link TraitEditButtons} — the Creative trait-editor's
 * button-id → trait-adjustment mapping. No world / NBT, so (unlike the
 * {@code DispositionTraits} NBT tests) no registry bootstrap is needed.
 */
class TraitEditButtonsTest {

    @Test
    void fightFlightButtonsStep() {
        DispositionTraits t = new DispositionTraits(); // 5/5
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_UP, t));
        assertEquals(6, t.fightFlight());
        assertEquals(5, t.friendliness(), "friendliness untouched");

        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_DOWN, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_DOWN, t));
        assertEquals(4, t.fightFlight());
    }

    @Test
    void friendlinessButtonsStep() {
        DispositionTraits t = new DispositionTraits();
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_UP, t));
        assertEquals(6, t.friendliness());
        assertEquals(5, t.fightFlight(), "fight/flight untouched");

        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_DOWN, t));
        assertEquals(5, t.friendliness());
    }

    @Test
    void editingMarksTraitsExplicit() {
        // Explicit traits persist via the existing NBT path (and survive the spawn
        // roll), so a Creative edit must mark the trait explicit. isExplicit() is the
        // AND of both flags, so exercise both buttons.
        DispositionTraits t = new DispositionTraits();
        assertFalse(t.isExplicit());
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_UP, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_DOWN, t));
        assertTrue(t.isExplicit(), "editing both traits marks the mob explicit");
    }

    @Test
    void clampsAtCeiling() {
        DispositionTraits t = new DispositionTraits();
        t.set(10, 10);
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_UP, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_UP, t));
        assertEquals(10, t.fightFlight());
        assertEquals(10, t.friendliness());
    }

    @Test
    void clampsAtFloor() {
        DispositionTraits t = new DispositionTraits();
        t.set(0, 0);
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_DOWN, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_DOWN, t));
        assertEquals(0, t.fightFlight());
        assertEquals(0, t.friendliness());
    }

    @Test
    void unknownIdIsIgnored() {
        DispositionTraits t = new DispositionTraits();
        for (int id : new int[] {-1, 4, 99, Integer.MAX_VALUE}) {
            assertFalse(TraitEditButtons.apply(id, t), "id " + id + " is not a trait button");
        }
        assertEquals(5, t.fightFlight());
        assertEquals(5, t.friendliness());
        assertFalse(t.isExplicit(), "an ignored id must not touch (or mark) the traits");
    }
}
