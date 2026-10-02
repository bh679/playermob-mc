package games.brennan.playermob.entity;

/**
 * The only speeds a {@link PlayerMobEntity} may move at: the ones a real player has. Primitives
 * only (no Minecraft types), so it unit-tests without a game bootstrap, exactly like
 * {@link StayNearPolicy} / {@link FollowLovedOnePolicy}.
 *
 * <p><b>Goals pick a gait, never a speed.</b> Every navigation call passes {@link #WALK} or
 * {@link #SPRINT} as its "speed modifier". That number is only a label: {@link #gaitFor} turns it
 * back into a {@link Gait} and {@link PlayerLikeMoveControl} feeds the physics a player's inputs
 * (full stick, the vanilla sprint modifier), so the magnitude of the modifier never reaches
 * {@code travel}. A vanilla mob's ground acceleration is {@code (modifier x attribute)^2}, which is
 * how the old per-goal multipliers (0.5 … 1.4 on a 0.30 base) produced seven land speeds, none of
 * them a player's.</p>
 *
 * <p>Potion effects are untouched: they modify the {@code MOVEMENT_SPEED} attribute, which is what
 * {@link PlayerMobEntity#getSpeed()} reports, exactly as for a player.</p>
 */
public final class PlayerSpeeds {

    private PlayerSpeeds() {}

    /** The two land gaits. Sprint-jumping is sprint plus a held jump (see {@link SprintJumpPolicy}). */
    public enum Gait { WALK, SPRINT }

    /** Navigation speed modifier meaning "walk" — player walk, 4.317 m/s. */
    public static final double WALK = 1.0;
    /** Navigation speed modifier meaning "sprint" — player sprint, 5.612 m/s. */
    public static final double SPRINT = 1.3;
    /**
     * Navigation speed modifier meaning "sprint, and it matters" — running from a threat, a lit
     * fuse or its own burning clothes. Same speed as {@link #SPRINT}; the difference is who is
     * allowed to do it (see {@link #allowsSprint}).
     */
    public static final double URGENT_SPRINT = 1.4;
    /**
     * Modifiers at or above this select {@link Gait#SPRINT}. Midway between {@link #WALK} and
     * {@link #SPRINT} so float noise or a vanilla goal's own constant can't flip the gait.
     */
    static final double SPRINT_THRESHOLD = 1.2;
    /** Modifiers at or above this are urgent. Midway between {@link #SPRINT} and {@link #URGENT_SPRINT}. */
    static final double URGENT_THRESHOLD = 1.35;

    /** Reaction speeds at or below this never sprint, however urgent. */
    static final int NEVER_SPRINT_MAX_REACTION = 1;
    /** Reaction speeds below this sprint only when it is urgent. */
    static final int FREE_SPRINT_MIN_REACTION = 3;

    /** A player's base {@code MOVEMENT_SPEED} attribute. */
    public static final double PLAYER_BASE_SPEED = 0.10;
    /**
     * The base {@code MOVEMENT_SPEED} PlayerMobs had before the player-speed model. Attribute bases
     * persist per entity, so a mob saved under the old model still carries it — see
     * {@link #migratedBaseSpeed}.
     */
    public static final double LEGACY_BASE_SPEED = 0.30;

    /** Movement input multiplier while using an item (drawing a bow, blocking, eating), as for a player. */
    public static final float USE_ITEM_INPUT = 0.2F;

    /** Airborne acceleration, as {@code Player.getFlyingSpeed}: a mob's default is the walking value always. */
    public static final float FLYING_SPEED = 0.02F;
    /** Airborne acceleration while sprinting — what makes a sprint-jump average 7.127 m/s rather than 6.25. */
    public static final float SPRINT_FLYING_SPEED = 0.025999999F;

    /**
     * {@code speed} the vanilla navigator's stuck detection is shown (see {@code PlayerMobNavigation}).
     * Its thresholds scale with {@code getSpeed()^2}, so at a player's 0.10 they would be nine times
     * laxer than the ones this mod's goals were tuned around; this keeps them where they were.
     */
    public static final float STUCK_DETECTION_SPEED = (float) LEGACY_BASE_SPEED;

    // ---- Steady-state speeds, in blocks/tick, for code that sets velocity directly (the gap leap) ----

    /** Vanilla's 0.98 input damping, applied to a full stick. */
    private static final double INPUT_DAMPING = 0.98;
    /** Per-tick ground drag on default blocks: slipperiness 0.6 x air drag 0.91. */
    private static final double GROUND_DRAG = 0.6 * 0.91;
    /** The vanilla sprint attribute modifier: +30%, multiplied on the total. */
    private static final double SPRINT_MODIFIER = 1.3;
    /**
     * Average horizontal speed of a player holding sprint and jump on flat ground at the base speed
     * attribute — 7.127 m/s. Faster than a plain sprint because every takeoff adds a 0.2 boost.
     */
    static final double SPRINT_JUMP_BLOCKS_PER_TICK = 0.35635;

    /** Walking speed (blocks/tick) for a movement-speed attribute value: 0.2159 at a player's 0.10. */
    public static double walkBlocksPerTick(double speedAttribute) {
        return speedAttribute * INPUT_DAMPING / (1.0 - GROUND_DRAG);
    }

    /** Sprinting speed (blocks/tick): 0.2806 at a player's 0.10. */
    public static double sprintBlocksPerTick(double speedAttribute) {
        return walkBlocksPerTick(speedAttribute * SPRINT_MODIFIER);
    }

    /** Sprint-jumping speed (blocks/tick), scaled with the attribute so Speed and Slowness carry over. */
    public static double sprintJumpBlocksPerTick(double speedAttribute) {
        return SPRINT_JUMP_BLOCKS_PER_TICK * speedAttribute / PLAYER_BASE_SPEED;
    }

    /**
     * The speeds (blocks/tick) a mob may cross a gap at, slowest first: its own speed, then each
     * faster player speed as a fallback for a gap its own can't clear. A mob that may not sprint
     * ({@link #allowsSprint}) starts at a walk; one that sprints but doesn't sprint-jump starts at
     * a sprint; a sprint-jumper has only the one.
     *
     * @param sprintJumps    whether this mob sprint-jumps this time ({@link SprintJumpPolicy#rollsRun})
     * @param speedAttribute the mob's movement-speed attribute <em>without</em> the sprint modifier
     */
    public static double[] crossingSpeeds(int reactionSpeed, boolean urgent, boolean sprintJumps,
                                          double speedAttribute) {
        double walk = walkBlocksPerTick(speedAttribute);
        double sprint = sprintBlocksPerTick(speedAttribute);
        double sprintJump = sprintJumpBlocksPerTick(speedAttribute);
        if (!allowsSprint(reactionSpeed, urgent)) {
            return new double[] {walk, sprint, sprintJump};
        }
        if (!sprintJumps) {
            return new double[] {sprint, sprintJump};
        }
        return new double[] {sprintJump};
    }

    /** Which gait a navigation speed modifier asks for. */
    public static Gait gaitFor(double navModifier) {
        return navModifier >= SPRINT_THRESHOLD ? Gait.SPRINT : Gait.WALK;
    }

    /** Whether a navigation speed modifier marks the movement as urgent (see {@link #URGENT_SPRINT}). */
    public static boolean isUrgent(double navModifier) {
        return navModifier >= URGENT_THRESHOLD;
    }

    /**
     * Whether a mob of this reaction speed sprints when a goal asks for the sprint gait. Sprinting
     * is something a sluggish mob doesn't think to do: reaction 0–1 never sprints, reaction 2 only
     * when it is {@code urgent}, and 3 and up whenever asked. A mob that may not sprint walks.
     */
    public static boolean allowsSprint(int reactionSpeed, boolean urgent) {
        if (reactionSpeed <= NEVER_SPRINT_MAX_REACTION) {
            return false;
        }
        return urgent || reactionSpeed >= FREE_SPRINT_MIN_REACTION;
    }

    /**
     * Scale a raw {@code (forward, strafe)} input pair to a player's stick: unit length times
     * {@code full}, or {@link #USE_ITEM_INPUT} of that while using an item. A zero input stays zero.
     *
     * @param full the full-stick magnitude for this game version (the 0.98 input damping is applied
     *             before the AI step on newer versions, so the control writes it itself there)
     * @return {@code {forward, strafe}}
     */
    public static float[] stickInput(float forward, float strafe, float full, boolean usingItem) {
        double length = Math.sqrt((double) forward * forward + (double) strafe * strafe);
        if (length < 1.0e-4) {
            return new float[] {0.0F, 0.0F};
        }
        double scale = (usingItem ? full * USE_ITEM_INPUT : full) / length;
        return new float[] {(float) (forward * scale), (float) (strafe * scale)};
    }

    /**
     * The base speed a loaded mob should have. A save from before the player-speed model
     * ({@code alreadyMigrated} false) that still carries exactly {@link #LEGACY_BASE_SPEED} drops to
     * {@link #PLAYER_BASE_SPEED}; anything else — a migrated save, or a base someone set on purpose
     * with {@code /attribute} — is left alone.
     */
    public static double migratedBaseSpeed(double savedBase, boolean alreadyMigrated) {
        if (!alreadyMigrated && Math.abs(savedBase - LEGACY_BASE_SPEED) < 1.0e-6) {
            return PLAYER_BASE_SPEED;
        }
        return savedBase;
    }
}
