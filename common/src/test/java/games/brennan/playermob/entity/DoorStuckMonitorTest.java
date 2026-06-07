package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DoorStuckMonitor} — the unit-tested core of the
 * close-a-blocking-door recovery. The interesting behaviour (fire after a run of
 * no-progress ticks, stay silent during the cooldown, reset on movement, never
 * fire when idle) is independent of any Minecraft world, so it's driven directly
 * by feeding the monitor positions and the trying-to-move flag. The live door
 * scan/close is covered by the in-game Gate 2 test instead.
 */
class DoorStuckMonitorTest {

    @Test
    void firesAfterStuckTicksOfNoProgress() {
        DoorStuckMonitor m = new DoorStuckMonitor();
        // The first sample only primes the last position — no delta yet, can't fire.
        assertFalse(m.tick(0, 0, true), "first sample only primes");
        // Stationary while trying to move: fires exactly on the STUCK_TICKS-th follow-up.
        for (int i = 1; i < DoorStuckMonitor.STUCK_TICKS; i++) {
            assertFalse(m.tick(0, 0, true), "not stuck yet at tick " + i);
        }
        assertTrue(m.tick(0, 0, true), "fires after STUCK_TICKS of no progress");
    }

    @Test
    void progressResetsTheCounter() {
        DoorStuckMonitor m = new DoorStuckMonitor();
        double x = 0;
        for (int i = 0; i < DoorStuckMonitor.STUCK_TICKS * 3; i++) {
            x += 0.5; // well above the no-progress epsilon
            assertFalse(m.tick(x, 0, true), "a moving mob is never stuck");
        }
    }

    @Test
    void idleMobIsNeverStuck() {
        DoorStuckMonitor m = new DoorStuckMonitor();
        for (int i = 0; i < DoorStuckMonitor.STUCK_TICKS * 3; i++) {
            assertFalse(m.tick(0, 0, false), "not trying to move means not stuck");
        }
    }

    @Test
    void cooldownSuppressesRefiringThenItCanFireAgain() {
        DoorStuckMonitor m = new DoorStuckMonitor();
        assertTrue(fireOnceStationary(m), "primes then fires");
        // Immediately after a fire the cooldown window must stay silent.
        for (int i = 0; i < DoorStuckMonitor.COOLDOWN_TICKS; i++) {
            assertFalse(m.tick(0, 0, true), "silent during cooldown at tick " + i);
        }
        // Then a fresh run of no-progress ticks fires again.
        for (int i = 1; i < DoorStuckMonitor.STUCK_TICKS; i++) {
            assertFalse(m.tick(0, 0, true), "re-accumulating at tick " + i);
        }
        assertTrue(m.tick(0, 0, true), "fires again after cooldown + STUCK_TICKS");
    }

    /** Drive a stationary, trying-to-move mob through priming + STUCK_TICKS; returns its final (firing) tick. */
    private static boolean fireOnceStationary(DoorStuckMonitor m) {
        m.tick(0, 0, true); // prime
        for (int i = 1; i < DoorStuckMonitor.STUCK_TICKS; i++) {
            m.tick(0, 0, true);
        }
        return m.tick(0, 0, true);
    }
}
