package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link AimCone#facesWithin} — the cone test that decides
 * whether a bow-drawing shooter is pointing at the mob. Independent of any
 * Minecraft world: fed a unit view vector and a target offset. The live shooter
 * scan (weapon state, line of sight, mob targets) is covered by the in-game Gate 2
 * test instead.
 *
 * <p>Convention: shooter at the origin looking down +X ({@code look = (1,0,0)});
 * cone half-angle ~53° ({@code cosThreshold = 0.6}, the goal's value).</p>
 */
class AimConeTest {

    private static final double COS = 0.6;

    private static boolean aimedAt(double dx, double dy, double dz) {
        return AimCone.facesWithin(1, 0, 0, dx, dy, dz, COS);
    }

    @Test
    void straightAtTargetIsAimed() {
        assertTrue(aimedAt(10, 0, 0), "looking straight at the target");
    }

    @Test
    void targetBehindIsNotAimed() {
        assertFalse(aimedAt(-10, 0, 0), "target directly behind the shooter");
    }

    @Test
    void perpendicularTargetIsNotAimed() {
        assertFalse(aimedAt(0, 0, 10), "target 90° to the side");
    }

    @Test
    void withinConeIsAimed() {
        // ~45° off-axis (dx == dz): cos = 0.707 > 0.6 ⇒ inside the cone.
        assertTrue(aimedAt(10, 0, 10), "just inside the cone half-angle");
    }

    @Test
    void outsideConeIsNotAimed() {
        // ~63° off-axis: cos ≈ 0.45 < 0.6 ⇒ outside the cone.
        assertFalse(aimedAt(5, 0, 10), "outside the cone half-angle");
    }

    @Test
    void pointBlankCountsAsAimed() {
        assertTrue(aimedAt(0, 0, 0), "zero offset is treated as aimed");
    }
}
