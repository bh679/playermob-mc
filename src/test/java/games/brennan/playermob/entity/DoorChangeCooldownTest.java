package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DoorChangeCooldown} — the unit-tested core of the per-mob door
 * rate limit. Everything interesting about it (the flat 2 s gap, the Fibonacci climb while the
 * mob isn't getting anywhere, the reset once it moves a whole block, and the close-behind
 * exemption) is independent of any Minecraft world, so it's driven directly by asking for
 * changes at chosen positions and ticking the clock forward. The live open/close is covered by
 * the in-game Gate 2 test instead.
 */
class DoorChangeCooldownTest {

    /** Run the clock forward, asserting a non-exempt change stays refused the whole time. */
    private static void advanceRefused(DoorChangeCooldown c, int ticks, double x, double z) {
        for (int i = 0; i < ticks; i++) {
            assertFalse(c.tryChange(x, z, false), "should still be on cooldown at tick " + i);
            c.tick();
        }
    }

    @Test
    void firstChangeIsAllowedAndArmsTheFirstRung() {
        DoorChangeCooldown c = new DoorChangeCooldown();
        assertTrue(c.tryChange(0, 0, false), "first change has nothing to wait for");
        assertTrue(c.isOnCooldown(), "and arms the window");
        // Refused for the whole 2 s, allowed on the tick it elapses.
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[0], 0, 0);
        assertFalse(c.isOnCooldown(), "window has elapsed");
    }

    @Test
    void climbsTheLadderWhileTheMobGetsNowhere() {
        DoorChangeCooldown c = new DoorChangeCooldown();
        assertTrue(c.tryChange(0, 0, false), "first change");
        // Each subsequent change from the very same spot waits one rung longer than the last.
        for (int rung = 1; rung < DoorChangeCooldown.LADDER_TICKS.length; rung++) {
            advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[rung - 1], 0, 0);
            assertTrue(c.tryChange(0, 0, false), "allowed once rung " + (rung - 1) + " elapsed");
        }
        // ...and holds at the top rung rather than growing without bound.
        int top = DoorChangeCooldown.LADDER_TICKS[DoorChangeCooldown.LADDER_TICKS.length - 1];
        for (int i = 0; i < 3; i++) {
            advanceRefused(c, top, 0, 0);
            assertTrue(c.tryChange(0, 0, false), "still capped at the top rung");
        }
    }

    @Test
    void movingAWholeBlockResetsTheLadder() {
        DoorChangeCooldown c = new DoorChangeCooldown();
        assertTrue(c.tryChange(0, 0, false), "first change");
        // Climb a few rungs by never going anywhere.
        for (int rung = 1; rung <= 3; rung++) {
            advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[rung - 1], 0, 0);
            assertTrue(c.tryChange(0, 0, false), "climbing to rung " + rung);
        }
        // Now actually travel a block before the next door: straight back to the bottom rung.
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[3], 0, 0);
        assertTrue(c.tryChange(5, 0, false), "allowed after the rung-3 wait");
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[0], 5, 0);
        assertTrue(c.tryChange(10, 0, false), "reset means only the 2 s wait applied");
    }

    @Test
    void exactlyOneBlockCountsAsProgress() {
        // A hair under a block is not progress...
        DoorChangeCooldown shy = new DoorChangeCooldown();
        assertTrue(shy.tryChange(0, 0, false), "first change");
        advanceRefused(shy, DoorChangeCooldown.LADDER_TICKS[0], 0, 0);
        assertTrue(shy.tryChange(0.99, 0, false), "second change, barely moved");
        advanceRefused(shy, DoorChangeCooldown.LADDER_TICKS[1], 0.99, 0);

        // ...but exactly a block is.
        DoorChangeCooldown exact = new DoorChangeCooldown();
        assertTrue(exact.tryChange(0, 0, false), "first change");
        advanceRefused(exact, DoorChangeCooldown.LADDER_TICKS[0], 0, 0);
        assertTrue(exact.tryChange(1.0, 0, false), "second change, moved a full block");
        advanceRefused(exact, DoorChangeCooldown.LADDER_TICKS[0], 1.0, 0);
    }

    @Test
    void progressCountsBothHorizontalAxes() {
        DoorChangeCooldown c = new DoorChangeCooldown();
        assertTrue(c.tryChange(0, 0, false), "first change");
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[0], 0, 0);
        // Diagonal: 0.8² + 0.8² = 1.28 ≥ 1, so it counts.
        assertTrue(c.tryChange(0.8, 0.8, false), "second change, moved diagonally");
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[0], 0.8, 0.8);
    }

    @Test
    void exemptChangePassesMidWindowWithoutClimbing() {
        DoorChangeCooldown c = new DoorChangeCooldown();
        assertTrue(c.tryChange(0, 0, false), "the open");
        c.tick();
        // The close-behind lands ~1 s later, well inside the window, and must be let through.
        assertTrue(c.tryChange(0, 0, true), "close-behind is exempt");
        // It re-armed at the same rung — the pair cost one slot, not two.
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[0], 0, 0);
        assertTrue(c.tryChange(0, 0, false), "next operation waited only the first rung");
    }

    @Test
    void refusedChangeChangesNothing() {
        DoorChangeCooldown c = new DoorChangeCooldown();
        assertTrue(c.tryChange(0, 0, false), "first change");
        // Hammer it every tick from a wildly different position while it's on cooldown. None of
        // those may extend the window, climb the ladder, or move the anchor.
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[0], 99, 99);
        // Window elapsed on schedule; the anchor is still the origin, so a change back at the
        // origin reads as no progress and climbs to exactly rung 1.
        assertTrue(c.tryChange(0, 0, false), "allowed the moment the first rung elapsed");
        advanceRefused(c, DoorChangeCooldown.LADDER_TICKS[1], 0, 0);
        assertTrue(c.tryChange(0, 0, false), "climbed exactly one rung, not seven");
    }

    @Test
    void ladderIsFibonacciSecondsUpToThirtyFour() {
        // 2, 3, 5, 8, 13, 21, 34 seconds at 20 ticks/s. Pinned so a well-meaning tweak to the
        // constants has to come past this test and the reasoning in the class javadoc.
        assertArrayEquals(new int[] {40, 60, 100, 160, 260, 420, 680},
            DoorChangeCooldown.LADDER_TICKS, "backoff ladder");
    }
}
