package games.brennan.playermob.entity;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link FeelingLedger} — default-neutral lookups, clamped
 * adjust, non-default-only persistence, and UUID-keyed NBT round-tripping.
 * {@link CompoundTag} needs a registry bootstrap (same as the other NBT tests).
 */
class FeelingLedgerTest {

    private static final float EPS = 1e-5f;

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

    @Test
    void saveOmitsNeutralEntries() {
        FeelingLedger ledger = new FeelingLedger();
        UUID id = UUID.randomUUID();
        ledger.set(id, FeelingLedger.DEFAULT); // neutral round-trips to default anyway
        assertTrue(ledger.has(id));
        CompoundTag tag = new CompoundTag();
        ledger.save(tag);
        ListTag list = tag.getList(FeelingLedger.TAG_FEELINGS, Tag.TAG_COMPOUND);
        assertEquals(0, list.size(), "neutral entries are not persisted (no save bloat)");
    }

    @Test
    void saveLoadRoundTripsNonDefaultEntries() {
        FeelingLedger src = new FeelingLedger();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        src.set(a, 3.5f);
        src.set(b, 8.25f);
        CompoundTag tag = new CompoundTag();
        src.save(tag);

        FeelingLedger loaded = new FeelingLedger();
        loaded.load(tag);
        assertEquals(3.5f, loaded.feelingToward(a), EPS);
        assertEquals(8.25f, loaded.feelingToward(b), EPS);
        assertEquals(2, loaded.size());
    }

    @Test
    void loadEmptyTagYieldsEmptyLedger() {
        FeelingLedger loaded = new FeelingLedger();
        loaded.load(new CompoundTag());
        assertEquals(0, loaded.size());
        assertEquals(5.0f, loaded.feelingToward(UUID.randomUUID()), EPS);
    }

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
    void encodeSkipsNeutralEntries() {
        FeelingLedger src = new FeelingLedger();
        src.set(UUID.randomUUID(), FeelingLedger.DEFAULT);
        assertEquals("", src.encode());
        assertTrue(FeelingLedger.decode(src.encode()).isEmpty());
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
