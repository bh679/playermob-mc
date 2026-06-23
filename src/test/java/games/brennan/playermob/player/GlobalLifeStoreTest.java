package games.brennan.playermob.player;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.player.GlobalLifeStore.DeathRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link GlobalLifeStore}: the death-log NBT round-trip, the carriage-band
 * eligibility filter, and the query accessors the reincarnation source registry consumes. No
 * Minecraft world (only {@link CompoundTag}/{@link UUID}), like {@code PlayerLifeRecordTest}; the
 * filesystem path is exercised in-game. The recency/proximity-weighted pick and the
 * one-echo-per-life session behaviour now live in the registry — see {@code ReincarnationWeightingTest}
 * and {@code ReincarnationSourcesTest}.
 */
class GlobalLifeStoreTest {

    private static final int NONE = TrainConfinement.NO_CARRIAGE;

    private static UUID uuid(int n) {
        return new UUID(0L, n);
    }

    private static CompoundTag snap(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "playermob:player_mob");
        tag.putString("Marker", marker);
        return tag;
    }

    private static DeathRecord rec(long id, int player, int carriage, String marker) {
        return new DeathRecord(id, uuid(player), "P" + player, carriage, snap(marker));
    }

    // ---- NBT round-trip ----

    @Test
    void writeReadRoundTrip() {
        List<DeathRecord> history = List.of(
            rec(1, 1, 0, "a"), rec(2, 2, 25, "b"), rec(3, 3, -40, "c"));
        CompoundTag tag = new CompoundTag();
        GlobalLifeStore.write(tag, history, 4L);

        List<DeathRecord> back = new ArrayList<>();
        long nextId = GlobalLifeStore.read(tag, back);

        assertEquals(3, back.size());
        assertEquals(4L, nextId);
        assertEquals(2L, back.get(1).id());
        assertEquals(uuid(2), back.get(1).uuid());
        assertEquals("P2", back.get(1).name());
        assertEquals(25, back.get(1).carriage());
        assertEquals("b", back.get(1).snapshot().getString("Marker"));
        assertEquals(-40, back.get(2).carriage());
    }

    @Test
    void writeReadRoundTripPreservesFriendSnapshots() {
        CompoundTag alice = snap("alice");
        alice.putString(GlobalLifeStore.FRIEND_LABEL_KEY, "Alice");
        CompoundTag bob = snap("bob");
        bob.putString(GlobalLifeStore.FRIEND_LABEL_KEY, "Bob");
        DeathRecord hosted = new DeathRecord(1, uuid(1), "P1", 0, snap("host"), List.of(alice, bob));
        DeathRecord lonely = new DeathRecord(2, uuid(2), "P2", 3, snap("solo")); // 5-arg ctor -> no friends

        CompoundTag tag = new CompoundTag();
        GlobalLifeStore.write(tag, List.of(hosted, lonely), 3L);
        List<DeathRecord> back = new ArrayList<>();
        GlobalLifeStore.read(tag, back);

        List<CompoundTag> friends = back.get(0).friendSnapshots();
        assertEquals(2, friends.size());
        assertEquals("Alice", friends.get(0).getString(GlobalLifeStore.FRIEND_LABEL_KEY));
        assertEquals("bob", friends.get(1).getString("Marker"));
        // No friends -> no "Friends" tag written -> reads back empty (the path an older lives.dat takes).
        assertTrue(back.get(1).friendSnapshots().isEmpty());
    }

    @Test
    void readLegacyLivesFormatMigratesWithUnknownCarriage() {
        // Pre-death-log global format: one snapshot per player under "Lives", no ids/carriage.
        CompoundTag tag = new CompoundTag();
        ListTag lives = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putUUID("UUID", uuid(7));
        e.putString("Name", "Old");
        e.put("Snapshot", snap("legacy"));
        lives.add(e);
        tag.put("Lives", lives);

        List<DeathRecord> back = new ArrayList<>();
        long nextId = GlobalLifeStore.read(tag, back);

        assertEquals(1, back.size());
        assertEquals(NONE, back.get(0).carriage());
        assertEquals("legacy", back.get(0).snapshot().getString("Marker"));
        assertTrue(back.get(0).id() >= 1L);
        assertTrue(nextId > back.get(0).id());
    }

    // ---- carriage-band eligibility (pure filter; the weighted pick lives in the registry) ----

    @Test
    void eligibleFiltersByBandAndUnknownCarriage() {
        List<DeathRecord> history = List.of(
            rec(1, 1, 0, "here"),
            rec(2, 2, 25, "near"),
            rec(3, 3, 31, "tooFar"),      // |31-0| = 31 > 30
            rec(4, 4, -30, "edge"),       // |-30-0| = 30 == radius -> in
            rec(5, 5, NONE, "offTrain")); // unknown carriage -> excluded

        List<DeathRecord> band = GlobalLifeStore.eligible(history, 0, 30);
        assertEquals(List.of(1L, 2L, 4L), band.stream().map(DeathRecord::id).toList());
    }

    // ---- query accessors consumed by the reincarnation source registry ----

    @Test
    void recordsInBandUsesTheDepthBandAndExcludesUnknownCarriage() {
        GlobalLifeStore store = new GlobalLifeStore();
        store.append(uuid(1), "A", 0, snap("here"));
        store.append(uuid(2), "B", 30, snap("edge"));   // |30-0| == radius -> in
        store.append(uuid(3), "C", 31, snap("tooFar")); // out of band
        store.append(uuid(4), "D", NONE, snap("offTrain"));

        assertEquals(List.of("here", "edge"),
            store.recordsInBand(0).stream().map(r -> r.snapshot().getString("Marker")).toList());
        // No spawn carriage -> nothing to compare depth against.
        assertTrue(store.recordsInBand(NONE).isEmpty());
    }

    @Test
    void recordsForPlayerReturnsThatPlayersLivesOldestFirst() {
        GlobalLifeStore store = new GlobalLifeStore();
        store.append(uuid(1), "A", 0, snap("a1"));
        store.append(uuid(2), "B", 0, snap("b"));
        store.append(uuid(1), "A", 0, snap("a2"));

        assertEquals(List.of("a1", "a2"),
            store.recordsForPlayer(uuid(1)).stream().map(r -> r.snapshot().getString("Marker")).toList());
        assertTrue(store.recordsForPlayer(uuid(42)).isEmpty());
    }

    @Test
    void allRecordsAndRecentExposeTheLogOldestAndNewestFirst() {
        GlobalLifeStore store = new GlobalLifeStore();
        store.append(uuid(1), "A", 0, snap("a"));
        store.append(uuid(2), "B", 0, snap("b"));
        store.append(uuid(3), "C", 0, snap("c"));

        assertEquals(List.of("a", "b", "c"),
            store.allRecords().stream().map(r -> r.snapshot().getString("Marker")).toList());
        assertEquals(List.of("c", "b"),
            store.recent(2).stream().map(r -> r.snapshot().getString("Marker")).toList());
        assertEquals(3, store.recent(10).size()); // limit beyond size -> whole log
    }

    @Test
    void mostRecentForPlayerReturnsLatestDeath() {
        GlobalLifeStore store = new GlobalLifeStore();
        store.append(uuid(1), "A", 0, snap("first"));
        store.append(uuid(2), "B", 0, snap("other"));
        store.append(uuid(1), "A", 0, snap("second"));
        assertEquals("second", store.mostRecentForPlayer(uuid(1)).getString("Marker"));
        assertTrue(store.hasAnyForPlayer(uuid(1)));
        assertNull(store.mostRecentForPlayer(uuid(42)));
    }
}
