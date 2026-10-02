package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerSpeeds;
import games.brennan.playermob.entity.PlayerSpeeds.Style;
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

    // ---- Crossing at a player's speed, sized to the gap ------------------------------
    // The speed is one of a player's three (PlayerSpeeds.crossingSpeeds, by reaction speed and
    // urgency); the gap then picks the jump height. Dungeon Train v0.471.0 tightened inter-group
    // seams to ~0.4 blocks.

    private static final double ATTR = PlayerSpeeds.PLAYER_BASE_SPEED;
    private static final double WALK = PlayerSpeeds.walkBlocksPerTick(ATTR);
    private static final double SPRINT = PlayerSpeeds.sprintBlocksPerTick(ATTR);
    private static final double SPRINT_JUMP = PlayerSpeeds.sprintJumpBlocksPerTick(ATTR);

    private static final double[] WALKER = PlayerSpeeds.crossingSpeeds(Style.WALK, ATTR);
    private static final double[] SPRINTER = PlayerSpeeds.crossingSpeeds(Style.SPRINT, ATTR);
    private static final double[] SPRINT_JUMPER = PlayerSpeeds.crossingSpeeds(Style.SPRINT_JUMP, ATTR);

    /** Blocks travelled by a planned hop: speed x airtime. */
    private static double travel(GapLeap.Hop hop) {
        return hop.speed() * GapLeap.airTicks(hop.rise());
    }

    @Test
    void playerSpeedsInBlocksPerTick() {
        assertEquals(4.317, WALK * 20.0, 0.001);
        assertEquals(5.612, SPRINT * 20.0, 0.001);
        assertEquals(7.127, SPRINT_JUMP * 20.0, 0.001);
        // Speed II (+40%) scales all three.
        assertEquals(WALK * 1.4, PlayerSpeeds.walkBlocksPerTick(ATTR * 1.4), EPS);
        assertEquals(SPRINT_JUMP * 1.4, PlayerSpeeds.sprintJumpBlocksPerTick(ATTR * 1.4), EPS);
    }

    @Test
    void crossingSpeedFollowsTheMovementStyle() {
        assertEquals(WALK, PlayerSpeeds.crossingSpeeds(Style.WALK, ATTR)[0], EPS);
        assertEquals(SPRINT, PlayerSpeeds.crossingSpeeds(Style.SPRINT, ATTR)[0], EPS);
        assertEquals(SPRINT_JUMP, PlayerSpeeds.crossingSpeeds(Style.SPRINT_JUMP, ATTR)[0], EPS);
        // Boost is a ceiling trick; over a gap it is just a sprint-jump.
        assertEquals(SPRINT_JUMP, PlayerSpeeds.crossingSpeeds(Style.BOOST, ATTR)[0], EPS);
    }

    @Test
    void crossingSpeedByReactionTier() {
        // Exploring (casual) vs escaping (urgent), through the same table as ordinary movement.
        assertEquals(WALK, crossing(1, true, 0.0), EPS);       // 0–1 always walk
        assertEquals(WALK, crossing(3, false, 0.0), EPS);      // 2–4 walk casually
        assertEquals(SPRINT, crossing(3, true, 0.0), EPS);     // ...and may sprint when fleeing
        assertEquals(WALK, crossing(3, true, 0.9), EPS);
        assertEquals(SPRINT, crossing(7, false, 0.9), EPS);    // 6–7 sprint casually
        assertEquals(SPRINT_JUMP, crossing(7, true, 0.9), EPS);
        assertEquals(SPRINT_JUMP, crossing(9, false, 0.9), EPS);
    }

    private static double crossing(int reaction, boolean urgent, double roll) {
        return PlayerSpeeds.crossingSpeeds(PlayerSpeeds.styleFor(reaction, urgent, roll), ATTR)[0];
    }

    @Test
    void fallbackSpeedsOnlyEverGetFaster() {
        for (double[] speeds : new double[][] {WALKER, SPRINTER, SPRINT_JUMPER}) {
            for (int i = 1; i < speeds.length; i++) {
                assertTrue(speeds[i] > speeds[i - 1]);
            }
            assertEquals(SPRINT_JUMP, speeds[speeds.length - 1], EPS, "sprint-jump is always the last resort");
        }
    }

    @Test
    void tightSeamIsCrossedAtTheMobsOwnSpeed() {
        // Across Dungeon Train's configured range (min 0.3, target 0.4, max 0.5) nobody needs to
        // borrow a faster speed.
        for (double gap : new double[] {0.3, 0.4, 0.5}) {
            assertEquals(WALK, GapLeap.plan(gap, WALKER).speed(), EPS);
            assertEquals(SPRINT, GapLeap.plan(gap, SPRINTER).speed(), EPS);
            assertEquals(SPRINT_JUMP, GapLeap.plan(gap, SPRINT_JUMPER).speed(), EPS);
        }
    }

    @Test
    void walkersJumpHigherToMakeUpForTheirSpeed() {
        // A walk doesn't cover the seam plus landing margin in a hop's airtime, so it takes the full jump.
        assertEquals(GapLeap.LAUNCH_UP, GapLeap.plan(0.4, WALKER).rise(), EPS);
        assertEquals(GapLeap.HOP_UP, GapLeap.plan(0.4, SPRINTER).rise(), EPS);
        assertEquals(GapLeap.HOP_UP, GapLeap.plan(0.4, SPRINT_JUMPER).rise(), EPS);
    }

    @Test
    void everyTierCoversTheFullLandingMarginAcrossDungeonTrainSpacing() {
        // The load-bearing property: whatever the sizing does, the mob must land past the far edge.
        for (double[] speeds : new double[][] {WALKER, SPRINTER, SPRINT_JUMPER}) {
            for (double gap : new double[] {0.0, 0.3, 0.4, 0.5}) {
                GapLeap.Hop hop = GapLeap.plan(gap, speeds);
                assertTrue(travel(hop) >= gap + GapLeap.LANDING_MARGIN - EPS,
                        "gap " + gap + " at " + hop.speed() + " → travelled " + travel(hop));
            }
        }
    }

    @Test
    void stepOverStaysWellAboveAFloorSkim() {
        // Encodes the issue #54 constraint as an assertion: Sable only sticks a riding mob while
        // grounded, so a low skim re-grounds on the origin and stalls. A future tuner lowering
        // HOP_UP toward skim height must fail here rather than silently reintroduce that bug.
        assertTrue(GapLeap.plan(0.4, SPRINT_JUMPER).rise() > 0.2, "hop must stay genuinely airborne, not skim the floor");
    }

    @Test
    void aGapTooWideForAWalkBorrowsTheNextSpeedUp() {
        // A walk-jump reaches ~2.27 blocks. A 1.0-block gap needs 2.5: the walker sprints it instead
        // of dropping into the gap; the sprinter still manages on its own.
        GapLeap.Hop walker = GapLeap.plan(1.0, WALKER);
        assertEquals(SPRINT, walker.speed(), EPS);
        assertTrue(travel(walker) >= 1.0 + GapLeap.LANDING_MARGIN - EPS);
        assertEquals(SPRINT, GapLeap.plan(1.0, SPRINTER).speed(), EPS);
        // Wider still (needs 3.5): only a sprint-jump covers it, so everyone takes one.
        assertEquals(SPRINT_JUMP, GapLeap.plan(2.0, WALKER).speed(), EPS);
        assertEquals(SPRINT_JUMP, GapLeap.plan(2.0, SPRINTER).speed(), EPS);
        assertEquals(GapLeap.LAUNCH_UP, GapLeap.plan(2.0, WALKER).rise(), EPS);
    }

    @Test
    void wideGapAlwaysTakesTheFullJump() {
        // Past the small-gap threshold there is no step-over, however fast the mob.
        assertEquals(GapLeap.LAUNCH_UP, GapLeap.plan(1.6, SPRINT_JUMPER).rise(), EPS);
    }

    @Test
    void unknownGapTakesTheFullSprintJump() {
        // Guards the ABSENT / no-Dungeon-Train path: with nothing to measure, even a walker gets
        // the longest leap a player can make rather than risk falling short.
        double unknown = TrainConfinement.UNKNOWN_GAP;
        for (double[] speeds : new double[][] {WALKER, SPRINTER, SPRINT_JUMPER}) {
            GapLeap.Hop hop = GapLeap.plan(unknown, speeds);
            assertEquals(GapLeap.LAUNCH_UP, hop.rise(), EPS);
            assertEquals(SPRINT_JUMP, hop.speed(), EPS);
        }
    }

    @Test
    void theLeapIsAlwaysExactlyAPlayerSpeed() {
        for (double[] speeds : new double[][] {WALKER, SPRINTER, SPRINT_JUMPER}) {
            for (double gap = 0.0; gap <= 4.0; gap += 0.1) {
                double speed = GapLeap.plan(gap, speeds).speed();
                assertTrue(speed == WALK || speed == SPRINT || speed == SPRINT_JUMP, "gap " + gap + " → " + speed);
            }
        }
    }
}
