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

    // ---- Reading (MobActivity.READING) ------------------------------------
    // Book held low in the off hand in front of the chest, head bowed over it, the main hand
    // resting on the facing page and lifting now and then to turn it.

    /** Head pitch while reading — bowed over the book, lower than the panel look. */
    static final float READ_HEAD_PITCH = 0.6F;
    /** Book arm: pitched forward, just below horizontal, angled in to the chest's centre. */
    static final float READ_BOOK_ARM_PITCH = -0.95F;
    static final float READ_BOOK_ARM_INWARD = 0.35F;
    /** Page arm: lower than the book arm, angled further in so the hand meets the page. */
    static final float READ_PAGE_ARM_PITCH = -0.8F;
    static final float READ_PAGE_ARM_INWARD = 0.5F;
    /** How far the page hand lifts at the top of a page turn. */
    static final float READ_PAGE_TURN_LIFT = 0.25F;
    /** Ticks per page: one turn every 3 seconds. */
    static final float READ_PAGE_TICKS = 60.0F;
    /** Share of each page cycle spent turning (the rest is still). */
    static final float READ_TURN_SHARE = 0.2F;

    public static float readHeadPitch(float blend, float current) {
        return lerp(blend, current, READ_HEAD_PITCH);
    }

    /** 0 while the hand rests, rising to 1 and back once per page as the page turns. */
    static float pageTurn(float ageInTicks) {
        float phase = (ageInTicks % READ_PAGE_TICKS) / READ_PAGE_TICKS;
        if (phase >= READ_TURN_SHARE) return 0.0F;
        return (float) Math.sin(Math.PI * phase / READ_TURN_SHARE);
    }

    public static float readBookArmPitch(float blend, float current) {
        return lerp(blend, current, READ_BOOK_ARM_PITCH);
    }

    /** @param rightArm whether the book arm is the model's right arm (flips the inward direction) */
    public static float readBookArmYaw(float blend, float current, boolean rightArm) {
        return lerp(blend, current, rightArm ? -READ_BOOK_ARM_INWARD : READ_BOOK_ARM_INWARD);
    }

    public static float readPageArmPitch(float blend, float current, float ageInTicks) {
        return lerp(blend, current, READ_PAGE_ARM_PITCH - READ_PAGE_TURN_LIFT * pageTurn(ageInTicks));
    }

    /** @param rightArm whether the page arm is the model's right arm (flips the inward direction) */
    public static float readPageArmYaw(float blend, float current, boolean rightArm) {
        return lerp(blend, current, rightArm ? -READ_PAGE_ARM_INWARD : READ_PAGE_ARM_INWARD);
    }

    // ---- Writing (MobActivity.WRITING) ------------------------------------
    // Same book arm and bowed head as reading; the main hand rests on the page and scribbles —
    // quick small strokes, drifting across the line and back.

    /** Writing-hand pitch: low over the page. */
    static final float WRITE_ARM_PITCH = -0.85F;
    /** Amplitude and speed (radians per tick) of the pen strokes. */
    static final float WRITE_STROKE = 0.05F;
    static final float WRITE_STROKE_SPEED = 1.3F;
    /** Writing-hand yaw: angled in to the page, drifting along the line. */
    static final float WRITE_ARM_INWARD = 0.45F;
    static final float WRITE_LINE_DRIFT = 0.12F;
    static final float WRITE_LINE_SPEED = 0.15F;

    public static float writeArmPitch(float blend, float current, float ageInTicks) {
        return lerp(blend, current, WRITE_ARM_PITCH + WRITE_STROKE * (float) Math.sin(ageInTicks * WRITE_STROKE_SPEED));
    }

    /** @param rightArm whether the writing arm is the model's right arm (flips the inward direction) */
    public static float writeArmYaw(float blend, float current, float ageInTicks, boolean rightArm) {
        float drift = WRITE_LINE_DRIFT * (float) Math.sin(ageInTicks * WRITE_LINE_SPEED);
        float target = rightArm ? -WRITE_ARM_INWARD + drift : WRITE_ARM_INWARD + drift;
        return lerp(blend, current, target);
    }
}
