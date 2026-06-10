package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link FeelingEditButtons} — the Creative relationship
 * editor's button-id ↔ row mapping and the per-row feeling adjustment against a
 * {@link FeelingLedger}. No world / NBT (the ledger paths used here are plain map
 * ops), so no registry bootstrap is needed.
 *
 * <p>UUIDs are built as {@code new UUID(0, n)} so their natural (signed) ordering
 * is simply {@code n} ascending — the same order the server and client use.</p>
 */
class FeelingEditButtonsTest {

    private static final UUID U1 = new UUID(0L, 1L);
    private static final UUID U2 = new UUID(0L, 2L);
    private static final UUID U3 = new UUID(0L, 3L);

    private static FeelingLedger ledgerOf(float f1, float f2, float f3) {
        FeelingLedger ledger = new FeelingLedger();
        ledger.set(U1, f1);
        ledger.set(U2, f2);
        ledger.set(U3, f3);
        return ledger;
    }

    // ---- id encode/decode -------------------------------------------------

    @Test
    void idRoundTrips() {
        for (int row = 0; row < FeelingEditButtons.MAX_ROWS; row++) {
            int down = FeelingEditButtons.idFor(row, false);
            int up = FeelingEditButtons.idFor(row, true);
            assertEquals(row, FeelingEditButtons.rowOf(down));
            assertEquals(row, FeelingEditButtons.rowOf(up));
            assertFalse(FeelingEditButtons.isUp(down));
            assertTrue(FeelingEditButtons.isUp(up));
        }
    }

    @Test
    void isFeelingButtonBoundaries() {
        // Trait ids (0..3) are not feeling buttons.
        for (int id = 0; id < FeelingEditButtons.FEELING_BASE; id++) {
            assertFalse(FeelingEditButtons.isFeelingButton(id), "trait id " + id);
        }
        int firstFeeling = FeelingEditButtons.FEELING_BASE;
        int lastFeeling = FeelingEditButtons.FEELING_BASE + FeelingEditButtons.MAX_ROWS * 2 - 1;
        assertTrue(FeelingEditButtons.isFeelingButton(firstFeeling));
        assertTrue(FeelingEditButtons.isFeelingButton(lastFeeling));
        assertFalse(FeelingEditButtons.isFeelingButton(lastFeeling + 1), "past the last row");
        assertFalse(FeelingEditButtons.isFeelingButton(-1));
    }

    // ---- apply against a ledger -------------------------------------------

    @Test
    void appliesToTheRowInStableOrder() {
        FeelingLedger ledger = ledgerOf(3.0F, 7.0F, 8.0F);
        // Stable order is U1, U2, U3 (UUID ascending).
        assertEquals(List.of(U1, U2, U3), ledger.uuidsSorted());

        assertTrue(FeelingEditButtons.apply(FeelingEditButtons.idFor(0, true), ledger));  // U1 +1
        assertTrue(FeelingEditButtons.apply(FeelingEditButtons.idFor(1, false), ledger)); // U2 -1
        assertTrue(FeelingEditButtons.apply(FeelingEditButtons.idFor(2, true), ledger));  // U3 +1

        assertEquals(4.0F, ledger.feelingToward(U1));
        assertEquals(6.0F, ledger.feelingToward(U2));
        assertEquals(9.0F, ledger.feelingToward(U3));
    }

    @Test
    void clampsAtFloorAndCeiling() {
        FeelingLedger ledger = ledgerOf(0.0F, 10.0F, 8.0F);
        assertTrue(FeelingEditButtons.apply(FeelingEditButtons.idFor(0, false), ledger)); // U1 stays 0
        assertTrue(FeelingEditButtons.apply(FeelingEditButtons.idFor(1, true), ledger));  // U2 stays 10
        assertEquals(0.0F, ledger.feelingToward(U1));
        assertEquals(10.0F, ledger.feelingToward(U2));
    }

    @Test
    void outOfRangeRowIsIgnored() {
        FeelingLedger ledger = ledgerOf(3.0F, 7.0F, 8.0F); // 3 rows: indices 0..2
        assertFalse(FeelingEditButtons.apply(FeelingEditButtons.idFor(3, true), ledger));
        // Ledger untouched.
        assertEquals(3.0F, ledger.feelingToward(U1));
        assertEquals(7.0F, ledger.feelingToward(U2));
        assertEquals(8.0F, ledger.feelingToward(U3));
    }

    @Test
    void nonFeelingIdIsIgnored() {
        FeelingLedger ledger = ledgerOf(3.0F, 7.0F, 8.0F);
        for (int id : new int[] {TraitEditButtons.FIGHT_FLIGHT_DOWN, TraitEditButtons.FRIENDLINESS_UP, -1}) {
            assertFalse(FeelingEditButtons.apply(id, ledger), "id " + id + " is not a feeling button");
        }
        assertEquals(3.0F, ledger.feelingToward(U1));
    }

    @Test
    void neutralEntriesAreIncludedInOrder() {
        // Phase B persists everyone the mob has *met*, so a neutral (default) entry
        // is still an editable relationship row — unlike Phase A, which dropped it.
        FeelingLedger ledger = ledgerOf(3.0F, FeelingLedger.DEFAULT, 8.0F);
        assertEquals(List.of(U1, U2, U3), ledger.uuidsSorted());
        // Row 1 addresses the neutral U2; editing it moves U2's feeling.
        assertTrue(FeelingEditButtons.apply(FeelingEditButtons.idFor(1, false), ledger)); // U2 -1
        assertEquals(4.0F, ledger.feelingToward(U2));
        assertEquals(8.0F, ledger.feelingToward(U3));
    }
}
