package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link GapLeap#launchVelocity}, the cross-gap leap aim shared by
 * {@link CrossGroupGapGoal} (forward explore) and {@link FleeFromCategoryGoal} (flee escape).
 * The rest of the leap (carry tracking, the flight controller, landing detection) needs a live
 * entity plus live Dungeon-Train geometry and is covered by the in-game Gate 2 smoke test instead.
 */
class GapLeapTest {

    private static final double EPS = 1.0e-9;

    @Test
    void aimsAlongXWithRequestedSpeedAndVertical() {
        Vec3 v = GapLeap.launchVelocity(new Vec3(0, 70, 0), new Vec3(10, 70, 0), 0.5, 0.42);
        assertEquals(0.5, v.x, EPS, "full horizontal speed toward +X");
        assertEquals(0.0, v.z, EPS, "no Z component for a pure-X target");
        assertEquals(0.42, v.y, EPS, "vertical component is passed through verbatim");
    }

    @Test
    void horizontalMagnitudeEqualsRequestedSpeedOnADiagonal() {
        // 3-4-5: a (3,4) XZ delta normalised to speed 0.5 → (0.3, 0.4), magnitude 0.5.
        // The target's Y differs from the source's to prove vertical delta is ignored.
        Vec3 v = GapLeap.launchVelocity(new Vec3(0, 70, 0), new Vec3(3, 90, 4), 0.5, 0.1);
        assertEquals(0.3, v.x, EPS);
        assertEquals(0.4, v.z, EPS);
        assertEquals(0.5, Math.hypot(v.x, v.z), EPS, "XZ magnitude is exactly the requested speed");
        assertEquals(0.1, v.y, EPS, "vertical delta of the target does not affect the result");
    }

    @Test
    void pointsBackwardForANegativeTarget() {
        Vec3 v = GapLeap.launchVelocity(new Vec3(0, 70, 0), new Vec3(-8, 70, 0), 0.55, 0.5);
        assertEquals(-0.55, v.x, EPS, "aims toward -X when the target is behind on X");
        assertEquals(0.0, v.z, EPS);
    }

    @Test
    void degenerateTargetYieldsPurelyVertical() {
        // Target directly above: no XZ direction to take, so only the vertical survives.
        Vec3 v = GapLeap.launchVelocity(new Vec3(5, 70, 5), new Vec3(5, 80, 5), 0.5, 0.5);
        assertEquals(0.0, v.x, EPS);
        assertEquals(0.0, v.z, EPS);
        assertEquals(0.5, v.y, EPS);
    }

    // ---- Gap-proportionate hop sizing ------------------------------------------------
    // Dungeon Train v0.471.0 tightened inter-group seams to ~0.4 blocks; these cover the
    // scaling of the arc to the measured gap, and the fallbacks that keep the original
    // sprint jump wherever the gap isn't measurably tight.

    /** Blocks travelled by a hop of {@code vy} impulse at {@code speed}: speed × airtime,
     *  where airtime is {@code 2 * vy / gravity} ticks (up and back down to launch height). */
    private static double travel(double gapWidth) {
        double vy = GapLeap.hopRise(gapWidth);
        return GapLeap.hopSpeed(gapWidth, vy) * (2.0 * vy / GapLeap.GRAVITY_PER_TICK);
    }

    @Test
    void wideGapKeepsTheFullSprintJump() {
        assertEquals(GapLeap.LAUNCH_UP, GapLeap.hopRise(3.0), EPS);
        assertEquals(GapLeap.SPRINT_SPEED, GapLeap.hopSpeed(3.0, GapLeap.LAUNCH_UP), EPS);
    }

    @Test
    void unknownGapKeepsTheFullSprintJump() {
        // Guards the ABSENT / no-Dungeon-Train path: an unmeasurable gap must behave exactly
        // as it did before hops became gap-proportionate.
        double unknown = TrainConfinement.UNKNOWN_GAP;
        assertEquals(GapLeap.LAUNCH_UP, GapLeap.hopRise(unknown), EPS);
        assertEquals(GapLeap.SPRINT_SPEED, GapLeap.hopSpeed(unknown, GapLeap.LAUNCH_UP), EPS);
    }

    @Test
    void tightGapUsesTheSmallerStepOver() {
        assertEquals(GapLeap.HOP_UP, GapLeap.hopRise(0.4), EPS);
        assertTrue(GapLeap.hopRise(0.4) < GapLeap.LAUNCH_UP, "step-over rises less than a sprint jump");
        assertTrue(GapLeap.hopSpeed(0.4, GapLeap.HOP_UP) < GapLeap.SPRINT_SPEED * 0.75,
                "and moves substantially slower, so the hop reads as a step rather than a leap");
    }

    @Test
    void stepOverStaysWellAboveAFloorSkim() {
        // Encodes the issue #54 constraint as an assertion: Sable only sticks a riding mob while
        // grounded, so a low skim re-grounds on the origin and stalls. A future tuner lowering
        // HOP_UP toward skim height must fail here rather than silently reintroduce that bug.
        assertTrue(GapLeap.hopRise(0.4) > 0.2, "hop must stay genuinely airborne, not skim the floor");
    }

    @Test
    void hopAlwaysClearsTheGapWithRoomToSpare() {
        // The load-bearing property: whatever the sizing maths does, the mob must actually land
        // past the far edge. Checked across the whole small-gap range, including the threshold
        // itself. Note the SPRINT_SPEED clamp bites above ~1.35 blocks, so the realised buffer
        // shrinks from LANDING_MARGIN toward ~1.35 there — still a comfortable overshoot onto
        // the far deck, which is the safe failure direction.
        for (double gap : new double[] {0.0, 0.3, 0.4, 0.5, 1.0, GapLeap.SMALL_GAP_THRESHOLD}) {
            assertTrue(travel(gap) >= gap + 1.0,
                    "gap " + gap + " → travelled " + travel(gap) + ", needs to clear the gap plus a landing buffer");
        }
    }

    @Test
    void hopCoversTheFullLandingMarginAcrossDungeonTrainSpacing() {
        // Over Dungeon Train's actual configured range (min 0.3, target 0.4, max 0.5) the sizing
        // is unclamped, so the full landing margin is realised.
        for (double gap : new double[] {0.3, 0.4, 0.5}) {
            assertTrue(travel(gap) >= gap + GapLeap.LANDING_MARGIN - EPS,
                    "gap " + gap + " → travelled " + travel(gap));
        }
    }

    @Test
    void hopSpeedIsMonotonicInGapWidth() {
        // A wider gap must never produce a slower hop.
        double prev = 0.0;
        for (double gap = 0.0; gap <= GapLeap.SMALL_GAP_THRESHOLD; gap += 0.1) {
            double speed = GapLeap.hopSpeed(gap, GapLeap.HOP_UP);
            assertTrue(speed >= prev - EPS, "speed dropped at gap " + gap);
            prev = speed;
        }
    }

    @Test
    void aNearZeroGapStillMovesTheMob() {
        // Floor: without it a touching-carriage seam could produce a hop so slow the train's
        // carry dominates and the mob lands behind where it started.
        assertTrue(GapLeap.hopSpeed(0.0, GapLeap.HOP_UP) >= GapLeap.MIN_HOP_SPEED);
    }
}
