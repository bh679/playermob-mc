package games.brennan.playermob.entity;

import games.brennan.playermob.entity.DoorHeading.Axis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link DoorHeading} — the decayed-accumulator heading behind the on-train
 * door reflex. What matters: a real walk latches its axis in a few ticks, a single slide against
 * a door panel can't flip it, a stalled mob falls back to the train axis only while marching, and
 * a stale heading decays rather than lingering. Driven with raw positions, no Minecraft world.
 */
class DoorHeadingTest {

    private static final double WALK = 0.15; // blocks per tick, a walking mob

    /** Feed {@code ticks} ticks of movement along one axis; returns the last heading. */
    private static Axis walk(DoorHeading h, double dx, double dz, int ticks, boolean marching) {
        Axis last = Axis.NONE;
        double x = 0;
        double z = 0;
        for (int i = 0; i < ticks; i++) {
            last = h.tick(x, z, marching);
            x += dx;
            z += dz;
        }
        return last;
    }

    @Test
    void sustainedWalkingLatchesItsAxisWithinAFewTicks() {
        DoorHeading h = new DoorHeading();
        assertEquals(Axis.X, walk(h, WALK, 0, 4, false), "three real steps along X latch X");
        assertEquals(Axis.Z, walk(new DoorHeading(), 0, WALK, 4, false), "three real steps along Z latch Z");
    }

    @Test
    void firstSampleOnlyPrimes() {
        DoorHeading h = new DoorHeading();
        assertEquals(Axis.NONE, h.tick(0, 0, false), "no displacement yet — nothing confident");
        assertEquals(Axis.X, new DoorHeading().tick(0, 0, true), "…but a marching mob gets the train axis");
    }

    @Test
    void aSingleSlideAlongThePanelDoesNotFlipTheHeading() {
        DoorHeading h = new DoorHeading();
        walk(h, WALK, 0, 6, true);            // walking along X
        // Shoved against an X-facing door: no X progress, one 0.03 slide along Z (the panel).
        assertEquals(Axis.X, h.tick(6 * WALK, 0.03, true), "one panel slide keeps X");
        assertEquals(Axis.X, h.tick(6 * WALK, 0.03, true), "still X while stalled");
    }

    @Test
    void marchingIsAlwaysTheTrainAxisEvenWhileDetouringAlongZ() {
        DoorHeading h = new DoorHeading();
        assertEquals(Axis.X, walk(h, 0, WALK, 6, true), "a Z detour on a march is not a heading");
        assertEquals(Axis.X, h.tick(0, 6 * WALK, true), "…nor is standing still");
    }

    @Test
    void aStaleHeadingDecaysOnceTheMobStops() {
        DoorHeading h = new DoorHeading();
        walk(h, 0, WALK, 6, false);            // walked Z (to a chest, say)
        Axis a = Axis.Z;
        int ticks = 0;
        while (a == Axis.Z && ticks < 40) {    // now stands still: Z decays away
            a = h.tick(0, 6 * WALK, false);
            ticks++;
        }
        assertEquals(Axis.NONE, a, "a stopped mob has no heading");
        // walk() leaves the last step unfed, so the first "idle" tick is one more real step (acc
        // ≈0.62); at 0.85/tick that drops below 0.3 five ticks later. (From full walking steady
        // state, ≈1.0, decay alone takes eight.) Pinned so retuning is visible.
        assertEquals(6, ticks, "the stale Z heading decays within a few idle ticks");
    }

    @Test
    void marchEndHandsOverToAnAlreadySettledDisplacementHeading() {
        DoorHeading h = new DoorHeading();
        walk(h, 0, WALK, 6, true);             // accumulators fed even while marching
        assertEquals(Axis.Z, h.tick(0, 6 * WALK, false), "the moment the march ends, the real heading is known");
    }

    @Test
    void nearDiagonalMotionIsNotAHeading() {
        DoorHeading h = new DoorHeading();
        assertEquals(Axis.NONE, walk(h, WALK, WALK, 8, false), "equal X and Z displacement is ambiguous");
        assertEquals(Axis.X, walk(new DoorHeading(), WALK, WALK, 8, true), "…so a marching mob keeps the fallback");
    }
}
