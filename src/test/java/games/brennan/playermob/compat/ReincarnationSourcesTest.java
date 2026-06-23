package games.brennan.playermob.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle for {@link ReincarnationSources}' pure aggregation cores ({@code pickFrom}/{@code recentFrom}):
 * cross-source pooling, the already-met de-dup, failure isolation, and the read cap. The static
 * registry/session plumbing on top is exercised in-game; here the helpers are driven directly with
 * stub sources and an explicit met-set, so there is no shared global state.
 */
class ReincarnationSourcesTest {

    private static final MinecraftServer NO_SERVER = null; // stub sources ignore it

    private static ReincarnationRecord rec(String source, String key, int carriage) {
        return rec(source, key, carriage, new UUID(0L, 0L));
    }

    private static ReincarnationRecord rec(String source, String key, int carriage, UUID player) {
        return new ReincarnationRecord(source, key, player, key, carriage, "", new CompoundTag(), List.of());
    }

    /** A remote source (the default kind) that returns a fixed candidate list (ignoring server/query). */
    private static ReincarnationSource source(ReincarnationRecord... records) {
        List<ReincarnationRecord> list = List.of(records);
        return (server, query) -> list;
    }

    /** A local source (e.g. the built-in death log) returning a fixed candidate list. */
    private static ReincarnationSource localSource(ReincarnationRecord... records) {
        List<ReincarnationRecord> list = List.of(records);
        return new ReincarnationSource() {
            @Override
            public List<ReincarnationRecord> candidates(MinecraftServer server, ReincarnationQuery query) {
                return list;
            }

            @Override
            public boolean remote() {
                return false;
            }
        };
    }

    // ---- cross-source pooling ----

    @Test
    void pickFromDrawsFromEverySource() {
        ReincarnationSource a = source(rec("a", "1", 0));
        ReincarnationSource b = source(rec("b", "1", 0));
        ReincarnationQuery q = ReincarnationQuery.byCarriage(0, null);

        Set<String> seenSources = new HashSet<>();
        RandomSource rng = RandomSource.create(5);
        for (int i = 0; i < 500; i++) {
            seenSources.add(ReincarnationSources.pickFrom(List.of(a, b), NO_SERVER, q, Set.of(), null, rng).sourceId());
        }
        assertEquals(Set.of("a", "b"), seenSources, "both sources should surface over many draws");
    }

    @Test
    void pickFromReturnsNullWithNoSources() {
        assertNull(ReincarnationSources.pickFrom(List.of(), NO_SERVER,
            ReincarnationQuery.any(null), Set.of(), null, RandomSource.create(1)));
    }

    // ---- already-met de-dup ----

    @Test
    void pickFromSkipsRecordsTheOwnerHasAlreadyMet() {
        // One source, two lives; the newest is already met -> the older one is returned instead.
        ReincarnationSource s = source(rec("playermob", "old", 0), rec("playermob", "new", 0));
        ReincarnationQuery q = ReincarnationQuery.any(null);
        Set<String> seen = Set.of("playermob:new");

        for (int seed = 0; seed < 20; seed++) { // newest is heavily favoured, so prove it's truly excluded
            ReincarnationRecord pick = ReincarnationSources.pickFrom(
                List.of(s), NO_SERVER, q, seen, null, RandomSource.create(seed));
            assertEquals("old", pick.key(), "the met life must never be picked");
        }
    }

    @Test
    void pickFromReturnsNullWhenEverythingIsMet() {
        ReincarnationSource s = source(rec("playermob", "x", 0), rec("playermob", "y", 0));
        Set<String> seen = Set.of("playermob:x", "playermob:y");
        assertNull(ReincarnationSources.pickFrom(List.of(s), NO_SERVER,
            ReincarnationQuery.any(null), seen, null, RandomSource.create(1)));
    }

    // ---- per-band cap (only the newest few are ever eligible) ----

    @Test
    void pickFromConsidersOnlyTheNewestFewPerSource() {
        // 8 lives oldest->newest; only the newest MAX_CANDIDATES_PER_BAND should ever be picked.
        ReincarnationRecord[] lives = new ReincarnationRecord[8];
        for (int i = 0; i < lives.length; i++) {
            lives[i] = rec("dp", Integer.toString(i), 0);
        }
        ReincarnationSource s = source(lives);
        ReincarnationQuery q = ReincarnationQuery.byCarriage(0, null);

        Set<String> picked = new HashSet<>();
        for (int seed = 0; seed < 400; seed++) {
            picked.add(ReincarnationSources.pickFrom(
                List.of(s), NO_SERVER, q, Set.of(), null, RandomSource.create(seed)).key());
        }
        int kept = ReincarnationSources.MAX_CANDIDATES_PER_BAND;
        for (int i = 0; i < lives.length - kept; i++) { // oldest (8 - 5 = 3) must never surface
            assertTrue(!picked.contains(Integer.toString(i)), "older-than-newest-" + kept + " life " + i + " leaked in");
        }
        assertTrue(picked.contains(Integer.toString(lives.length - 1)), "the newest life should still surface");
    }

    // ---- resilience ----

    @Test
    void pickFromIsolatesAFailingSource() {
        ReincarnationSource boom = (server, query) -> {
            throw new IllegalStateException("source blew up");
        };
        ReincarnationSource ok = source(rec("ok", "1", 0));
        ReincarnationRecord pick = ReincarnationSources.pickFrom(
            List.of(boom, ok), NO_SERVER, ReincarnationQuery.any(null), Set.of(), null, RandomSource.create(3));
        assertEquals("ok", pick.sourceId(), "a throwing source is skipped, not fatal");
    }

    // ---- local vs remote kind + self-exclusion ----

    @Test
    void filterByKindSplitsLocalAndRemote() {
        ReincarnationSource local = localSource(rec("playermob", "1", 0));
        ReincarnationSource remote = source(rec("dp", "1", 0));
        List<ReincarnationSource> all = List.of(local, remote);

        assertEquals(List.of(remote), ReincarnationSources.filterByKind(all, true), "remote kind");
        assertEquals(List.of(local), ReincarnationSources.filterByKind(all, false), "local kind");
    }

    @Test
    void pickFromExcludesTheGivenPlayer() {
        // Remote self-exclusion: a remote pool of two lives — one is the live player's own — must
        // never return that player's life when excludePlayer is set.
        UUID me = new UUID(0L, 7L);
        UUID other = new UUID(0L, 8L);
        ReincarnationSource remote = source(
            rec("dp", "mine", 0, me), rec("dp", "theirs", 0, other));
        ReincarnationQuery q = ReincarnationQuery.byCarriage(0, me);

        for (int seed = 0; seed < 20; seed++) {
            ReincarnationRecord pick = ReincarnationSources.pickFrom(
                List.of(remote), NO_SERVER, q, Set.of(), me, RandomSource.create(seed));
            assertEquals("theirs", pick.key(), "a remote echo must never be the live player themselves");
        }
        // With only the player's own life available, the remote pool yields nothing.
        ReincarnationSource onlyMine = source(rec("dp", "mine", 0, me));
        assertNull(ReincarnationSources.pickFrom(
            List.of(onlyMine), NO_SERVER, q, Set.of(), me, RandomSource.create(1)));
    }

    // ---- read aggregation + cap ----

    @Test
    void recentFromAggregatesAcrossSourcesAndCaps() {
        ReincarnationSource a = new ReincarnationSource() {
            @Override
            public List<ReincarnationRecord> candidates(MinecraftServer server, ReincarnationQuery query) {
                return List.of();
            }

            @Override
            public List<ReincarnationRecord> recent(MinecraftServer server, int limit) {
                return List.of(rec("a", "1", 0), rec("a", "2", 0));
            }
        };
        ReincarnationSource b = new ReincarnationSource() {
            @Override
            public List<ReincarnationRecord> candidates(MinecraftServer server, ReincarnationQuery query) {
                return List.of();
            }

            @Override
            public List<ReincarnationRecord> recent(MinecraftServer server, int limit) {
                return List.of(rec("b", "1", 0), rec("b", "2", 0));
            }
        };

        assertEquals(4, ReincarnationSources.recentFrom(List.of(a, b), NO_SERVER, 10).size());
        assertEquals(3, ReincarnationSources.recentFrom(List.of(a, b), NO_SERVER, 3).size(), "capped to the limit");
        assertTrue(ReincarnationSources.recentFrom(List.of(a, b), NO_SERVER, 0).isEmpty());
    }
}
