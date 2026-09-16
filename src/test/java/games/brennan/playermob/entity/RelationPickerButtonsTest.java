package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link RelationPickerButtons} — the add-relation picker's button-id
 * layout. The important invariant is the 1.20.1 one: every id must fit a signed byte, because
 * that version's container-button packet truncates the id on the wire.
 */
class RelationPickerButtonsTest {

    @Test
    void idRoundTrips() {
        for (int row = 0; row < RelationPickerButtons.MAX_CANDIDATES; row++) {
            int plain = RelationPickerButtons.idFor(row, false);
            int mirrored = RelationPickerButtons.idFor(row, true);
            assertTrue(RelationPickerButtons.isPickButton(plain));
            assertTrue(RelationPickerButtons.isPickButton(mirrored));
            assertEquals(row, RelationPickerButtons.rowOf(plain));
            assertEquals(row, RelationPickerButtons.rowOf(mirrored));
            assertFalse(RelationPickerButtons.isMirror(plain));
            assertTrue(RelationPickerButtons.isMirror(mirrored));
            assertFalse(RelationPickerButtons.isRefresh(plain));
        }
    }

    @Test
    void refreshIsNotAPickButton() {
        assertTrue(RelationPickerButtons.isRefresh(RelationPickerButtons.REFRESH));
        assertFalse(RelationPickerButtons.isPickButton(RelationPickerButtons.REFRESH));
    }

    @Test
    void doesNotOverlapTraitOrFeelingIds() {
        int lastFeeling = FeelingEditButtons.FEELING_BASE + FeelingEditButtons.MAX_ROWS * 2 - 1;
        assertTrue(RelationPickerButtons.REFRESH > lastFeeling);
        assertFalse(FeelingEditButtons.isFeelingButton(RelationPickerButtons.REFRESH));
        assertFalse(FeelingEditButtons.isFeelingButton(RelationPickerButtons.ADD_BASE));
        assertFalse(RelationPickerButtons.isPickButton(lastFeeling));
        assertFalse(RelationPickerButtons.isRefresh(lastFeeling));
        // Trait ids sit below everything.
        for (int id = 0; id < TraitEditButtons.ID_COUNT; id++) {
            assertFalse(RelationPickerButtons.isPickButton(id), "trait id " + id);
            assertFalse(RelationPickerButtons.isRefresh(id), "trait id " + id);
        }
    }

    @Test
    void pastLastRowIsNotAPickButton() {
        int last = RelationPickerButtons.idFor(RelationPickerButtons.MAX_CANDIDATES - 1, true);
        assertTrue(RelationPickerButtons.isPickButton(last));
        assertFalse(RelationPickerButtons.isPickButton(last + 1));
    }

    /** 1.20.1 writes the button id as a signed byte — a MAX_CANDIDATES bump must never cross it. */
    @Test
    void everyIdFitsAByte() {
        int last = RelationPickerButtons.idFor(RelationPickerButtons.MAX_CANDIDATES - 1, true);
        assertTrue(last <= RelationPickerButtons.MAX_BUTTON_ID,
            "id " + last + " exceeds the 1.20.1 byte-wide button id");
    }
}
