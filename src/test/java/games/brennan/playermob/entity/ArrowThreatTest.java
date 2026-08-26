package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link ArrowThreat#isIncoming} — the unit-tested core of the
 * raise-a-shield-against-arrows reflex. The collision-course decision (approaching
 * vs receding, lands within the hit disc, soon enough to matter, ignore stalled
 * arrows) is independent of any Minecraft world, so it's driven directly by feeding
 * the function mob/arrow coordinates and a velocity. The live entity scan + shield
 * raise is covered by the in-game Gate 2 test instead.
 *
 * <p>Conventions: mob sits at the origin; {@code HIT_RADIUS} = 1 block,
 * {@code MAX_LEAD} = 20 ticks (the goal's real values).</p>
 */
class ArrowThreatTest {

    private static final double HIT_RADIUS = 1.0;
    private static final double MAX_LEAD = 20.0;

    /** Convenience: mob fixed at the origin. */
    private static boolean incoming(double ax, double ay, double az,
                                    double vx, double vy, double vz) {
        return ArrowThreat.isIncoming(0, 0, 0, ax, ay, az, vx, vy, vz, HIT_RADIUS, MAX_LEAD);
    }

    @Test
    void headOnArrowIsAThreat() {
        // 10 blocks east, flying straight west at the mob.
        assertTrue(incoming(10, 0, 0, -3, 0, 0), "head-on arrow should be incoming");
    }

    @Test
    void arrowMovingAwayIsNotAThreat() {
        // 10 blocks east, flying further east.
        assertFalse(incoming(10, 0, 0, 3, 0, 0), "arrow receding should not be incoming");
    }

    @Test
    void arrowPassingWideMissesTheHitDisc() {
        // Flying west but offset 5 blocks on Z — closest approach misses by 5 > radius.
        assertFalse(incoming(10, 0, 5, -3, 0, 0), "arrow passing wide should miss");
    }

    @Test
    void grazingArrowWithinRadiusIsAThreat() {
        // Flying west, offset only 0.5 on Z — closest approach is inside the 1-block disc.
        assertTrue(incoming(10, 0, 0.5, -3, 0, 0), "near-miss within radius should count");
    }

    @Test
    void approachingButTooFarInFutureIsNotYetAThreat() {
        // 100 blocks out at 1 b/t ⇒ ~100 ticks to closest approach, well past the horizon.
        assertFalse(incoming(100, 0, 0, -1, 0, 0), "beyond the lead horizon should not fire yet");
    }

    @Test
    void stalledArrowIsNotAThreat() {
        // A landed arrow parks at ~zero velocity even when it's right next to the mob.
        assertFalse(incoming(1, 0, 0, 0, 0, 0), "zero-velocity arrow should be ignored");
    }

    @Test
    void crawlingArrowBelowSpeedFloorIsIgnored() {
        // Below the MIN_SPEED_SQR floor (~0.1 b/t) — treat as not really flying.
        assertFalse(incoming(1, 0, 0, -0.05, 0, 0), "sub-floor speed should be ignored");
    }

    @Test
    void descendingArrowAimedAtTheMobIsAThreat() {
        // Lobbed shot dropping in from above-and-east, heading down at the mob.
        assertTrue(incoming(6, 6, 0, -3, -3, 0), "incoming arc should be a threat");
    }

    @Test
    void anArrowInsideTheReflexWindowIsNotAThreat() {
        // 3 blocks out at 3 blocks/tick — one tick from impact. A mob with a 4-tick reflex
        // window cannot get the shield up, so it must not be reported as blockable.
        assertFalse(ArrowThreat.isIncoming(0, 0, 0, -3, 0, 0, 3, 0, 0, 0.5, 4.0, 20.0),
            "a point-blank arrow is inside the blind window");
        // The same arrow IS a threat to a mob whose window has closed (reaction 10).
        assertTrue(ArrowThreat.isIncoming(0, 0, 0, -3, 0, 0, 3, 0, 0, 0.5, 0.0, 20.0),
            "with no blind window the same arrow is blockable");
    }

    @Test
    void theMinLeadFloorStillRejectsWhatTheMaxHorizonRejected() {
        // Beyond the horizon is still no threat regardless of the new floor.
        assertFalse(ArrowThreat.isIncoming(0, 0, 0, -300, 0, 0, 3, 0, 0, 0.5, 0.0, 20.0));
    }

    @Test
    void aZeroFloorMatchesTheOriginalOverload() {
        // The floor-less overload must keep behaving exactly as it always did.
        assertEquals(
            ArrowThreat.isIncoming(0, 0, 0, -30, 0, 0, 3, 0, 0, 0.5, 20.0),
            ArrowThreat.isIncoming(0, 0, 0, -30, 0, 0, 3, 0, 0, 0.5, 0.0, 20.0));
    }
}
