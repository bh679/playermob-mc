package games.brennan.playermob.player;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.player.GlobalLifeStore.DeathRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link GlobalLifeStore}: the death-log NBT round-trip, the
 * carriage-band eligibility filter, the recency-weighted pick, and the one-echo-per-life
 * session behaviour. No Minecraft world (only {@link CompoundTag}/{@link UUID}/
 * {@link RandomSource}), like {@code PlayerLifeRecordTest}; the filesystem path is exercised
 * in-game.
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

    // ---- carriage-band eligibility ----

    @Test
    void eligibleFiltersByBandUsedAndUnknownCarriage() {
        List<DeathRecord> history = List.of(
            rec(1, 1, 0, "here"),
            rec(2, 2, 25, "near"),
            rec(3, 3, 31, "tooFar"),      // |31-0| = 31 > 30
            rec(4, 4, -30, "edge"),       // |-30-0| = 30 == radius -> in
            rec(5, 5, NONE, "offTrain")); // unknown carriage -> excluded

        List<DeathRecord> band = GlobalLifeStore.eligible(history, 0, 30, Collections.emptySet());
        assertEquals(List.of(1L, 2L, 4L), band.stream().map(DeathRecord::id).toList());

        // Exclude one already met this life.
        List<DeathRecord> minusUsed = GlobalLifeStore.eligible(history, 0, 30, Set.of(2L));
        assertEquals(List.of(1L, 4L), minusUsed.stream().map(DeathRecord::id).toList());
    }

    // ---- recency-weighted pick ----

    @Test
    void weightedIndexBoundaries() {
        // n=3 -> weights {1,2,3}, total 6: roll 0->0, {1,2}->1, {3,4,5}->2.
        int[] expected = {0, 1, 1, 2, 2, 2};
        for (int roll = 0; roll < expected.length; roll++) {
            assertEquals(expected[roll], GlobalLifeStore.weightedIndex(3, roll), "roll " + roll);
        }
        assertEquals(0, GlobalLifeStore.weightedIndex(1, 0));
    }

    @Test
    void pickWeightedNewerHandlesEmptyAndSingle() {
        assertNull(GlobalLifeStore.pickWeightedNewer(List.of(), RandomSource.create(1)));
        DeathRecord only = rec(9, 9, 0, "solo");
        assertEquals(only, GlobalLifeStore.pickWeightedNewer(List.of(only), RandomSource.create(2)));
    }

    @Test
    void pickWeightedNewerFavoursNewer() {
        List<DeathRecord> band = List.of(rec(1, 1, 0, "old"), rec(2, 2, 0, "new")); // oldest first
        RandomSource rng = RandomSource.create(123);
        int newer = 0;
        for (int i = 0; i < 600; i++) {
            if ("new".equals(GlobalLifeStore.pickWeightedNewer(band, rng).snapshot().getString("Marker"))) {
                newer++;
            }
        }
        // weights 1:2 -> newer ~2/3; comfortably the majority.
        assertTrue(newer > 360, "expected newer favoured (~400/600), got " + newer);
    }

    // ---- one echo per life + reset (stateful, in-memory store) ----

    @Test
    void oneEchoPerLifeThenSessionResetReopensAll() {
        GlobalLifeStore store = new GlobalLifeStore(); // in-memory (no file)
        store.append(uuid(1), "A", 0, snap("a"));
        store.append(uuid(2), "B", 5, snap("b"));
        store.append(uuid(3), "C", -10, snap("c"));
        UUID owner = uuid(99);
        RandomSource rng = RandomSource.create(7);

        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            DeathRecord pick = store.pickEchoFor(owner, 0, rng);
            assertNotNull(pick, "pick " + i + " should be eligible");
            assertTrue(seen.add(pick.id()), "echoes must not repeat within a life");
        }
        assertNull(store.pickEchoFor(owner, 0, rng), "all three met this life -> no more echoes");

        store.resetSession(owner);
        assertNotNull(store.pickEchoFor(owner, 0, rng), "after reset all lives are available again");
    }

    @Test
    void unresolvedSpawnCarriageYieldsNoEcho() {
        GlobalLifeStore store = new GlobalLifeStore();
        store.append(uuid(1), "A", 0, snap("a"));
        assertNull(store.pickEchoFor(uuid(99), NONE, RandomSource.create(1)));
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
