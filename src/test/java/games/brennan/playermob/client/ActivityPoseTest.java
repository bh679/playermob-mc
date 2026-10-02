package games.brennan.playermob.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link ActivityPose} — the blend never disturbs vanilla's pose at zero,
 * lands on the pose at one, and keeps the raised arm inside a sane range throughout its drift.
 */
class ActivityPoseTest {

    private static final float EPSILON = 1.0e-5F;

    @Test
    void zeroBlendLeavesVanillaAnglesAlone() {
        assertEquals(0.3F, ActivityPose.headPitch(0.0F, 0.3F), EPSILON);
        assertEquals(-0.8F, ActivityPose.headYaw(0.0F, -0.8F), EPSILON);
        assertEquals(0.4F, ActivityPose.mainArmPitch(0.0F, 0.4F, 123.0F), EPSILON);
        assertEquals(0.1F, ActivityPose.mainArmYaw(0.0F, 0.1F, 123.0F, true), EPSILON);
        assertEquals(-0.2F, ActivityPose.offArmPitch(0.0F, -0.2F), EPSILON);
        assertEquals(0.05F, ActivityPose.offArmYaw(0.0F, 0.05F, false), EPSILON);
    }

    @Test
    void fullBlendLooksDownAndStillsTheHead() {
        assertEquals(ActivityPose.HEAD_PITCH, ActivityPose.headPitch(1.0F, -0.6F), EPSILON);
        assertEquals(1.0F * ActivityPose.HEAD_YAW_KEEP, ActivityPose.headYaw(1.0F, 1.0F), EPSILON);
    }

    @Test
    void mainArmStaysRaisedThroughItsWholeDrift() {
        for (float age = 0.0F; age < 200.0F; age += 0.5F) {
            float pitch = ActivityPose.mainArmPitch(1.0F, 0.0F, age);
            assertTrue(pitch <= ActivityPose.MAIN_ARM_PITCH + ActivityPose.MAIN_ARM_BOB + EPSILON
                    && pitch >= ActivityPose.MAIN_ARM_PITCH - ActivityPose.MAIN_ARM_BOB - EPSILON,
                "pitch " + pitch + " at age " + age);
            assertTrue(pitch < -1.0F, "arm must stay well raised, got " + pitch);
        }
    }

    @Test
    void armsAngleInwardWhicheverHandLeads() {
        // age 0 → no drift term, so the yaw is exactly the inward offset.
        assertEquals(-ActivityPose.MAIN_ARM_INWARD, ActivityPose.mainArmYaw(1.0F, 0.0F, 0.0F, true), EPSILON);
        assertEquals(ActivityPose.MAIN_ARM_INWARD, ActivityPose.mainArmYaw(1.0F, 0.0F, 0.0F, false), EPSILON);
        assertEquals(-ActivityPose.OFF_ARM_INWARD, ActivityPose.offArmYaw(1.0F, 0.0F, true), EPSILON);
        assertEquals(ActivityPose.OFF_ARM_INWARD, ActivityPose.offArmYaw(1.0F, 0.0F, false), EPSILON);
    }

    @Test
    void blendIsClampedAndLinear() {
        assertEquals(0.0F, ActivityPose.lerp(-1.0F, 0.0F, 10.0F), EPSILON);
        assertEquals(5.0F, ActivityPose.lerp(0.5F, 0.0F, 10.0F), EPSILON);
        assertEquals(10.0F, ActivityPose.lerp(2.0F, 0.0F, 10.0F), EPSILON);
    }
}
