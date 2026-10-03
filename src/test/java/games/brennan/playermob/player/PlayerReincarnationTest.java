package games.brennan.playermob.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracles for the two selections a death makes: {@link PlayerReincarnation#topIndices}
 * (which loved-one PlayerMobs are logged as friend-echoes) and
 * {@link PlayerReincarnation#petOrder} / {@link PlayerReincarnation#returnsWithEcho} (which tamed
 * animals come back with an echo). No Minecraft world, like {@link GlobalLifeStoreTest}; the world
 * scans that feed them are exercised in-game.
 */
class PlayerReincarnationTest {

    @Test
    void topIndicesPicksHighestWithinCap() {
        float[] scores = {7.0F, 9.5F, 8.0F, 7.5F, 10.0F};
        // highest first: 10.0 (idx 4), 9.5 (idx 1), 8.0 (idx 2)
        assertArrayEquals(new int[] {4, 1, 2}, PlayerReincarnation.topIndices(scores, 3));
    }

    @Test
    void topIndicesCapsAtArrayLength() {
        assertArrayEquals(new int[] {0}, PlayerReincarnation.topIndices(new float[] {7.0F}, 4));
        assertEquals(0, PlayerReincarnation.topIndices(new float[] {}, 4).length);
    }

    @Test
    void topIndicesBreaksTiesByAscendingIndex() {
        float[] scores = {8.0F, 8.0F, 8.0F};
        assertArrayEquals(new int[] {0, 1}, PlayerReincarnation.topIndices(scores, 2));
    }

    // ---- which pets come back with an echo ----

    @Test
    void unnamedMountsStayBehindButNamedOnesReturn() {
        assertFalse(PlayerReincarnation.returnsWithEcho(true, false));  // a horse you never named
        assertTrue(PlayerReincarnation.returnsWithEcho(true, true));    // a horse you did
        assertTrue(PlayerReincarnation.returnsWithEcho(false, false));  // an unnamed wolf is still a pet
        assertTrue(PlayerReincarnation.returnsWithEcho(false, true));
    }

    @Test
    void petOrderPrefersNamedThenNearest() {
        boolean[] named = {false, true, false, true};
        boolean[] alive = {true, true, true, true};
        long[] lastSeen = {100, 100, 100, 100};
        double[] distanceSqr = {1.0, 90.0, 4.0, 25.0};
        // named first (idx 3 at 25 beats idx 1 at 90), then the nearest unnamed (idx 0).
        assertArrayEquals(new int[] {3, 1, 0},
            PlayerReincarnation.petOrder(named, alive, lastSeen, distanceSqr, 3));
    }

    @Test
    void petOrderRanksNamedOverLivingOverRecent() {
        // 0: unnamed, alive, seen now      1: named, dead, seen long ago
        // 2: unnamed, dead, seen now       3: unnamed, alive, seen long ago (left at base)
        boolean[] named = {false, true, false, false};
        boolean[] alive = {true, false, false, true};
        long[] lastSeen = {500, 10, 500, 10};
        double[] distanceSqr = {4.0, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        // a named pet beats everything, even dead; then the living — most recently seen first.
        assertArrayEquals(new int[] {1, 0, 3},
            PlayerReincarnation.petOrder(named, alive, lastSeen, distanceSqr, 3));
    }

    @Test
    void petOrderCapsAndBreaksTiesByAscendingIndex() {
        boolean[] named = {false, false, false};
        boolean[] alive = {true, true, true};
        long[] lastSeen = {7, 7, 7};
        double[] distanceSqr = {5.0, 5.0, 5.0};
        assertArrayEquals(new int[] {0, 1},
            PlayerReincarnation.petOrder(named, alive, lastSeen, distanceSqr, 2));
        assertEquals(0, PlayerReincarnation.petOrder(
            new boolean[] {}, new boolean[] {}, new long[] {}, new double[] {}, 3).length);
    }
}
