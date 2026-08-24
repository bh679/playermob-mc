package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

/**
 * One life's running tally of how a player has treated PlayerMobs — the
 * <em>inverse</em> of the mob-side {@link games.brennan.playermob.entity.FeelingLedger}.
 * Where the ledger records how a mob feels about a player, this records what the
 * player <em>did</em>: blows landed, mobs killed, kind gestures, cruelties.
 *
 * <p>Immutable value type (mirrors {@code FeelingRecord}): every {@link #credit}
 * returns a fresh record, so the live store can swap one in without aliasing.
 * On the player's death the accumulated record is distilled into a
 * {@link DispositionTraits} via {@link #toTraits()} — "you become, to the world,
 * what you were to it" — and the reincarnated PlayerMob is born with those traits.</p>
 *
 * <p>Pure (no Minecraft world) so the derivation is unit-testable — see
 * {@code PlayerLifeRecordTest}.</p>
 */
public final class PlayerLifeRecord {

    // ---- Kindness weights (per gesture) -----------------------------------
    /** Feeling-neutral kindness added by one crouch-greet aimed at a mob. */
    static final float CROUCH_KINDNESS = 0.5F;
    /** Kindness added per carriage travelled alongside a mob (Dungeon Train). */
    static final float TRAVEL_KINDNESS = 0.2F;
    /** Kindness added for stepping in to defend a mob from a hostile. */
    static final float DEFEND_KINDNESS = 1.0F;
    /**
     * Kindness added for taming an animal. The heaviest single gesture in the list: every other
     * kindness is a moment, while taming is a standing commitment to keep something alive.
     */
    static final float TAME_KINDNESS = 2.0F;

    // ---- Trait derivation (toTraits) --------------------------------------
    /** Aggression a single kill contributes, on top of the killing blow's damage. */
    static final double KILL_AGGRESSION = 5.0;
    /** Fight/Flight points gained per unit of aggression (damage + kills). */
    static final double AGGRESSION_TO_FIGHT = 0.1;
    /**
     * Fight/Flight points <em>lost</em> per unit of timidity — damage taken from a mob and never
     * answered, plus {@code FeelingRecord.ESCAPE_TIMIDITY} for each mob broken away from clean.
     * Deliberately equal to {@link #AGGRESSION_TO_FIGHT}: ten damage endured weighs exactly as
     * much as ten damage dealt, in the opposite direction.
     */
    static final double TIMIDITY_TO_FLIGHT = 0.1;

    /** Cruelty per point of damage dealt to mobs. */
    static final double DAMAGE_CRUELTY = 0.1;
    /** Cruelty per mob killed. */
    static final double KILL_CRUELTY = 2.0;
    /** Cruelty per witnessed harm of a mob's loved one. */
    static final double HARM_CRUELTY = 1.0;
    /** Friendliness points gained per unit of kindness. */
    static final double KINDNESS_TO_FRIENDLY = 1.0;

    /**
     * Weight applied to damage and kills dealt in <em>self-defence</em> — against a mob that
     * had already taken combat intent toward the player (see {@code FeelingRecord.provoked}).
     * Fighting back still says something about you, but at a tenth of the weight of a fight
     * you started: a player who only ever defends themselves reincarnates near neutral.
     */
    static final double DEFENSIVE_SCALE = 0.1;

    // ---- NBT keys ---------------------------------------------------------
    static final String TAG_DAMAGE = "DamageDealt";
    static final String TAG_KILLS = "Kills";
    static final String TAG_KINDNESS = "Kindness";
    static final String TAG_HARMS = "Harms";
    static final String TAG_ATTACKS = "Attacks";
    static final String TAG_DEFENSIVE_DAMAGE = "DefensiveDamage";
    static final String TAG_DEFENSIVE_KILLS = "DefensiveKills";
    static final String TAG_TIMIDITY = "Timidity";

    /** A fresh life — no conduct recorded yet. */
    public static final PlayerLifeRecord EMPTY =
        new PlayerLifeRecord(0.0F, 0, 0.0F, 0, 0, 0.0F, 0, 0.0F);

    private final float damageDealt;
    private final int kills;
    private final float kindness;
    private final int harms;
    private final int attacks;
    /** Subset of {@link #damageDealt} dealt to mobs that had already picked the fight. */
    private final float defensiveDamage;
    /** Subset of {@link #kills} taken in self-defence. */
    private final int defensiveKills;
    /** Flight banked by not fighting back: damage endured unanswered, plus escapes made. */
    private final float timidity;

    PlayerLifeRecord(float damageDealt, int kills, float kindness, int harms, int attacks,
                     float defensiveDamage, int defensiveKills, float timidity) {
        this.damageDealt = damageDealt;
        this.kills = kills;
        this.kindness = kindness;
        this.harms = harms;
        this.attacks = attacks;
        this.defensiveDamage = defensiveDamage;
        this.defensiveKills = defensiveKills;
        this.timidity = Math.max(0.0F, timidity);
    }

    public float damageDealt() { return damageDealt; }
    public int kills() { return kills; }
    public float kindness() { return kindness; }
    public int harms() { return harms; }
    public int attacks() { return attacks; }
    /** Of {@link #damageDealt}, how much landed on a mob that struck or stalked first. */
    public float defensiveDamage() { return defensiveDamage; }
    /** Of {@link #kills}, how many were self-defence. */
    public int defensiveKills() { return defensiveKills; }
    /** Damage endured from mobs that never got a blow back, plus a bonus per mob escaped. */
    public float timidity() { return timidity; }

    /** True for a life with no recorded conduct (used to skip empty saves). */
    public boolean isEmpty() {
        return damageDealt == 0.0F && kills == 0 && kindness == 0.0F && harms == 0 && attacks == 0
            && timidity == 0.0F;
    }

    /** The kind of player→mob action being credited; routes a magnitude to the right tally. */
    public enum Signal { ATTACK, KILL, CROUCH, GIFT, TRAVEL, DEFEND, HARM, FLEE, TAME }

    /** As {@link #credit(Signal, float, boolean)}, treating the action as unprovoked. */
    public PlayerLifeRecord credit(Signal signal, float magnitude) {
        return credit(signal, magnitude, false);
    }

    /**
     * Return a fresh record with {@code signal} applied. {@code magnitude} is the
     * damage amount for {@link Signal#ATTACK}, the gift value for {@link Signal#GIFT}, and the
     * Flight points banked (or, when negative, handed back) for {@link Signal#FLEE}; it is
     * ignored for the fixed-weight signals.
     *
     * <p>{@code defensive} marks an {@link Signal#ATTACK}/{@link Signal#KILL} against a mob
     * that had already taken combat intent toward the player. The blow is tallied in full
     * <em>and</em> into the defensive subset, so {@link #toTraits()} can weigh it at
     * {@link #DEFENSIVE_SCALE}. It is ignored for every other signal — there is no such thing
     * as a defensive gift.</p>
     */
    public PlayerLifeRecord credit(Signal signal, float magnitude, boolean defensive) {
        return switch (signal) {
            case ATTACK -> {
                float dealt = Math.max(0.0F, magnitude);
                yield new PlayerLifeRecord(damageDealt + dealt, kills, kindness, harms, attacks + 1,
                    defensive ? defensiveDamage + dealt : defensiveDamage, defensiveKills, timidity);
            }
            case KILL   -> new PlayerLifeRecord(damageDealt, kills + 1, kindness, harms, attacks,
                               defensiveDamage, defensive ? defensiveKills + 1 : defensiveKills,
                               timidity);
            case CROUCH -> withKindness(CROUCH_KINDNESS);
            case TRAVEL -> withKindness(TRAVEL_KINDNESS);
            case DEFEND -> withKindness(DEFEND_KINDNESS);
            case TAME   -> withKindness(TAME_KINDNESS);
            case GIFT   -> withKindness(Math.max(0.0F, magnitude));
            case HARM   -> new PlayerLifeRecord(damageDealt, kills, kindness, harms + 1, attacks,
                               defensiveDamage, defensiveKills, timidity);
            // Signed: a negative magnitude is a mob handing back what it had banked, once the
            // player finally hit it. The constructor floors the tally at 0.
            case FLEE   -> new PlayerLifeRecord(damageDealt, kills, kindness, harms, attacks,
                               defensiveDamage, defensiveKills, timidity + magnitude);
        };
    }

    private PlayerLifeRecord withKindness(float delta) {
        return new PlayerLifeRecord(damageDealt, kills, kindness + delta, harms, attacks,
            defensiveDamage, defensiveKills, timidity);
    }

    /**
     * Distil this life into the two locked traits the reincarnated mob is born with.
     * <ul>
     *   <li><b>Fight/Flight</b> rises with combat aggression (damage dealt + kills) and falls
     *       with timidity — damage taken from a mob that never got a blow back, plus a bonus per
     *       mob broken away from clean. The two weigh the same per point, so a life reads
     *       neutral 5 only when it never met violence, or gave back exactly what it took.</li>
     *   <li><b>Friendliness</b> = neutral 5 shifted up by kindness and down by cruelty
     *       (damage, kills, harming loved ones). A cruel life reincarnates unfriendly;
     *       a kind one, welcoming.</li>
     * </ul>
     * Both combat terms weigh self-defence — blows against a mob that picked the fight —
     * at {@link #DEFENSIVE_SCALE}: killing what came for you is neither aggression nor
     * cruelty in the way hunting something down is. {@code harms} is never discounted;
     * there is no self-defence in hurting someone's loved one.
     * Both are rounded and clamped to {@code [0, 10]} and marked explicit so the
     * mob's spawn roll leaves them untouched.
     */
    public DispositionTraits toTraits() {
        // Defensive tallies are subsets of the totals: full-weight the rest, tenth-weight those.
        double weightedDamage = (damageDealt - defensiveDamage) + defensiveDamage * DEFENSIVE_SCALE;
        double weightedKills = (kills - defensiveKills) + defensiveKills * DEFENSIVE_SCALE;

        double aggression = weightedDamage + weightedKills * KILL_AGGRESSION;
        double cruelty = weightedDamage * DAMAGE_CRUELTY + weightedKills * KILL_CRUELTY
            + harms * HARM_CRUELTY;

        int fightFlight = clampTrait(DispositionTraits.DEFAULT
            + aggression * AGGRESSION_TO_FIGHT
            - timidity * TIMIDITY_TO_FLIGHT);
        int friendliness = clampTrait(DispositionTraits.DEFAULT + kindness * KINDNESS_TO_FRIENDLY - cruelty);

        DispositionTraits traits = new DispositionTraits();
        traits.set(fightFlight, friendliness);
        return traits;
    }

    private static int clampTrait(double value) {
        long rounded = Math.round(value);
        if (rounded < DispositionTraits.MIN) return DispositionTraits.MIN;
        if (rounded > DispositionTraits.MAX) return DispositionTraits.MAX;
        return (int) rounded;
    }

    public void save(CompoundTag tag) {
        tag.putFloat(TAG_DAMAGE, damageDealt);
        tag.putInt(TAG_KILLS, kills);
        tag.putFloat(TAG_KINDNESS, kindness);
        tag.putInt(TAG_HARMS, harms);
        tag.putInt(TAG_ATTACKS, attacks);
        tag.putFloat(TAG_DEFENSIVE_DAMAGE, defensiveDamage);
        tag.putInt(TAG_DEFENSIVE_KILLS, defensiveKills);
        tag.putFloat(TAG_TIMIDITY, timidity);
    }

    /** Read a record back; missing keys default to zero (forward/backward compatible). */
    public static PlayerLifeRecord load(CompoundTag tag) {
        return new PlayerLifeRecord(
            NbtCompat.getFloatOr(tag, TAG_DAMAGE, 0f),
            NbtCompat.getIntOr(tag, TAG_KILLS, 0),
            NbtCompat.getFloatOr(tag, TAG_KINDNESS, 0f),
            NbtCompat.getIntOr(tag, TAG_HARMS, 0),
            NbtCompat.getIntOr(tag, TAG_ATTACKS, 0),
            // Missing on saves from before the self-defence split ⇒ 0 ⇒ every past blow
            // scores as offensive, exactly as that build scored it.
            NbtCompat.getFloatOr(tag, TAG_DEFENSIVE_DAMAGE, 0f),
            NbtCompat.getIntOr(tag, TAG_DEFENSIVE_KILLS, 0),
            // Missing on saves from before restraint was scored ⇒ 0 ⇒ that life reads exactly
            // as aggressive as the build that wrote it scored it.
            NbtCompat.getFloatOr(tag, TAG_TIMIDITY, 0f));
    }
}
