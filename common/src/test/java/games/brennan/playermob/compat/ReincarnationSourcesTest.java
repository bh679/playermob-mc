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
        return new ReincarnationRecord(source, key, new UUID(0L, 0L), key, carriage, "", new CompoundTag(), List.of());
    }

    /** A source that returns a fixed candidate list (ignoring server/query). */
    private static ReincarnationSource source(ReincarnationRecord... records) {
        List<ReincarnationRecord> list = List.of(records);
        return (server, query) -> list;
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
            seenSources.add(ReincarnationSources.pickFrom(List.of(a, b), NO_SERVER, q, Set.of(), rng).sourceId());
        }
        assertEquals(Set.of("a", "b"), seenSources, "both sources should surface over many draws");
    }

    @Test
    void pickFromReturnsNullWithNoSources() {
        assertNull(ReincarnationSources.pickFrom(List.of(), NO_SERVER,
            ReincarnationQuery.any(null), Set.of(), RandomSource.create(1)));
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
                List.of(s), NO_SERVER, q, seen, RandomSource.create(seed));
            assertEquals("old", pick.key(), "the met life must never be picked");
        }
    }

    @Test
    void pickFromReturnsNullWhenEverythingIsMet() {
        ReincarnationSource s = source(rec("playermob", "x", 0), rec("playermob", "y", 0));
        Set<String> seen = Set.of("playermob:x", "playermob:y");
        assertNull(ReincarnationSources.pickFrom(List.of(s), NO_SERVER,
            ReincarnationQuery.any(null), seen, RandomSource.create(1)));
    }

    // ---- resilience ----

    @Test
    void pickFromIsolatesAFailingSource() {
        ReincarnationSource boom = (server, query) -> {
            throw new IllegalStateException("source blew up");
        };
        ReincarnationSource ok = source(rec("ok", "1", 0));
        ReincarnationRecord pick = ReincarnationSources.pickFrom(
            List.of(boom, ok), NO_SERVER, ReincarnationQuery.any(null), Set.of(), RandomSource.create(3));
        assertEquals("ok", pick.sourceId(), "a throwing source is skipped, not fatal");
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
