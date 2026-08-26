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
    void reactionSpeedButtonsStep() {
        DispositionTraits t = new DispositionTraits();
        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_UP, t));
        assertEquals(6, t.reactionSpeed());
        assertEquals(5, t.fightFlight(), "fight/flight untouched");
        assertEquals(5, t.friendliness(), "friendliness untouched");

        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_DOWN, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_DOWN, t));
        assertEquals(4, t.reactionSpeed());
    }

    @Test
    void editingMarksTraitsExplicit() {
        // Explicit traits persist via the existing NBT path (and survive the spawn
        // roll), so a Creative edit must mark the trait explicit. isExplicit() is the
        // AND of every flag, so exercise a button from each trait.
        DispositionTraits t = new DispositionTraits();
        assertFalse(t.isExplicit());
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_UP, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_DOWN, t));
        assertFalse(t.isExplicit(), "reaction speed has not been edited yet");
        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_UP, t));
        assertTrue(t.isExplicit(), "editing every trait marks the mob explicit");
    }

    @Test
    void clampsAtCeiling() {
        DispositionTraits t = new DispositionTraits();
        t.set(10, 10, 10);
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_UP, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_UP, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_UP, t));
        assertEquals(10, t.fightFlight());
        assertEquals(10, t.friendliness());
        assertEquals(10, t.reactionSpeed());
    }

    @Test
    void clampsAtFloor() {
        DispositionTraits t = new DispositionTraits();
        t.set(0, 0, 0);
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FIGHT_FLIGHT_DOWN, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.FRIENDLINESS_DOWN, t));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_DOWN, t));
        assertEquals(0, t.fightFlight());
        assertEquals(0, t.friendliness());
        assertEquals(0, t.reactionSpeed());
    }

    @Test
    void unknownIdIsIgnored() {
        DispositionTraits t = new DispositionTraits();
        for (int id : new int[] {-1, TraitEditButtons.ID_COUNT, 99, Integer.MAX_VALUE}) {
            assertFalse(TraitEditButtons.apply(id, t), "id " + id + " is not a trait button");
        }
        assertEquals(5, t.fightFlight());
        assertEquals(5, t.friendliness());
        assertEquals(5, t.reactionSpeed());
        assertFalse(t.isExplicit(), "an ignored id must not touch (or mark) the traits");
    }

    @Test
    void everyTraitIdIsClaimedAndNoneCollideWithFeelingButtons() {
        // The relationship-row buttons live immediately above the trait ids. If a new trait
        // were added without moving FEELING_BASE, the Creative menu's relationship rows would
        // silently start editing traits — so pin the boundary from both sides.
        DispositionTraits t = new DispositionTraits();
        for (int id = 0; id < TraitEditButtons.ID_COUNT; id++) {
            assertTrue(TraitEditButtons.apply(id, t), "id " + id + " should be a trait button");
            assertFalse(FeelingEditButtons.isFeelingButton(id), "id " + id + " must not be a feeling button");
        }
        assertEquals(TraitEditButtons.ID_COUNT, FeelingEditButtons.FEELING_BASE);
        assertTrue(FeelingEditButtons.isFeelingButton(FeelingEditButtons.FEELING_BASE));
        assertFalse(TraitEditButtons.apply(FeelingEditButtons.FEELING_BASE, t),
            "the first feeling id must not be a trait button");
    }
}
