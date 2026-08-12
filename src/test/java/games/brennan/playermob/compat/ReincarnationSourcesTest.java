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

    /** A life whose snapshot carries the Free Play provenance flag ({@link FreePlayQuery#SNAPSHOT_TAG}). */
    private static ReincarnationRecord recFP(String source, String key, int carriage, boolean freePlay) {
        CompoundTag snap = new CompoundTag();
        snap.putBoolean(FreePlayQuery.SNAPSHOT_TAG, freePlay);
        return new ReincarnationRecord(source, key, new UUID(0L, 0L), key, carriage, "", snap, List.of());
    }

    private static boolean pickedFreePlay(ReincarnationRecord r) {
        return NbtCompat.getBooleanOr(r.snapshot(), FreePlayQuery.SNAPSHOT_TAG, false);
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

    // ---- Free Play provenance match (query.matchFreePlay) ----

    @Test
    void matchFreePlayTrueKeepsOnlyFreePlayLives() {
        // Mixed pool; a Free Play player (matchFreePlay=TRUE) must only ever embody a Free Play life.
        ReincarnationSource s = source(
            recFP("dp", "legit1", 0, false), recFP("dp", "legit2", 0, false),
            recFP("dp", "fp1", 0, true), recFP("dp", "fp2", 0, true));
        ReincarnationQuery q = ReincarnationQuery.any(null).withMatchFreePlay(true);
        for (int seed = 0; seed < 50; seed++) {
            ReincarnationRecord pick = ReincarnationSources.pickFrom(
                List.of(s), NO_SERVER, q, Set.of(), null, RandomSource.create(seed));
            assertTrue(pickedFreePlay(pick), "a Free Play player must never embody a legit life");
        }
    }

    @Test
    void matchFreePlayFalseKeepsOnlyLegitLives() {
        ReincarnationSource s = source(
            recFP("dp", "legit1", 0, false), recFP("dp", "legit2", 0, false),
            recFP("dp", "fp1", 0, true), recFP("dp", "fp2", 0, true));
        ReincarnationQuery q = ReincarnationQuery.any(null).withMatchFreePlay(false);
        for (int seed = 0; seed < 50; seed++) {
            ReincarnationRecord pick = ReincarnationSources.pickFrom(
                List.of(s), NO_SERVER, q, Set.of(), null, RandomSource.create(seed));
            assertTrue(!pickedFreePlay(pick), "a legit player must never embody a Free Play life");
        }
    }

    @Test
    void matchFreePlayReturnsNullWhenNoLifeMatches() {
        // A Free Play player, but every stored life is legit -> nothing eligible -> stay a fresh mob.
        ReincarnationSource s = source(recFP("dp", "legit1", 0, false), recFP("dp", "legit2", 0, false));
        ReincarnationQuery q = ReincarnationQuery.any(null).withMatchFreePlay(true);
        assertNull(ReincarnationSources.pickFrom(List.of(s), NO_SERVER, q, Set.of(), null, RandomSource.create(1)));
    }

    @Test
    void nullMatchFreePlayDoesNotFilter() {
        // No provenance requested (dev-build bypass / no consumer): neither kind is filtered out — a
        // legit-only pool and a Free-Play-only pool each still yield their (only) life.
        ReincarnationQuery q = ReincarnationQuery.any(null); // matchFreePlay == null
        ReincarnationRecord fromFp = ReincarnationSources.pickFrom(
            List.of(source(recFP("dp", "fp", 0, true))), NO_SERVER, q, Set.of(), null, RandomSource.create(1));
        ReincarnationRecord fromLegit = ReincarnationSources.pickFrom(
            List.of(source(recFP("dp", "legit", 0, false))), NO_SERVER, q, Set.of(), null, RandomSource.create(1));
        assertTrue(fromFp != null && pickedFreePlay(fromFp), "a Free Play life is not filtered when no provenance is requested");
        assertTrue(fromLegit != null && !pickedFreePlay(fromLegit), "a legit life is not filtered when no provenance is requested");
    }

    // ---- difficulty partition (query.difficulty) ----

    /** A life lived on {@code difficulty} ({@code ""} = a record written before the partition existed). */
    private static ReincarnationRecord recDiff(String source, String key, String difficulty) {
        return new ReincarnationRecord(source, key, new UUID(0L, 0L), key, 0, "", difficulty,
            new CompoundTag(), List.of());
    }

    @Test
    void difficultyPartitionKeepsOnlyLivesFromThatDifficulty() {
        ReincarnationSource s = source(
            recDiff("dp", "hard1", "hard"), recDiff("dp", "hard2", "hard"),
            recDiff("dp", "peaceful", "peaceful"), recDiff("dp", "easy", "easy"));
        ReincarnationQuery q = ReincarnationQuery.any(null).withDifficulty("hard");
        for (int seed = 0; seed < 50; seed++) {
            ReincarnationRecord pick = ReincarnationSources.pickFrom(
                List.of(s), NO_SERVER, q, Set.of(), null, RandomSource.create(seed));
            assertEquals("hard", pick.partition(), "a Hard run must never meet another difficulty's echo");
        }
    }

    @Test
    void anUntaggedLifeIsOfferedOnNormalOnly() {
        // The whole pre-partition history: no difficulty recorded, so it belongs to Normal.
        ReincarnationSource s = source(recDiff("pm", "legacy", ""));
        assertEquals("pm:legacy", ReincarnationSources.pickFrom(
            List.of(s), NO_SERVER, ReincarnationQuery.any(null).withDifficulty("normal"),
            Set.of(), null, RandomSource.create(1)).id());
        assertNull(ReincarnationSources.pickFrom(
            List.of(s), NO_SERVER, ReincarnationQuery.any(null).withDifficulty("hard"),
            Set.of(), null, RandomSource.create(1)));
    }

    @Test
    void aRemotePoolThatIgnoredTheFilterIsStillPartitionedLocally() {
        // An older relay drops the difficulty parameter and returns the whole band. The local filter is
        // the backstop that keeps another difficulty's echoes out regardless.
        ReincarnationSource stale = source(recDiff("dp", "peaceful", "peaceful"), recDiff("dp", "hard", "hard"));
        ReincarnationRecord pick = ReincarnationSources.pickFrom(
            List.of(stale), NO_SERVER, ReincarnationQuery.any(null).withDifficulty("hard"),
            Set.of(), null, RandomSource.create(3));
        assertEquals("dp:hard", pick.id());
    }

    @Test
    void noDifficultyRequestedDoesNotFilter() {
        // Isolation off (or no difficulty readable): every partition is eligible, as before. Asserted
        // one pool at a time — with both in one pool the recency weighting would almost always return
        // the newer life, which says nothing about filtering.
        ReincarnationQuery q = ReincarnationQuery.any(null).withDifficulty(null);
        assertEquals("dp:hard", ReincarnationSources.pickFrom(
            List.of(source(recDiff("dp", "hard", "hard"))), NO_SERVER, q, Set.of(), null,
            RandomSource.create(1)).id());
        assertEquals("dp:peaceful", ReincarnationSources.pickFrom(
            List.of(source(recDiff("dp", "peaceful", "peaceful"))), NO_SERVER, q, Set.of(), null,
            RandomSource.create(1)).id());
    }

    @Test
    void withDifficultyTreatsBlankAsNoFilterAndKeepsTheFreePlayMatch() {
        ReincarnationQuery q = ReincarnationQuery.any(null).withMatchFreePlay(true).withDifficulty("");
        assertNull(q.difficulty(), "a blank partition is no partition");
        assertEquals(Boolean.TRUE, q.matchFreePlay(), "the provenance match survives a partition change");
        assertEquals("hard", q.withDifficulty("hard").difficulty());
        assertEquals(Boolean.TRUE, q.withDifficulty("hard").withMatchFreePlay(true).matchFreePlay());
    }
}
