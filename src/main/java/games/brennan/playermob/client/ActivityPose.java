package games.brennan.playermob.client;

/**
 * Angle maths for the "busy at a screen" pose a PlayerMob takes while it has a container open or
 * is going through its gear: head tipped down, main arm raised and drifting as if picking through
 * slots, off arm lifted a little. Each method blends the angle vanilla already computed toward
 * the pose by {@code blend} (0 = untouched, 1 = full pose), so walking / idle sway eases in and
 * out instead of snapping.
 *
 * <p>Pure functions over primitives (radians) — no Minecraft types — so the pose is unit-tested
 * without a client. Sign conventions follow the vanilla humanoid model: negative arm pitch raises
 * the arm forward; negative yaw swings the right arm inward, positive the left.</p>
 */
public final class ActivityPose {

    /** Head pitch at full pose — looking down at the panel. */
    static final float HEAD_PITCH = 0.45F;
    /** How much of the head's own yaw survives at full pose. */
    static final float HEAD_YAW_KEEP = 0.3F;

    /** Main-arm pitch at full pose — raised forward, a little below horizontal. */
    static final float MAIN_ARM_PITCH = -1.25F;
    /** Amplitude of the main arm's up/down picking motion. */
    static final float MAIN_ARM_BOB = 0.14F;
    /** Main-arm yaw at full pose — angled inward across the body. */
    static final float MAIN_ARM_INWARD = 0.22F;
    /** Amplitude of the main arm's side-to-side drift across the panel. */
    static final float MAIN_ARM_DRIFT = 0.2F;

    /** Off-arm pitch at full pose — lifted, as if steadying the bag. */
    static final float OFF_ARM_PITCH = -0.55F;
    /** Off-arm yaw at full pose — angled inward. */
    static final float OFF_ARM_INWARD = 0.25F;

    // Two unrelated angular speeds (radians per tick) so the hand wanders rather than loops.
    private static final float BOB_SPEED = 0.33F;
    private static final float DRIFT_SPEED = 0.19F;

    private ActivityPose() {}

    /** Linear blend from {@code from} to {@code to}; {@code blend} is clamped to 0–1. */
    public static float lerp(float blend, float from, float to) {
        float t = Math.max(0.0F, Math.min(1.0F, blend));
        return from + (to - from) * t;
    }

    public static float headPitch(float blend, float current) {
        return lerp(blend, current, HEAD_PITCH);
    }

    public static float headYaw(float blend, float current) {
        return lerp(blend, current, current * HEAD_YAW_KEEP);
    }

    public static float mainArmPitch(float blend, float current, float ageInTicks) {
        float target = MAIN_ARM_PITCH + MAIN_ARM_BOB * (float) Math.sin(ageInTicks * BOB_SPEED);
        return lerp(blend, current, target);
    }

    /**
     * @param rightArm whether the main arm is the model's right arm (flips the inward direction)
     */
    public static float mainArmYaw(float blend, float current, float ageInTicks, boolean rightArm) {
        float inward = rightArm ? -MAIN_ARM_INWARD : MAIN_ARM_INWARD;
        float target = inward + MAIN_ARM_DRIFT * (float) Math.sin(ageInTicks * DRIFT_SPEED);
        return lerp(blend, current, target);
    }

    public static float offArmPitch(float blend, float current) {
        return lerp(blend, current, OFF_ARM_PITCH);
    }

    /**
     * @param rightArm whether the off arm is the model's right arm (flips the inward direction)
     */
    public static float offArmYaw(float blend, float current, boolean rightArm) {
        return lerp(blend, current, rightArm ? -OFF_ARM_INWARD : OFF_ARM_INWARD);
    }
}
