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
 *   <li><b>Animals / villagers</b> → greet if friendly, else ignore (never proactively
 *       attacked; hunger-hunting is a separate goal).</li>
 *   <li><b>Players / PlayerMobs</b>:
 *     <ul>
 *       <li>feeling ≤ {@link #FEELING_HATE} ("hate") → fight/flee within {@link #HATE_RANGE},
 *           overriding nature regardless of friendliness;</li>
 *       <li>else friendliness ≥ {@link #FRIEND_GREET} → greet (the gift sub-step,
 *           gated on feeling ≥ {@link #FEELING_LOVE}, is decided by the goal);</li>
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
     * Witnessed-attack admiration. A mob seeing attacker A hit victim V comes to like A
     * when its feeling toward V is at or below {@code fightFlight − }{@link #WITNESS_APPROVE_MARGIN}:
     * the more aggressive the mob, the higher the victim it'll tolerate being attacked.
     */
    static final int WITNESS_APPROVE_MARGIN = 3;
    /**
     * How strongly friendliness tempers admiration: each point of friendliness above neutral (5)
     * raises the admire threshold (cold mobs applaud more readily, warm mobs less; each point below
     * lowers it). 0 ignores friendliness (pure fight/flight); 1 gives it full weight.
     */
    static final float WITNESS_FRIENDLINESS_WEIGHT = 1.0F;
    /** Floor admiration an approving witness grants the attacker per witnessed attack. */
    static final float ADMIRE_BASE = 0.5F;
    /** Extra admiration per point the victim's feeling sits below neutral (more disliked → bigger bump). */
    static final float ADMIRE_PER_POINT = 0.1F;
    /** Ceiling on a single admiration bump (reached when the witness outright hates the victim). */
    static final float ADMIRE_MAX = 0.7F;

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
                if (friendliness >= FRIEND_GREET) {
                    return Reaction.GREET; // gift sub-step (feeling >= FEELING_LOVE) decided by the goal
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
     * Whether a mob, witnessing attacker A hit victim V, comes to <em>like</em> A. True iff its
     * feeling toward V is at or below an aggression threshold <b>and</b> below {@link #FEELING_LOVE}.
     * The threshold is {@code fightFlight − }{@link #WITNESS_APPROVE_MARGIN}, then tempered by
     * friendliness ({@link #WITNESS_FRIENDLINESS_WEIGHT} per point above neutral 5): a warmer mob
     * must dislike V more before it applauds, a colder one applauds more readily — so the cruel
     * {@code 9/1} archetype admires almost any beating while a brave-but-warm {@code 9/9} only
     * cheers an attack on someone it nearly hates. The {@code < FEELING_LOVE} clause keeps this
     * disjoint from harm-a-loved-one (feeling ≥ 7) at any traits, so one witnessed attack is only
     * ever harm OR admire.
     */
    public static boolean approvesWitnessedAttack(int fightFlight, int friendliness,
                                                  float feelingTowardVictim) {
        float threshold = fightFlight - WITNESS_APPROVE_MARGIN
            - WITNESS_FRIENDLINESS_WEIGHT * (friendliness - DispositionTraits.DEFAULT);
        return feelingTowardVictim <= threshold && feelingTowardVictim < FEELING_LOVE;
    }

    /**
     * The small, positive feeling an approving witness grants the attacker — an {@link #ADMIRE_BASE}
     * floor that grows by {@link #ADMIRE_PER_POINT} for each point the victim's feeling sits below
     * neutral (the more the mob dislikes the victim, the more it admires the attacker), capped at
     * {@link #ADMIRE_MAX}. Assumes the caller has confirmed {@link #approvesWitnessedAttack}.
     */
    public static float admireBonus(float feelingTowardVictim) {
        float dislike = Math.max(0.0F, DispositionTraits.DEFAULT - feelingTowardVictim);
        return Math.min(ADMIRE_MAX, ADMIRE_BASE + ADMIRE_PER_POINT * dislike);
    }

    static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }
}
