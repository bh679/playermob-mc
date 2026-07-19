package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link TrinketLedger} — the conjured-flower cap boundary,
 * per-recipient isolation, the unlimited / never sentinel limits, the entry cap, and
 * NBT round-tripping. {@link CompoundTag} needs a registry bootstrap (same as the
 * other NBT tests).
 */
class TrinketLedgerTest {

    private static final int LIMIT = 5;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Give {@code n} flowers to {@code id}, asserting each one was actually allowed. */
    private static void giveAll(TrinketLedger ledger, UUID id, int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(ledger.canGive(id, LIMIT), "flower " + (i + 1) + " should be allowed");
            ledger.record(id);
        }
    }

    @Test
    void fifthFlowerAllowedSixthRefused() {
        TrinketLedger ledger = new TrinketLedger();
        UUID friend = UUID.randomUUID();
        giveAll(ledger, friend, LIMIT);
        assertEquals(LIMIT, ledger.countFor(friend));
        assertFalse(ledger.canGive(friend, LIMIT), "6th conjured flower must be refused");
    }

    @Test
    void unknownRecipientStartsAtZero() {
        TrinketLedger ledger = new TrinketLedger();
        assertEquals(0, ledger.countFor(UUID.randomUUID()));
    }

    @Test
    void capIsPerRecipient() {
        TrinketLedger ledger = new TrinketLedger();
        UUID maxedOut = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        giveAll(ledger, maxedOut, LIMIT);
        assertFalse(ledger.canGive(maxedOut, LIMIT));
        // A different person is entirely unaffected by the first one's tally.
        assertTrue(ledger.canGive(stranger, LIMIT));
        assertEquals(0, ledger.countFor(stranger));
    }

    @Test
    void negativeLimitIsUnlimited() {
        TrinketLedger ledger = new TrinketLedger();
        UUID friend = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            assertTrue(ledger.canGive(friend, -1));
            ledger.record(friend);
        }
        assertTrue(ledger.canGive(friend, -1));
    }

    @Test
    void zeroLimitNeverConjures() {
        TrinketLedger ledger = new TrinketLedger();
        assertFalse(ledger.canGive(UUID.randomUUID(), 0));
    }

    @Test
    void tallySurvivesSaveLoad() {
        TrinketLedger saved = new TrinketLedger();
        UUID friend = UUID.randomUUID();
        giveAll(saved, friend, LIMIT);

        CompoundTag tag = new CompoundTag();
        saved.save(tag);

        TrinketLedger loaded = new TrinketLedger();
        loaded.load(tag);
        assertEquals(LIMIT, loaded.countFor(friend));
        assertFalse(loaded.canGive(friend, LIMIT), "cap must hold across a reload");
    }

    @Test
    void partialTallySurvivesSaveLoadAndStillAllowsTheRest() {
        TrinketLedger saved = new TrinketLedger();
        UUID friend = UUID.randomUUID();
        giveAll(saved, friend, 3);

        CompoundTag tag = new CompoundTag();
        saved.save(tag);

        TrinketLedger loaded = new TrinketLedger();
        loaded.load(tag);
        assertEquals(3, loaded.countFor(friend));
        giveAll(loaded, friend, 2);                      // the remaining 2 of the 5
        assertFalse(loaded.canGive(friend, LIMIT));
    }

    /** A world saved before this feature existed has no key — everyone starts fresh. */
    @Test
    void missingKeyLoadsEmpty() {
        TrinketLedger ledger = new TrinketLedger();
        ledger.load(new CompoundTag());
        UUID anyone = UUID.randomUUID();
        assertEquals(0, ledger.countFor(anyone));
        assertTrue(ledger.canGive(anyone, LIMIT));
    }

    /** Overflowing the entry cap drops the lowest tallies, never a maxed-out recipient. */
    @Test
    void pruneKeepsTheRecipientsNearestTheirCap() {
        TrinketLedger ledger = new TrinketLedger();
        UUID maxedOut = UUID.randomUUID();
        giveAll(ledger, maxedOut, LIMIT);
        for (int i = 0; i < TrinketLedger.MAX_ENTRIES + 10; i++) {
            ledger.record(UUID.randomUUID());            // one flower each — lowest tallies
        }
        assertEquals(LIMIT, ledger.countFor(maxedOut), "a maxed-out tally must not be pruned");
        assertFalse(ledger.canGive(maxedOut, LIMIT));
    }
}
