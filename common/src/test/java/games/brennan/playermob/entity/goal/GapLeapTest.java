package games.brennan.playermob.entity.goal;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
