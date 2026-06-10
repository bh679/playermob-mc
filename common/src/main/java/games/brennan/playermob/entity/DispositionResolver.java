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

    static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }
}
