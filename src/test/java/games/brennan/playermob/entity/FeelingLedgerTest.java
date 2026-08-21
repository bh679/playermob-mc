package games.brennan.playermob.entity;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link FeelingLedger} — default-neutral lookups, the Phase B
 * events (encounter / crouch / defend / witness / travel), all-entry persistence with
 * the strongest-magnitude cap, additive-NBT round-tripping (incl. legacy Phase A
 * entries), and the lossy float sync channel. {@link CompoundTag} needs a registry
 * bootstrap (same as the other NBT tests).
 */
class FeelingLedgerTest {

    private static final float EPS = 1e-4f;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unknownIsNeutralDefault() {
        FeelingLedger ledger = new FeelingLedger();
        assertEquals(5.0f, ledger.feelingToward(UUID.randomUUID()), EPS);
        assertEquals(0, ledger.size());
    }

    @Test
    void adjustClampsToRange() {
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        ledger.adjust(id, -2.0f);
        assertEquals(3.0f, ledger.feelingToward(id), EPS);
        ledger.adjust(id, -10.0f);
        assertEquals(0.0f, ledger.feelingToward(id), EPS); // floor
        ledger.set(id, 99.0f);
        assertEquals(10.0f, ledger.feelingToward(id), EPS); // ceiling
    }

    // ---- Phase B: every entry persists (an entry = "met") ----

    @Test
    void encounterAddsNeutralEntryOnce() {
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        assertTrue(ledger.encounter(id), "first sight creates an entry");
        assertTrue(ledger.has(id));
        assertEquals(5.0f, ledger.feelingToward(id), EPS);
        assertFalse(ledger.encounter(id), "second sight is a no-op");
        assertEquals(1, ledger.size());
    }

    @Test
    void savePersistsEvenNeutralEntries() {
        FeelingLedger ledger = new FeelingLedger();
        ledger.encounter(UUID.randomUUID()); // neutral "met" entry
        CompoundTag tag = new CompoundTag();
        ledger.save(tag);
        ListTag list = NbtCompat.getListOfType(tag, FeelingLedger.TAG_FEELINGS, Tag.TAG_COMPOUND);
        assertEquals(1, list.size(), "Phase B persists met entries (the roster)");
    }

    @Test
    void encodeIncludesNeutralEntries() {
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        ledger.encounter(id);
        Map<UUID, Float> decoded = FeelingLedger.decode(ledger.encode());
        assertEquals(1, decoded.size());
        assertEquals(5.0f, decoded.get(id), EPS);
    }

    // ---- Phase B: events ----

    @Test
    void defendIsDebouncedAndCappedAtTwo() {
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        assertTrue(ledger.defend(id, 100));                 // +1 → 6
        assertFalse(ledger.defend(id, 100), "same event tick is debounced");
        assertEquals(6.0f, ledger.feelingToward(id), EPS);
        assertTrue(ledger.defend(id, 101));                 // +1 → 7
        assertEquals(7.0f, ledger.feelingToward(id), EPS);
        assertFalse(ledger.defend(id, 102), "capped at +2 → no further gain");
        assertEquals(7.0f, ledger.feelingToward(id), EPS);
    }

    @Test
    void witnessAppliesSignedDeltaDebouncedAndClamped() {
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        assertTrue(ledger.witness(id, 0.5f, 50));            // admiration: 5 → 5.5
        assertFalse(ledger.witness(id, 0.5f, 50), "same event tick is debounced");
        assertEquals(5.5f, ledger.feelingToward(id), EPS);
        assertTrue(ledger.witness(id, -2.0f, 51));           // resentment: 5.5 → 3.5
        assertEquals(3.5f, ledger.feelingToward(id), EPS);
        ledger.set(id, 0.0f);
        assertFalse(ledger.witness(id, -1.0f, 60), "already at the floor → no change");
        assertEquals(0.0f, ledger.feelingToward(id), EPS);
        ledger.set(id, 10.0f);
        assertFalse(ledger.witness(id, 0.5f, 70), "already at the ceiling → no change");
        assertEquals(10.0f, ledger.feelingToward(id), EPS);
    }

    @Test
    void witnessGrantsNoCrouchHeadroom() {
        // witness() uses the plain adjusted() path, so (unlike a direct attack-loss) even a negative
        // delta must NOT re-open crouch budget — an attacker can't farm crouch headroom.
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        ledger.witness(id, -2.0f, 10);
        FeelingRecord r = ledger.recordFor(id);
        assertEquals(FeelingRecord.CROUCH_CAP_BASE, r.crouchCap(), EPS);
        assertEquals(0.0f, r.crouchBudgetUsed(), EPS);
        assertEquals(0, r.defendCount());
    }

    @Test
    void scaledOverloadsRouteThroughScaledRecordEvents() {
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        // A friendlier mob (scale 2.0) warms twice as fast per bow and banks twice per defence.
        assertTrue(ledger.crouch(id, 2.0f));                 // +0.2 → 5.2
        assertEquals(5.2f, ledger.feelingToward(id), EPS);
        assertTrue(ledger.defend(id, 200, 2.0f));            // +2.0 → 7.2
        assertEquals(7.2f, ledger.feelingToward(id), EPS);
        assertFalse(ledger.defend(id, 200, 2.0f), "same event tick is still debounced");
        // The unscaled overloads equal a neutral scale of 1.0.
        UUID baseline = UUID.randomUUID();
        assertTrue(ledger.crouch(baseline));
        assertEquals(5.1f, ledger.feelingToward(baseline), EPS);
    }

    // ---- NBT round-trip incl. legacy Phase A entries ----

    @Test
    void saveLoadRoundTripsRecordFields() {
        FeelingLedger src = new FeelingLedger();
        UUID a = UUID.randomUUID();
        src.set(a, 3.5f);
        UUID b = UUID.randomUUID();
        src.crouch(b);        // feeling 5.1, crouchUsed 0.1
        src.defend(b, 100);   // feeling 6.1, defendCount 1, witnessTick 100 (not persisted)
        src.travel(b, 4);     // first sight: record carriage 4, no feeling change
        src.travel(b, 3);     // changed carriage → +0.2 → 6.3

        CompoundTag tag = new CompoundTag();
        src.save(tag);
        FeelingLedger loaded = new FeelingLedger();
        loaded.load(tag);

        assertEquals(3.5f, loaded.feelingToward(a), EPS);
        FeelingRecord rb = loaded.recordFor(b);
        assertEquals(6.3f, rb.feeling(), 1e-3f);
        assertEquals(0.1f, rb.crouchBudgetUsed(), 1e-3f);
        assertEquals(1, rb.defendCount());
        assertEquals(3, rb.lastCarriageIndex());
        assertEquals(0, rb.lastWitnessTick(), "witness debounce is session-scoped — resets on load");
        assertEquals(2, loaded.size());
    }

    @Test
    void legacyEntryLoadsWithWorkingDefaults() {
        // A Phase A entry is just {UUID, Feeling}. crouchCap MUST default to its base
        // (2.0), not 0 — otherwise crouching would be dead-locked for every old mob.
        UUID id = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        NbtCompat.putUUID(entry, FeelingLedger.TAG_UUID, id);
        entry.putFloat(FeelingLedger.TAG_FEELING, 3.0f);
        list.add(entry);
        tag.put(FeelingLedger.TAG_FEELINGS, list);

        FeelingLedger ledger = new FeelingLedger();
        ledger.load(tag);
        FeelingRecord r = ledger.recordFor(id);
        assertEquals(3.0f, r.feeling(), EPS);
        assertEquals(FeelingRecord.CROUCH_CAP_BASE, r.crouchCap(), EPS);
        assertEquals(0.0f, r.crouchBudgetUsed(), EPS);
        assertEquals(0, r.defendCount());
        assertEquals(FeelingRecord.NO_CARRIAGE, r.lastCarriageIndex());
        assertEquals(0, r.lastWitnessTick());
    }

    @Test
    void loadEmptyTagYieldsEmptyLedger() {
        FeelingLedger loaded = new FeelingLedger();
        loaded.load(new CompoundTag());
        assertEquals(0, loaded.size());
        assertEquals(5.0f, loaded.feelingToward(UUID.randomUUID()), EPS);
    }

    // ---- save-bloat cap ----

    @Test
    void capKeepsRichestEntriesNotJustExtremeFeelings() {
        FeelingLedger ledger = new FeelingLedger();
        UUID weakest = null;
        for (int i = 1; i <= FeelingLedger.MAX_ENTRIES; i++) {
            UUID id = UUID.randomUUID();
            if (i == 1) {
                weakest = id;
            }
            ledger.adjust(id, 0.1f * i); // feeling 5 + 0.1i → magnitude 0.1i
        }
        assertEquals(FeelingLedger.MAX_ENTRIES, ledger.size());

        // A feeling-neutral entry that nonetheless carries real history (two defences)
        // must survive the cap over a bare feeling-5.1 entry.
        UUID rich = UUID.randomUUID();
        ledger.defend(rich, 1);
        ledger.defend(rich, 2);
        ledger.adjust(rich, -2.0f); // back to feeling 5, defendCount 2 retained

        assertEquals(FeelingLedger.MAX_ENTRIES, ledger.size(), "cap holds");
        assertTrue(ledger.has(rich), "rich neutral entry survives the cap");
        assertFalse(ledger.has(weakest), "the weakest bare entry is pruned first");
    }

    // ---- provocation ----

    @Test
    void markProvokedLatchesPerIndividual() {
        FeelingLedger ledger = new FeelingLedger();
        UUID hunted = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        assertFalse(ledger.isProvoked(hunted), "unknown individual is not provoked");
        ledger.markProvoked(hunted);
        assertTrue(ledger.isProvoked(hunted));
        assertFalse(ledger.isProvoked(bystander), "provocation does not spill to others");
        ledger.markProvoked(hunted);   // idempotent
        assertTrue(ledger.isProvoked(hunted));
    }

    @Test
    void provocationSurvivesSaveLoad() {
        FeelingLedger src = new FeelingLedger();
        UUID hunted = UUID.randomUUID();
        UUID gifted = UUID.randomUUID();
        src.markProvoked(hunted);
        src.adjust(gifted, 1.0f);
        CompoundTag tag = new CompoundTag();
        src.save(tag);

        FeelingLedger back = new FeelingLedger();
        back.load(tag);
        assertTrue(back.isProvoked(hunted), "a mob that picked the fight still picked it after a reload");
        assertFalse(back.isProvoked(gifted));
        assertEquals(6.0f, back.feelingToward(gifted), EPS);
    }

    @Test
    void entryWithoutTheProvokedKeyLoadsUnprovoked() {
        // A save written before the flag existed: the player struck first, as that build assumed.
        UUID id = UUID.randomUUID();
        CompoundTag entry = new CompoundTag();
        NbtCompat.putUUID(entry, "UUID", id);
        entry.putFloat("Feeling", 4.0f);
        ListTag list = new ListTag();
        list.add(entry);
        CompoundTag tag = new CompoundTag();
        tag.put(FeelingLedger.TAG_FEELINGS, list);

        FeelingLedger back = new FeelingLedger();
        back.load(tag);
        assertFalse(back.isProvoked(id));
        assertEquals(4.0f, back.feelingToward(id), EPS);
    }

    // ---- lossy float sync channel (unchanged) ----

    @Test
    void encodeDecodeRoundTrip() {
        FeelingLedger src = new FeelingLedger();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        src.set(a, 3.5f);
        src.set(b, 8.25f);
        Map<UUID, Float> decoded = FeelingLedger.decode(src.encode());
        assertEquals(2, decoded.size());
        assertEquals(3.5f, decoded.get(a), EPS);
        assertEquals(8.25f, decoded.get(b), EPS);
    }

    @Test
    void decodeHandlesEmptyNullAndMalformed() {
        assertTrue(FeelingLedger.decode("").isEmpty());
        assertTrue(FeelingLedger.decode(null).isEmpty());
        assertTrue(FeelingLedger.decode("garbage;=;not-a-uuid=3;").isEmpty());
        UUID id = UUID.randomUUID();
        Map<UUID, Float> mixed = FeelingLedger.decode("junk;" + id + "=2.5");
        assertEquals(1, mixed.size());
        assertEquals(2.5f, mixed.get(id), EPS);
    }
}
