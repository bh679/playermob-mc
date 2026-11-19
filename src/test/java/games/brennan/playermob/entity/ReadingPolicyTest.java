package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingPolicyTest {

    @Test
    void readingZeroNeverReads() {
        assertEquals(ReadingPolicy.NEVER, ReadingPolicy.gapTicks(0, 0.5));
        assertEquals(ReadingPolicy.NEVER, ReadingPolicy.gapTicks(-3, 0.5));
    }

    @Test
    void bookishMobsReadMoreOften() {
        int prev = Integer.MAX_VALUE;
        for (int reading = 1; reading <= 10; reading++) {
            int gap = ReadingPolicy.gapTicks(reading, 0.5);
            assertTrue(gap < prev, "gap shrinks as reading rises");
            prev = gap;
        }
        assertEquals(ReadingPolicy.GAP_SECONDS_AT_MAX * 20, ReadingPolicy.gapTicks(10, 0.5));
        assertEquals(ReadingPolicy.GAP_SECONDS_AT_MAX * 20 * 10, ReadingPolicy.gapTicks(1, 0.5));
    }

    @Test
    void gapIsSpreadAroundTheMean() {
        int mean = ReadingPolicy.gapTicks(10, 0.5);
        assertEquals(mean / 2, ReadingPolicy.gapTicks(10, 0.0));
        assertEquals(mean * 3 / 2, ReadingPolicy.gapTicks(10, 1.0));
    }

    @Test
    void readLengthFollowsFocus() {
        assertEquals(ReadingPolicy.MIN_READ_TICKS, ReadingPolicy.readTicks(0, 0.5));
        assertEquals(ReadingPolicy.MAX_READ_TICKS, ReadingPolicy.readTicks(10, 0.5));
        int prev = -1;
        for (int focus = 0; focus <= 10; focus++) {
            int t = ReadingPolicy.readTicks(focus, 0.5);
            assertTrue(t > prev, "longer reads as focus rises");
            prev = t;
        }
    }

    @Test
    void chanceSpreadsAReadByAQuarter() {
        int mean = ReadingPolicy.readTicks(10, 0.5);
        assertEquals(Math.round(mean * 0.75), ReadingPolicy.readTicks(10, 0.0));
        assertEquals(Math.round(mean * 1.25), ReadingPolicy.readTicks(10, 1.0));
        assertTrue(ReadingPolicy.readTicks(0, 0.0) > 0);
    }
}
