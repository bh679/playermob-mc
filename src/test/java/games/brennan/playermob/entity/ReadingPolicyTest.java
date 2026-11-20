package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingPolicyTest {

    @Test
    void passChanceRunsFromZeroToFiftyPercent() {
        assertEquals(0.0, ReadingPolicy.passChance(0), 1e-9);
        assertEquals(0.25, ReadingPolicy.passChance(5), 1e-9);
        assertEquals(0.5, ReadingPolicy.passChance(10), 1e-9);
        // Out-of-range traits clamp rather than exceed the bounds.
        assertEquals(0.0, ReadingPolicy.passChance(-4), 1e-9);
        assertEquals(0.5, ReadingPolicy.passChance(99), 1e-9);
        double prev = -1;
        for (int t = 0; t <= 10; t++) {
            double c = ReadingPolicy.passChance(t);
            assertTrue(c > prev && c <= ReadingPolicy.MAX_PASS_CHANCE);
            prev = c;
        }
    }

    @Test
    void lecternChanceRunsFromZeroToCertain() {
        assertEquals(0.0, ReadingPolicy.lecternChance(0), 1e-9);
        assertEquals(0.5, ReadingPolicy.lecternChance(5), 1e-9);
        assertEquals(1.0, ReadingPolicy.lecternChance(10), 1e-9);
        assertEquals(1.0, ReadingPolicy.lecternChance(42), 1e-9);
        assertEquals(0.0, ReadingPolicy.lecternChance(-1), 1e-9);
        for (int t = 0; t <= 10; t++) {
            assertTrue(ReadingPolicy.lecternChance(t) >= ReadingPolicy.passChance(t));
        }
    }

    @Test
    void signingIsAnOccasionalOutcome() {
        assertTrue(ReadingPolicy.SIGN_CHANCE > 0 && ReadingPolicy.SIGN_CHANCE < 0.5);
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
