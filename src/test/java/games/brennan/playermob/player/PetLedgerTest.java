package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bookkeeping for the pets one life remembers — see {@link PetLedger}. */
class PetLedgerTest {

    private static PetLedger.Entry pet(UUID id, boolean named, boolean alive, long lastSeen) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("id", "minecraft:wolf");
        return new PetLedger.Entry(id, snapshot, named, false, alive, lastSeen);
    }

    @Test
    void withReplacesThePetsPreviousEntry() {
        UUID wolf = UUID.randomUUID();
        PetLedger ledger = PetLedger.EMPTY.with(pet(wolf, false, true, 1)).with(pet(wolf, true, true, 9));
        assertEquals(1, ledger.size());
        assertTrue(ledger.get(wolf).named());
        assertEquals(9, ledger.get(wolf).lastSeen());
    }

    @Test
    void writesNeverMutateTheOriginal() {
        UUID wolf = UUID.randomUUID();
        PetLedger before = PetLedger.EMPTY;
        PetLedger after = before.with(pet(wolf, false, true, 1));
        assertTrue(before.isEmpty());
        assertSame(after, after.without(UUID.randomUUID()));
        assertTrue(after.without(wolf).isEmpty());
        assertEquals(1, after.size());
    }

    @Test
    void deadMarksDeathButKeepsTheSnapshot() {
        PetLedger.Entry e = pet(UUID.randomUUID(), true, true, 3);
        PetLedger.Entry dead = e.dead();
        assertFalse(dead.alive());
        assertSame(e.snapshot(), dead.snapshot());
        assertTrue(dead.named());
    }

    @Test
    void fullLedgerEvictsUnnamedDeadOldestFirstAndKeepsNamed() {
        UUID named = UUID.randomUUID();
        UUID deadOld = UUID.randomUUID();
        PetLedger ledger = PetLedger.EMPTY.with(pet(named, true, false, 0)).with(pet(deadOld, false, false, 0));
        for (int i = 0; i < PetLedger.CAP - 2; i++) {
            ledger = ledger.with(pet(UUID.randomUUID(), false, true, 100 + i));
        }
        assertEquals(PetLedger.CAP, ledger.size());
        ledger = ledger.with(pet(UUID.randomUUID(), false, true, 999));
        assertEquals(PetLedger.CAP, ledger.size());
        assertNull(ledger.get(deadOld));   // unnamed + dead + oldest goes first
        assertNotNull(ledger.get(named));  // a named pet outlasts every unnamed one, dead or not
    }

    @Test
    void saveLoadRoundTrips() {
        UUID wolf = UUID.randomUUID();
        UUID horse = UUID.randomUUID();
        CompoundTag horseSnap = new CompoundTag();
        horseSnap.putString("id", "minecraft:horse");
        PetLedger ledger = PetLedger.EMPTY
            .with(pet(wolf, true, false, 42))
            .with(new PetLedger.Entry(horse, horseSnap, false, true, true, 7));
        PetLedger loaded = PetLedger.load(ledger.save());
        assertEquals(2, loaded.size());
        PetLedger.Entry w = loaded.get(wolf);
        assertTrue(w.named());
        assertFalse(w.alive());
        assertEquals(42, w.lastSeen());
        assertEquals("minecraft:wolf", NbtCompat.getStringOr(w.snapshot(), "id", ""));
        assertTrue(loaded.get(horse).mount());
    }
}
