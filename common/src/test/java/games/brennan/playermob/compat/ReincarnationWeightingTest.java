package games.brennan.playermob.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure oracle for {@link ReincarnationWeighting}: the position-based recency normalisation, the
 * carriage-proximity tilt, and the recency-dominated weighted pick. No world (only
 * {@link CompoundTag}/{@link RandomSource}); statistical assertions use a fixed seed.
 */
class ReincarnationWeightingTest {

    private static ReincarnationRecord rec(String key, int carriage) {
        return new ReincarnationRecord("s", key, new UUID(0L, 0L), key, carriage, "", new CompoundTag(), List.of());
    }

    // ---- recency normalisation ----

    @Test
    void recencyNormPutsNewestAtOne() {
        assertEquals(1.0, ReincarnationWeighting.recencyNorm(0, 1));
        assertEquals(0.5, ReincarnationWeighting.recencyNorm(0, 2));
        assertEquals(1.0, ReincarnationWeighting.recencyNorm(1, 2));
        assertEquals(0.0, ReincarnationWeighting.recencyNorm(0, 0)); // empty guard
    }

    // ---- carriage proximity ----

    @Test
    void proximityIsNeutralExceptForCarriageRequestsWithAKnownCarriage() {
        assertEquals(1.0, ReincarnationWeighting.proximity(50, ReincarnationQuery.any(null)));
        assertEquals(1.0, ReincarnationWeighting.proximity(50, ReincarnationQuery.byPlayer(new UUID(0L, 1L), null)));
        assertEquals(1.0, ReincarnationWeighting.proximity(
            TrainConfinement.NO_CARRIAGE, ReincarnationQuery.byCarriage(0, null)));
    }

    @Test
    void proximityFallsOffWithCarriageDistance() {
        ReincarnationQuery q = ReincarnationQuery.byCarriage(0, null);
        assertEquals(1.0, ReincarnationWeighting.proximity(0, q));
        assertTrue(ReincarnationWeighting.proximity(0, q) > ReincarnationWeighting.proximity(10, q));
        assertTrue(ReincarnationWeighting.proximity(10, q) > ReincarnationWeighting.proximity(30, q));
        // ...but only mildly: even at the band edge it stays well above half.
        assertTrue(ReincarnationWeighting.proximity(30, q) > 0.5);
    }

    // ---- weighted pick ----

    @Test
    void chooseReturnsNullWhenNothingIsEligible() {
        RandomSource rng = RandomSource.create(1);
        assertNull(ReincarnationWeighting.choose(List.of(), ReincarnationQuery.any(null), rng));
        assertNull(ReincarnationWeighting.choose(List.of(List.of()), ReincarnationQuery.any(null), rng));
    }

    @Test
    void chooseStronglyFavoursRecentWithinASource() {
        // One source, oldest -> newest. recency^3 weights: old≈0.037, mid≈0.296, new=1.0.
        List<ReincarnationRecord> group = List.of(rec("old", 0), rec("mid", 0), rec("new", 0));
        ReincarnationQuery q = ReincarnationQuery.any(null);
        RandomSource rng = RandomSource.create(42);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            counts.merge(ReincarnationWeighting.choose(List.of(group), q, rng).key(), 1, Integer::sum);
        }
        assertTrue(counts.getOrDefault("new", 0) > counts.getOrDefault("mid", 0),
            "newest should dominate: " + counts);
        assertTrue(counts.getOrDefault("mid", 0) > counts.getOrDefault("old", 0),
            "older lives still surface but less: " + counts);
    }

    @Test
    void chooseTreatsNewestOfEachSourceAsEquallyRecent() {
        // Two sources each offering one (newest) life at the same carriage -> ~even split, so an
        // external source competes with the built-in log on recency, not on how much history it has.
        ReincarnationQuery q = ReincarnationQuery.byCarriage(0, null);
        RandomSource rng = RandomSource.create(99);
        int a = 0;
        for (int i = 0; i < 3000; i++) {
            if ("a".equals(ReincarnationWeighting.choose(
                    List.of(List.of(rec("a", 0)), List.of(rec("b", 0))), q, rng).key())) {
                a++;
            }
        }
        assertTrue(a > 1200 && a < 1800, "expected a roughly even split, got " + a + "/3000");
    }

    @Test
    void chooseMildlyPrefersNearerCarriageAtEqualRecency() {
        // Both are the newest of their own source (recency 1.0); only carriage distance differs.
        ReincarnationQuery q = ReincarnationQuery.byCarriage(0, null);
        RandomSource rng = RandomSource.create(7);
        int near = 0;
        for (int i = 0; i < 3000; i++) {
            if ("near".equals(ReincarnationWeighting.choose(
                    List.of(List.of(rec("near", 0)), List.of(rec("far", 30))), q, rng).key())) {
                near++;
            }
        }
        // near weight 1.0 vs far ≈0.625 -> near ≈61.5% : a clear but not overwhelming preference.
        assertTrue(near > 1500, "nearer carriage should be preferred, got " + near);
        assertTrue(near < 2700, "but only mildly — far lives still surface, got " + near);
    }
}
