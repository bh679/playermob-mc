package games.brennan.playermob.entity;

/**
 * The pure decision function that turns a {@link PlayerMobEntity}'s numeric
 * traits ({@link DispositionTraits}) and its {@link FeelingLedger} feeling toward
 * a target into a single {@link Reaction}. All-static and world-free — takes
 * primitives only — so it unit-tests like {@code EquipmentEvaluator} /
 * {@code ForagePolicy}. This is the single seam the goal classes read through.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li><b>Hostile mobs</b> → fight or flee by fight/flight (no bubble).</li>
 *   <li><b>Neutral mobs</b> → always ignore proactively (harmless until provoked). The
 *       entity re-treats one as hostile only once it actually turns on the mob; being
 *       hit is handled by the retaliation path, not here.</li>
 *   <li><b>Animals / villagers</b> → greet if friendly, else ignore (never proactively
 *       attacked; hunger-hunting is a separate goal).</li>
 *   <li><b>Players / PlayerMobs</b>:
 *     <ul>
 *       <li>feeling ≤ {@link #FEELING_HATE} ("hate") → fight/flee within {@link #HATE_RANGE},
 *           overriding nature regardless of friendliness;</li>
 *       <li>else feeling ≥ {@link #FEELING_LOVE} ("love") → greet, overriding nature regardless of
 *           friendliness (the gift sub-step is decided by the goal) — the mirror of the hate rule;</li>
 *       <li>else friendliness ≥ {@link #FRIEND_GREET} → greet;</li>
 *       <li>else (low friendliness) a two-ring <b>personal-space bubble</b>: fight/flee
 *           inside {@link #innerRadius}, skeptical WATCH inside {@link #outerRadius},
 *           ignore beyond.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>The bubble gates <em>acquisition</em> only; once a target is engaged, normal
 * combat retention applies. All thresholds/radii are tunable constants here.</p>
 */
public final class DispositionResolver {

    private DispositionResolver() {}

    /** Fight/flight ≥ this fights; below it flees. */
    static final int FF_FIGHT = 5;
    /** Friendliness ≥ this is "friendly" (greets); below it is territorial. */
    static final int FRIEND_GREET = 5;
    /** Feeling ≤ this is "hate" — overrides friendliness into fight/flight. */
    static final float FEELING_HATE = 3.0F;
    /** Feeling ≥ this is "love" — required before a friendly mob actually gifts (goal-side). */
    public static final float FEELING_LOVE = 7.0F;

    /** Largest any reaction radius can grow to (also the social goals' scan range). */
    public static final double MAX_RANGE = 16.0;
    /** A hated target is treated as an enemy within this range, any friendliness. */
    static final double HATE_RANGE = MAX_RANGE;
    /** Blocks the bubble grows/shrinks per point of feeling away from neutral (5). */
    static final double SPACE_PER_FEELING = 0.5;

    /** Outer (skeptical) radius at neutral feeling, indexed by friendliness 0..4. */
    private static final double[] OUTER_AT_NEUTRAL = {15.0, 8.5, 7.0, 6.0, 5.5};
    /** Inner (fight-or-flight) radius at neutral feeling, indexed by friendliness 0..2. */
    private static final double[] INNER_AT_NEUTRAL = {10.0, 5.0, 1.5};

    /**
     * Witnessed-attack reaction. A mob seeing attacker A hit victim V forms one continuous signed
     * feeling toward A — positive (admiration) when it approves of the violence, negative (resentment)
     * when A harmed someone it loves. {@code WITNESS_APPROVE_MARGIN} sets the approve threshold
     * ({@code feelingTowardVictim ≤ fightFlight − }this); {@link #witnessedAttackDelta} gives the magnitude.
     */
    static final int WITNESS_APPROVE_MARGIN = 3;
    /** Weight on {@code (fightFlight − feelingTowardVictim)} in the witnessed-attack delta. */
    static final float WITNESS_LIKE_SCALE = 0.4F;
    /** Per-fightFlight damping in the witnessed-attack delta. */
    static final float WITNESS_AGGRESSION_SCALE = 0.1F;
    /** Cap on the positive (admiration) witnessed-attack delta. */
    static final float WITNESS_MAX = 3.0F;

    /** Outcome of being hit — feeling mutation is done by the entity, not here. */
    public enum HurtResponse { RETALIATE, FLEE }

    /**
     * Outer (skeptical / WATCH) radius for a low-friendliness mob toward a
     * non-hated target. Returns 0 for friendliness ≥ 5 (no bubble). Feeling
     * widens it for disliked targets and shrinks it for liked ones, capped at
     * {@link #MAX_RANGE}.
     */
    public static double outerRadius(int friendliness, float feeling) {
        if (friendliness < 0 || friendliness >= FRIEND_GREET) {
            return 0.0;
        }
        double base = OUTER_AT_NEUTRAL[friendliness];
        return Math.min(MAX_RANGE, base + (DispositionTraits.DEFAULT - feeling) * SPACE_PER_FEELING);
    }

    /**
     * Inner (fight-or-flight) radius for a non-hated target. Only friendliness
     * 0–2 have one; 3–4 are skeptical-only (returns 0). The hate case is handled
     * in {@link #resolve} via {@link #HATE_RANGE}, so this assumes feeling &gt;
     * {@link #FEELING_HATE}.
     */
    public static double innerRadius(int friendliness, float feeling) {
        if (friendliness < 0 || friendliness > 2) {
            return 0.0;
        }
        double base = INNER_AT_NEUTRAL[friendliness] + (DispositionTraits.DEFAULT - feeling) * SPACE_PER_FEELING;
        return clamp(base, 0.0, outerRadius(friendliness, feeling));
    }

    /**
     * The proactive reaction toward a categorised target at {@code distance}
     * blocks. {@code feeling} is ignored for non-player categories. Never returns
     * null; an uncategorised target (e.g. creative/spectator → null category)
     * resolves to {@link Reaction#IGNORE}.
     */
    public static Reaction resolve(int fightFlight, int friendliness, float feeling,
                                   TargetCategory category, double distance) {
        if (category == null) {
            return Reaction.IGNORE;
        }
        switch (category) {
            case HOSTILE_MOBS:
                return fightFlight >= FF_FIGHT ? Reaction.FIGHT : Reaction.FLEE;
            case NEUTRAL_MOBS:
                // Harmless until provoked — never acquired proactively. Once a neutral
                // mob actually turns on the mob, the entity reclassifies it to
                // HOSTILE_MOBS before calling resolve, so this stays a pure "leave it be".
                return Reaction.IGNORE;
            case ANIMALS:
            case VILLAGERS:
                return friendliness >= FRIEND_GREET ? Reaction.GREET : Reaction.IGNORE;
            case PLAYERS:
            default:
                if (feeling <= FEELING_HATE) {
                    // Hate overrides nature: treat as an enemy within HATE_RANGE.
                    return distance <= HATE_RANGE
                        ? (fightFlight >= FF_FIGHT ? Reaction.FIGHT : Reaction.FLEE)
                        : Reaction.IGNORE;
                }
                if (feeling >= FEELING_LOVE) {
                    // Love overrides nature: never attack a loved one — greet it instead, at any
                    // friendliness (the gift sub-step is decided by the goal). Mirror of the hate rule.
                    return Reaction.GREET;
                }
                if (friendliness >= FRIEND_GREET) {
                    return Reaction.GREET;
                }
                if (distance <= innerRadius(friendliness, feeling)) {
                    return fightFlight >= FF_FIGHT ? Reaction.FIGHT : Reaction.FLEE;
                }
                if (distance <= outerRadius(friendliness, feeling)) {
                    return Reaction.WATCH;
                }
                return Reaction.IGNORE;
        }
    }

    /** Immediate response to taking a hit: stand and fight, or break off and flee. */
    public static HurtResponse onHurt(int fightFlight) {
        return fightFlight >= FF_FIGHT ? HurtResponse.RETALIATE : HurtResponse.FLEE;
    }

    /**
     * The signed feeling a mob forms toward attacker A on witnessing A hit victim V — positive
     * admiration, negative resentment, or {@code 0} (indifferent). Two bands are active; between them
     * the mob doesn't react:
     * <ul>
     *   <li><b>Approve</b> — {@code feelingTowardVictim ≤ fightFlight − }{@link #WITNESS_APPROVE_MARGIN}
     *       and {@code <} {@link #FEELING_LOVE}: it likes A for the violence (positive).</li>
     *   <li><b>Resent</b> — {@code feelingTowardVictim ≥ }{@link #FEELING_LOVE}: A harmed someone it
     *       loves (negative).</li>
     * </ul>
     * In either band the magnitude is {@code WITNESS_LIKE_SCALE·(fightFlight − feelingTowardVictim) −
     * WITNESS_AGGRESSION_SCALE·fightFlight}, capped above at {@link #WITNESS_MAX}: admiration grows with
     * fight/flight and with disliking V; resentment deepens the more it loves V and the calmer it is.
     */
    public static float witnessedAttackDelta(int fightFlight, float feelingTowardVictim) {
        boolean resent = feelingTowardVictim >= FEELING_LOVE;
        boolean approve = feelingTowardVictim < FEELING_LOVE
            && feelingTowardVictim <= fightFlight - WITNESS_APPROVE_MARGIN;
        if (!resent && !approve) {
            return 0.0F;
        }
        float delta = Math.min(WITNESS_MAX,
            WITNESS_LIKE_SCALE * (fightFlight - feelingTowardVictim)
                - WITNESS_AGGRESSION_SCALE * fightFlight);
        return Math.abs(delta) < 1e-4F ? 0.0F : delta;
    }

    // ---- Trait-weighted feeling scaling ------------------------------------
    //
    // Traits decide WHICH reaction the mob picks (resolve / onHurt); these decide
    // HOW STRONGLY the resulting feeling-event lands. Pure and primitives-only like
    // the rest of this class, so they unit-test without a live world.

    /** Kindness multiplier at friendliness 0 — the floor; kindness only ever amplifies (≥ 1.0×). */
    public static final float KINDNESS_SCALE_BASE = 1.0F;
    /** Added kindness multiplier per point of friendliness → 2.0× at friendliness 10. */
    public static final float KINDNESS_SCALE_PER_POINT = 0.1F;
    /** Attack feeling-loss multiplier at fightFlight 0 — a timid mob takes a hit hardest. */
    public static final float ATTACK_SCALE_BASE = 1.2F;
    /** Subtracted attack multiplier per point of fightFlight → 0.2× at fightFlight 10. */
    public static final float ATTACK_SCALE_PER_POINT = 0.1F;

    /**
     * Multiplier on the feeling <b>gained</b> from an act of kindness (gift, bow, defend),
     * rising with friendliness: {@link #KINDNESS_SCALE_BASE 1.0}× at 0 up to 2.0× at 10. A
     * friendlier mob is won over faster and further. Never dampens (≥ 1.0×).
     */
    public static float kindnessScale(int friendliness) {
        return KINDNESS_SCALE_BASE + clampTrait(friendliness) * KINDNESS_SCALE_PER_POINT;
    }

    /**
     * Multiplier on the feeling <b>lost</b> when attacked, falling with fightFlight:
     * {@link #ATTACK_SCALE_BASE 1.2}× at 0 down to 0.2× at 10. The more aggressive the mob,
     * the less a hit cools its feeling toward the attacker (it cares less about being hit).
     */
    public static float attackScale(int fightFlight) {
        return ATTACK_SCALE_BASE - clampTrait(fightFlight) * ATTACK_SCALE_PER_POINT;
    }

    // ---- Ranged firerate (how fast a fighting mob shoots) ------------------
    //
    // A ranged mob's firerate is floored by the weapon's own cadence (bow draw /
    // crossbow charge) — it can never shoot faster than it can ready the weapon.
    // fightFlight then adds an EXTRA cooldown on top of that floor: an aggressive
    // mob adds nothing (fires as fast as the weapon allows), a balanced mob adds a
    // beat, a timid one adds a long pause (it rarely fights at all, so this barely
    // shows). Pure and primitives-only — unit-tests without a live world.

    /** Extra ranged cooldown at fightFlight 10 (ticks): fire as fast as the weapon allows. */
    public static final int RANGED_DELAY_HIGH = 0;
    /** Extra ranged cooldown at fightFlight 5 (ticks = 2s): the balanced beat. */
    public static final int RANGED_DELAY_MID = 40;
    /** Extra ranged cooldown at fightFlight 0 (ticks = 10s): the timid crawl. */
    public static final int RANGED_DELAY_LOW = 200;
    /** Pivot point of the firerate curve — matches {@link #FF_FIGHT} / {@link DispositionTraits#DEFAULT}. */
    static final int RANGED_DELAY_PIVOT = 5;

    /**
     * Extra cooldown (in ticks) added <b>on top of the weapon's own firing cadence</b>
     * between ranged shots, as a function of fightFlight, then stretched or compressed by
     * reaction speed ({@link ReactionSpeed#ticks}) — a twitchy mob re-fires sooner within
     * whatever its nerve allows. Piecewise-linear through three
     * anchors: {@link #RANGED_DELAY_HIGH 0} ticks at ff 10 (fire as fast as the weapon
     * allows), {@link #RANGED_DELAY_MID 40} (2s) at ff 5, {@link #RANGED_DELAY_LOW 200}
     * (10s) at ff 0. The curve steepens below the pivot (8 ticks/point above, 32 below)
     * on purpose: a low-fightFlight mob usually flees rather than fights, so its slow
     * firerate seldom comes into play. Clamped to {@code [0, 10]}.
     */
    public static int rangedAttackExtraDelayTicks(int fightFlight, int reactionSpeed) {
        return ReactionSpeed.ticks(reactionSpeed, rangedAttackExtraDelayTicks(fightFlight));
    }

    /**
     * The fightFlight-only cadence, at the neutral reaction speed. Retained as the pure
     * two-anchor curve the tests pin; live goal code calls the
     * {@link #rangedAttackExtraDelayTicks(int, int) reaction-aware overload}.
     */
    public static int rangedAttackExtraDelayTicks(int fightFlight) {
        int ff = clampTrait(fightFlight);
        if (ff >= RANGED_DELAY_PIVOT) {
            // ff 10 → HIGH (0), ff 5 → MID (40): lerp down across the top half.
            return RANGED_DELAY_MID
                + (RANGED_DELAY_HIGH - RANGED_DELAY_MID) * (ff - RANGED_DELAY_PIVOT) / (DispositionTraits.MAX - RANGED_DELAY_PIVOT);
        }
        // ff 5 → MID (40), ff 0 → LOW (200): steeper lerp across the bottom half.
        return RANGED_DELAY_MID
            + (RANGED_DELAY_LOW - RANGED_DELAY_MID) * (RANGED_DELAY_PIVOT - ff) / (RANGED_DELAY_PIVOT - DispositionTraits.MIN);
    }

    /** Clamp a trait to {@code [MIN, MAX]} — defensive; traits are already bounded at source. */
    private static int clampTrait(int trait) {
        if (trait < DispositionTraits.MIN) return DispositionTraits.MIN;
        if (trait > DispositionTraits.MAX) return DispositionTraits.MAX;
        return trait;
    }

    // ---- "Can I win?" assessment (Phase C: the mid fight/flight band) ------
    //
    // The hard cut at FF_FIGHT (5) holds at the extremes, but the middle of the
    // fight/flight range (MID_BAND_LO..MID_BAND_HI) no longer decides fight-vs-flee
    // by trait alone: it weighs the mob's combat power against the target's, so a
    // middling mob fights a weaker foe and flees a stronger one. These methods are
    // pure (primitives only); the entity computes the two power estimates and the
    // existing resolve()/onHurt(int) above are left untouched.

    /** Lower end (inclusive) of the band where fight-vs-flee is decided by power. */
    static final int MID_BAND_LO = 4;
    /** Upper end (inclusive) of that band; at/above {@code MID_BAND_HI + 1} the mob always fights. */
    static final int MID_BAND_HI = 6;
    /** Power contributed per point of weapon {@link EquipmentEvaluator#score}. */
    static final double POWER_WEIGHT_WEAPON = 1.5;
    /** Power contributed per point of armour value. */
    static final double POWER_WEIGHT_ARMOR = 1.0;
    /** How much the required self/target power ratio eases per point of fight/flight past the pivot. */
    static final double RATIO_PER_FF = 0.1;

    /**
     * A crude combat-power estimate: effective {@code health} plus weighted weapon
     * and armour scores. {@code weaponScore} is an {@link EquipmentEvaluator#score}
     * of the held weapon; {@code armorScore} is the entity's armour value. Higher
     * means a better fighter. Tunable via the weight constants.
     */
    public static double combatPower(float health, double weaponScore, double armorScore) {
        return health + weaponScore * POWER_WEIGHT_WEAPON + armorScore * POWER_WEIGHT_ARMOR;
    }

    /**
     * The self/target power ratio a mid-band mob needs before it commits to a
     * fight, easing around the {@link #FF_FIGHT} pivot: a braver mob (ff 6) fights
     * even when slightly outmatched (0.9×), a more timid one (ff 4) needs an edge
     * (1.1×).
     */
    static double requiredRatio(int fightFlight) {
        return 1.0 + (FF_FIGHT - fightFlight) * RATIO_PER_FF;
    }

    /** True if {@code selfPower} clears the {@link #requiredRatio} bar against {@code targetPower}. */
    public static boolean canWin(int fightFlight, double selfPower, double targetPower) {
        return selfPower >= targetPower * requiredRatio(fightFlight);
    }

    /** The fight/flight band whose fight-vs-flee choice is decided by power rather than the hard cut. */
    static boolean isMidBand(int fightFlight) {
        return fightFlight >= MID_BAND_LO && fightFlight <= MID_BAND_HI;
    }

    /**
     * Refine a proactive {@link #resolve} verdict with the "can I win?" check.
     * Only a mid-band ({@link #isMidBand}) {@link Reaction#FIGHT} or {@link
     * Reaction#FLEE} is reconsidered — it flips to fight or flee per {@link
     * #canWin}. Outside the band the extremes are unconditional and the base
     * verdict already matches, so it (and every non-combat reaction) passes through
     * unchanged. Pure: the entity supplies the two power estimates.
     */
    public static Reaction applyWinAssessment(Reaction base, int fightFlight,
                                              double selfPower, double targetPower) {
        if ((base == Reaction.FIGHT || base == Reaction.FLEE) && isMidBand(fightFlight)) {
            return canWin(fightFlight, selfPower, targetPower) ? Reaction.FIGHT : Reaction.FLEE;
        }
        return base;
    }

    /**
     * Power-aware variant of {@link #onHurt(int)}: a mid-band mob retaliates only
     * if it reckons it can win, otherwise it breaks off and flees. Outside the band
     * it delegates to the hard-cut {@link #onHurt(int)} (brave always retaliates,
     * timid always flees).
     */
    public static HurtResponse onHurt(int fightFlight, double selfPower, double targetPower) {
        if (isMidBand(fightFlight)) {
            return canWin(fightFlight, selfPower, targetPower)
                ? HurtResponse.RETALIATE : HurtResponse.FLEE;
        }
        return onHurt(fightFlight);
    }

    /** A defender bails only if the foe's power exceeds the mob's by more than this factor. */
    static final double DEFEND_HOPELESS_RATIO = 2.0;

    /**
     * Whether a mob should wade in to defend a loved one against {@code foe} — true
     * unless the fight is hopeless (foe power more than {@link #DEFEND_HOPELESS_RATIO}×
     * the mob's). Deliberately <em>independent of fight/flight</em>: love and loyalty
     * drive defending, so even a timid mob joins for a friend unless badly outmatched.
     * (Contrast {@link #canWin}, which governs the mob's own fights.)
     */
    public static boolean defendIsWorthwhile(double selfPower, double foePower) {
        return selfPower * DEFEND_HOPELESS_RATIO >= foePower;
    }

    static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }
}
