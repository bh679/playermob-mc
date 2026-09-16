package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for {@link LinkEditButtons} — id layout and row → UUID resolution. */
class LinkEditButtonsTest {

    private static final UUID U1 = new UUID(0L, 1L);
    private static final UUID U2 = new UUID(0L, 2L);

    @Test
    void idRoundTrips() {
        for (int row = 0; row < LinkEditButtons.MAX_ROWS; row++) {
            int id = LinkEditButtons.idFor(row);
            assertTrue(LinkEditButtons.isLinkButton(id));
            assertEquals(row, LinkEditButtons.rowOf(id));
        }
        assertFalse(LinkEditButtons.isLinkButton(LinkEditButtons.idFor(LinkEditButtons.MAX_ROWS)));
    }

    @Test
    void sitsAfterPickerIdsAndFitsAByte() {
        int lastPick = RelationPickerButtons.idFor(RelationPickerButtons.MAX_CANDIDATES - 1, true);
        assertEquals(lastPick + 1, LinkEditButtons.LINK_BASE);
        assertFalse(RelationPickerButtons.isPickButton(LinkEditButtons.LINK_BASE));
        assertFalse(FeelingEditButtons.isFeelingButton(LinkEditButtons.LINK_BASE));
        int last = LinkEditButtons.idFor(LinkEditButtons.MAX_ROWS - 1);
        assertTrue(last <= RelationPickerButtons.MAX_BUTTON_ID,
            "id " + last + " exceeds the 1.20.1 byte-wide button id");
    }

    @Test
    void targetOfResolvesTheRow() {
        FeelingLedger ledger = new FeelingLedger();
        ledger.encounter(U2);
        ledger.encounter(U1);
        assertEquals(Optional.of(U1), LinkEditButtons.targetOf(LinkEditButtons.idFor(0), ledger));
        assertEquals(Optional.of(U2), LinkEditButtons.targetOf(LinkEditButtons.idFor(1), ledger));
        assertTrue(LinkEditButtons.targetOf(LinkEditButtons.idFor(2), ledger).isEmpty(), "stale row");
        assertTrue(LinkEditButtons.targetOf(RelationPickerButtons.REFRESH, ledger).isEmpty());
    }
}
