package games.brennan.playermob.entity;

/**
 * The only speeds a {@link PlayerMobEntity} may move at: the ones a real player has. Primitives
 * only (no Minecraft types), so it unit-tests without a game bootstrap, exactly like
 * {@link StayNearPolicy} / {@link FollowLovedOnePolicy}.
 *
 * <p><b>Goals never pick a speed — or even a gait.</b> Every navigation call passes
 * {@link #CASUAL} or {@link #URGENT} as its "speed modifier". That number is only a label saying
 * what kind of movement this is; the mob's <em>reaction speed</em> then decides how it moves
 * ({@link #styleFor}), and {@link PlayerLikeMoveControl} feeds the physics a player's inputs (full
 * stick, the vanilla sprint modifier, a held jump), so the magnitude of the modifier never reaches
 * {@code travel}. A vanilla mob's ground acceleration is {@code (modifier x attribute)^2}, which is
 * how the old per-goal multipliers (0.5 … 1.4 on a 0.30 base) produced seven land speeds, none of
 * them a player's.</p>
 *
 * <p><b>The table.</b> Moving well is a skill, so it follows the {@code reactionSpeed} trait:</p>
 * <pre>
 * reaction   casual                 combat / fleeing
 *   0–1      walk                   walk
 *   2–4      walk                   sprint, 25% / 50% / 75% of the time
 *   5        walk or sprint, 50:50  sprint
 *   6–7      sprint                 sprint-jump
 *   8–9      sprint-jump            sprint-jump + boost
 *   10       sprint-jump + boost    sprint-jump + boost
 * </pre>
 * <p>"Boost" is head-bumping through a 2-block-tall gap (see {@link SprintJumpPolicy}). The
 * chances are rolled once per stretch of movement, not per tick.</p>
 *
 * <p>Potion effects are untouched: they modify the {@code MOVEMENT_SPEED} attribute, which is what
 * {@link PlayerMobEntity#getSpeed()} reports, exactly as for a player.</p>
 */
public final class PlayerSpeeds {

    private PlayerSpeeds() {}

    /** How a mob moves, fastest last. Each style includes the abilities of the ones before it. */
    public enum Style {
        /** Player walk, 4.317 m/s. */
        WALK,
        /** Player sprint, 5.612 m/s. */
        SPRINT,
        /** Sprint with jump held wherever there is a safe straight run, ~7.1 m/s. */
        SPRINT_JUMP,
        /** Sprint-jump, plus head-bumping through 2-block-tall gaps. */
        BOOST;

        public boolean sprints() {
            return this != WALK;
        }

        public boolean sprintJumps() {
            return this == SPRINT_JUMP || this == BOOST;
        }

        public boolean boosts() {
            return this == BOOST;
        }
    }

    /** Navigation speed modifier for ordinary movement: strolling, looting, marching, following an order. */
    public static final double CASUAL = 1.0;
    /** Navigation speed modifier for combat and fleeing: closing on a target, running from a threat or a fire. */
    public static final double URGENT = 1.3;
    /**
     * Modifiers at or above this are {@link #URGENT}. Midway between the two so float noise or a
     * vanilla goal's own constant (strafing uses 0.25) can't flip it.
     */
    static final double URGENT_THRESHOLD = 1.15;

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
     * The speeds (blocks/tick) a mob may cross a gap at, slowest first: the speed of its own
     * {@code style}, then each faster player speed as a fallback for a gap its own can't clear.
     *
     * @param speedAttribute the mob's movement-speed attribute <em>without</em> the sprint modifier
     */
    public static double[] crossingSpeeds(Style style, double speedAttribute) {
        double walk = walkBlocksPerTick(speedAttribute);
        double sprint = sprintBlocksPerTick(speedAttribute);
        double sprintJump = sprintJumpBlocksPerTick(speedAttribute);
        if (!style.sprints()) {
            return new double[] {walk, sprint, sprintJump};
        }
        if (!style.sprintJumps()) {
            return new double[] {sprint, sprintJump};
        }
        return new double[] {sprintJump};
    }

    /** Whether a navigation speed modifier marks the movement as combat/fleeing (see {@link #URGENT}). */
    public static boolean isUrgent(double navModifier) {
        return navModifier >= URGENT_THRESHOLD;
    }

    /**
     * Chance that a mob which is not yet a habitual sprinter sprints for a stretch of movement.
     * Combat/fleeing ramps up from never at reaction 1 to always at 5 (25% / 50% / 75% between);
     * casual movement is never below 5, half the time at 5, always above.
     */
    public static double sprintChance(int reactionSpeed, boolean urgent) {
        int reaction = DispositionTraits.clamp(reactionSpeed);
        if (urgent) {
            return Math.max(0.0, Math.min(1.0, (reaction - 1) / 4.0));
        }
        if (reaction < DispositionTraits.DEFAULT) {
            return 0.0;
        }
        return reaction == DispositionTraits.DEFAULT ? 0.5 : 1.0;
    }

    /**
     * How a mob of this reaction speed moves — the table in the class comment.
     *
     * @param urgent whether the movement is combat/fleeing ({@link #URGENT}) rather than casual
     * @param roll   a uniform random number in {@code [0, 1)}, held for the whole stretch of
     *               movement; decides the tiers that sprint only some of the time
     */
    public static Style styleFor(int reactionSpeed, boolean urgent, double roll) {
        int reaction = DispositionTraits.clamp(reactionSpeed);
        int boostFrom = urgent ? 8 : 10;
        int sprintJumpFrom = urgent ? 6 : 8;
        if (reaction >= boostFrom) {
            return Style.BOOST;
        }
        if (reaction >= sprintJumpFrom) {
            return Style.SPRINT_JUMP;
        }
        return roll < sprintChance(reaction, urgent) ? Style.SPRINT : Style.WALK;
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
