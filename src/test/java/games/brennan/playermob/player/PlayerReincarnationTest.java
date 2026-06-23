package games.brennan.playermob.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic oracle for {@link PlayerReincarnation#topIndices} — the most-loved selection that
 * decides which loved-one PlayerMobs a death logs as friend-echoes. No Minecraft world, like
 * {@link GlobalLifeStoreTest}; the world scan that feeds it is exercised in-game.
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
}
