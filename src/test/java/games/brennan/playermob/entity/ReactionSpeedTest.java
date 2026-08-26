package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link ReactionSpeed} — the curve anchors, the "reaction 5 behaves
 * exactly as the mod did before the trait existed" contract, monotonicity across the range,
 * and the guard that keeps a scaled cooldown from collapsing to zero and busy-looping a goal.
 * {@link RandomSource} needs a registry bootstrap, same as the other tests here.
 */
class ReactionSpeedTest {

    /** Enough samples that the distribution assertions below are stable, not flaky. */
    private static final int SAMPLES = 20_000;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- scale / ticks ----------------------------------------------------

    @Test
    void scaleAnchorsAtTheExtremesAndPivot() {
        assertEquals(2.0, ReactionSpeed.scale(0), 1e-9);
        assertEquals(1.0, ReactionSpeed.scale(5), 1e-9);
        assertEquals(0.5, ReactionSpeed.scale(10), 1e-9);
    }

    @Test
    void scaleIsSymmetricAboutThePivot() {
        for (int offset = 1; offset <= 5; offset++) {
            double slow = ReactionSpeed.scale(5 - offset);
            double fast = ReactionSpeed.scale(5 + offset);
            assertEquals(1.0, slow * fast, 1e-9,
                "reaction " + (5 - offset) + " and " + (5 + offset) + " should be reciprocal");
        }
    }

    @Test
    void scaleFallsMonotonicallyAsReactionRises() {
        for (int rs = 1; rs <= 10; rs++) {
            assertTrue(ReactionSpeed.scale(rs) < ReactionSpeed.scale(rs - 1),
                "scale should fall from reaction " + (rs - 1) + " to " + rs);
        }
    }

    @Test
    void scaleClampsOutOfRangeInput() {
        assertEquals(ReactionSpeed.scale(0), ReactionSpeed.scale(-7), 1e-9);
        assertEquals(ReactionSpeed.scale(10), ReactionSpeed.scale(99), 1e-9);
    }

    @Test
    void pivotLeavesFixedDelaysUntouched() {
        for (int base : new int[] {1, 5, 8, 10, 20, 40, 200}) {
            assertEquals(base, ReactionSpeed.ticks(5, base));
        }
    }

    @Test
    void ticksDoublesAtZeroAndHalvesAtTen() {
        assertEquals(80, ReactionSpeed.ticks(0, 40));
        assertEquals(20, ReactionSpeed.ticks(10, 40));
        assertEquals(40, ReactionSpeed.ticks(0, 20));
        assertEquals(10, ReactionSpeed.ticks(10, 20));
    }

    @Test
    void ticksNeverCollapsesAPositiveDelayToZero() {
        // A 1-tick base at maximum reaction would round to 0 and busy-loop its goal.
        assertEquals(1, ReactionSpeed.ticks(10, 1));
        assertEquals(2, ReactionSpeed.ticks(10, 3));
    }

    @Test
    void ticksPassesNonPositiveBasesThrough() {
        assertEquals(0, ReactionSpeed.ticks(0, 0));
        assertEquals(0, ReactionSpeed.ticks(10, 0));
        assertEquals(-1, ReactionSpeed.ticks(10, -1));
    }

    // ---- roll -------------------------------------------------------------

    @Test
    void skewIsTheReciprocalOfScale() {
        for (int rs = 0; rs <= 10; rs++) {
            assertEquals(1.0, ReactionSpeed.scale(rs) * ReactionSpeed.skew(rs), 1e-9);
        }
    }

    @Test
    void rollStaysInsideTheWindowAtEveryReactionSpeed() {
        RandomSource random = RandomSource.create(1234L);
        for (int rs = 0; rs <= 10; rs++) {
            for (int i = 0; i < SAMPLES; i++) {
                int value = ReactionSpeed.roll(rs, 5, 20, random);
                assertTrue(value >= 5 && value <= 20,
                    "roll " + value + " escaped [5,20] at reaction " + rs);
            }
        }
    }

    @Test
    void rollReachesBothEndpoints() {
        RandomSource random = RandomSource.create(99L);
        for (int rs : new int[] {0, 5, 10}) {
            boolean sawMin = false;
            boolean sawMax = false;
            for (int i = 0; i < SAMPLES; i++) {
                int value = ReactionSpeed.roll(rs, 5, 20, random);
                sawMin |= value == 5;
                sawMax |= value == 20;
            }
            assertTrue(sawMin, "reaction " + rs + " never rolled the window minimum");
            assertTrue(sawMax, "reaction " + rs + " never rolled the window maximum");
        }
    }

    @Test
    void rollAtThePivotIsUniformLikeTheNextIntItReplaces() {
        // The historic call was `min + random.nextInt(span + 1)` — a flat distribution over
        // 16 buckets, mean 12.5 for [5,20]. Anything else is a behaviour change at reaction 5.
        assertEquals(12.5, meanRoll(5, 5, 20), 0.1);

        int[] buckets = new int[16];
        RandomSource random = RandomSource.create(7L);
        for (int i = 0; i < SAMPLES; i++) {
            buckets[ReactionSpeed.roll(5, 5, 20, random) - 5]++;
        }
        double expected = SAMPLES / 16.0;
        for (int i = 0; i < buckets.length; i++) {
            assertTrue(Math.abs(buckets[i] - expected) < expected * 0.15,
                "bucket " + i + " (" + buckets[i] + ") is not flat around " + expected);
        }
    }

    @Test
    void rollSkewsLowForFastMobsAndHighForSlowOnes() {
        double slow = meanRoll(0, 5, 20);
        double neutral = meanRoll(5, 5, 20);
        double fast = meanRoll(10, 5, 20);

        assertEquals(15.0, slow, 0.3);
        assertEquals(12.5, neutral, 0.3);
        assertEquals(10.0, fast, 0.3);
        assertTrue(fast < neutral && neutral < slow, "means should order fast < neutral < slow");
    }

    @Test
    void rollMeanFallsMonotonicallyAsReactionRises() {
        double previous = Double.MAX_VALUE;
        for (int rs = 0; rs <= 10; rs++) {
            double mean = meanRoll(rs, 5, 20);
            assertTrue(mean < previous, "mean should fall at reaction " + rs);
            previous = mean;
        }
    }

    @Test
    void rollHandlesADegenerateWindow() {
        RandomSource random = RandomSource.create(3L);
        assertEquals(7, ReactionSpeed.roll(0, 7, 7, random));
        assertEquals(7, ReactionSpeed.roll(10, 7, 3, random));
    }

    /** Average of {@link #SAMPLES} rolls — the distribution assertions all lean on this. */
    private static double meanRoll(int reactionSpeed, int min, int max) {
        RandomSource random = RandomSource.create(42L);
        long total = 0;
        for (int i = 0; i < SAMPLES; i++) {
            total += ReactionSpeed.roll(reactionSpeed, min, max, random);
        }
        return (double) total / SAMPLES;
    }
}
