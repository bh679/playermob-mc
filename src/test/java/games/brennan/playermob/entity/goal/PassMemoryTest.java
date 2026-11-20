package games.brennan.playermob.entity.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassMemoryTest {

    @Test
    void aShelfGetsOneRollPerPass() {
        PassMemory m = new PassMemory();
        assertTrue(m.firstSight(7L, 0));
        assertFalse(m.firstSight(7L, 10), "still in range — same pass");
        assertFalse(m.firstSight(7L, 600));
    }

    @Test
    void standingBesideAShelfNeverRerollsIt() {
        PassMemory m = new PassMemory();
        assertTrue(m.firstSight(7L, 0));
        for (long t = 10; t < 10_000; t += 10) {
            assertFalse(m.firstSight(7L, t), "seen every scan, so the pass never ends");
        }
    }

    @Test
    void comingBackLaterIsANewPass() {
        PassMemory m = new PassMemory();
        assertTrue(m.firstSight(7L, 0));
        assertTrue(m.firstSight(7L, PassMemory.FORGET_TICKS));
    }

    @Test
    void shelvesAreRememberedSeparately() {
        PassMemory m = new PassMemory();
        assertTrue(m.firstSight(1L, 0));
        assertTrue(m.firstSight(2L, 0));
        assertFalse(m.firstSight(1L, 5));
    }

    @Test
    void oldEntriesArePruned() {
        PassMemory m = new PassMemory();
        for (long p = 0; p < 300; p++) m.firstSight(p, 0);
        m.firstSight(9999L, PassMemory.FORGET_TICKS + 1);
        assertTrue(m.size() < 300);
    }
}
