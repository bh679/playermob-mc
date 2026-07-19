package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.StayNearPolicy.beyond;
import static games.brennan.playermob.entity.StayNearPolicy.clampRadius;
import static games.brennan.playermob.entity.StayNearPolicy.keep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link StayNearPolicy} — the "walk back only when past the radius" engage
 * gate and the hysteresis release band. Primitives only, so no Minecraft bootstrap is needed
 * (mirrors {@link FollowLovedOnePolicyTest}).
 */
class StayNearPolicyTest {

    @Test
    void engagesOnlyBeyondTheRadius() {
        assertFalse(beyond(0.0, 32));
        assertFalse(beyond(31.9, 32));
        assertFalse(beyond(32.0, 32)); // exactly at the edge — still "inside", behave normally
        assertTrue(beyond(32.1, 32));  // past it → walk back
        assertTrue(beyond(100.0, 32));
    }

    @Test
    void keepsReturningUntilComfortablyInside() {
        // KEEP_FRACTION 0.75 → release threshold is 24 blocks for a radius of 32.
        assertTrue(keep(32.0, 32));  // still out near the edge → keep walking back
        assertTrue(keep(24.1, 32));  // just outside the release band → still returning
        assertFalse(keep(24.0, 32)); // reached radius * 0.75 → release to normal behaviour
        assertFalse(keep(10.0, 32));
    }

    @Test
    void releasePointSitsInsideEngagePoint() {
        // Hysteresis: a distance in the band (release, engage] neither engages fresh nor forces a
        // continued return — it's the dead zone that stops boundary flicker.
        int radius = 20;
        double inBand = 18.0; // between 15 (0.75*20) and 20
        assertFalse(beyond(inBand, radius)); // wouldn't start a return from here
        assertTrue(keep(inBand, radius));    // but if already returning, keeps going past the edge inward
    }

    @Test
    void clampsRadiusToSettableRange() {
        assertEquals(StayNearPolicy.MIN_RADIUS, clampRadius(0));
        assertEquals(StayNearPolicy.MIN_RADIUS, clampRadius(-5));
        assertEquals(StayNearPolicy.MAX_RADIUS, clampRadius(StayNearPolicy.MAX_RADIUS + 1));
        assertEquals(StayNearPolicy.MAX_RADIUS, clampRadius(10_000));
        assertEquals(50, clampRadius(50)); // in range → unchanged
    }
}
