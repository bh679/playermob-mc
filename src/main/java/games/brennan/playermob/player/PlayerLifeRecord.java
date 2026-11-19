package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

import java.util.function.UnaryOperator;

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

    /**
     * Fight/Flight points gained per act of sabotage — bringing an explosive aboard a carriage
     * somebody built by hand. Not routed through {@code aggression}: sabotage is aimed at a
     * <em>place</em>, never at a mob, so it must not read as cruelty toward the mobs the player
     * actually met. It moves Fight/Flight and nothing else.
     */
    static final double SABOTAGE_TO_FIGHT = 0.5;
    /**
     * Ceiling on the Fight/Flight one life can earn from sabotage alone — six acts reach it.
     * Capped because a stack of TNT is one decision, not sixty-four: without this, a single
     * demolition would pin the trait at maximum and drown out everything else the life did.
     * The tally itself is never capped, so the rate stays re-tunable.
     */
    static final double MAX_SABOTAGE_FIGHT = 3.0;

    /**
     * Friendliness points gained per animal fed by hand — breeding it, growing its young, healing
     * it, gentling a horse. Scored on its own term rather than as {@code kindness}: feeding is
     * cheap and repeatable, so it is capped where a gift or a defence is not.
     */
    static final double FEED_TO_FRIENDLY = 0.25;
    /**
     * Ceiling on the Friendliness one life can earn from feeding alone — eight feeds reach it.
     * Capped so a breeding pen can nudge an echo friendly without farming it to maximum. The
     * tally itself is never capped, so the rate stays re-tunable.
     */
    static final double MAX_FEED_FRIENDLY = 2.0;

    // ---- NBT keys ---------------------------------------------------------
    static final String TAG_DAMAGE = "DamageDealt";
    static final String TAG_KILLS = "Kills";
    static final String TAG_KINDNESS = "Kindness";
    static final String TAG_HARMS = "Harms";
    static final String TAG_ATTACKS = "Attacks";
    static final String TAG_DEFENSIVE_DAMAGE = "DefensiveDamage";
    static final String TAG_DEFENSIVE_KILLS = "DefensiveKills";
    static final String TAG_TIMIDITY = "Timidity";
    static final String TAG_SABOTAGE = "Sabotage";
    static final String TAG_FED = "Fed";

    /** A fresh life — no conduct recorded yet. */
    public static final PlayerLifeRecord EMPTY =
        new PlayerLifeRecord(0.0F, 0, 0.0F, 0, 0, 0.0F, 0, 0.0F, 0.0F, 0.0F, ReflexTally.EMPTY,
            TamingTally.EMPTY);

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
    /** Acts of sabotage against a build somebody made by hand. */
    private final float sabotage;
    /** Animals this life fed by hand. */
    private final float fed;
    /** Evidence of the player's reflexes — distilled into reaction speed. */
    private final ReflexTally reflexes;
    /** Animals this life tamed, bred and fed — distilled into the echo's Taming hobby. */
    private final TamingTally taming;

    PlayerLifeRecord(float damageDealt, int kills, float kindness, int harms, int attacks,
                     float defensiveDamage, int defensiveKills, float timidity, float sabotage,
                     float fed, ReflexTally reflexes, TamingTally taming) {
        this.damageDealt = damageDealt;
        this.kills = kills;
        this.kindness = kindness;
        this.harms = harms;
        this.attacks = attacks;
        this.defensiveDamage = defensiveDamage;
        this.defensiveKills = defensiveKills;
        this.timidity = Math.max(0.0F, timidity);
        this.sabotage = Math.max(0.0F, sabotage);
        this.fed = Math.max(0.0F, fed);
        this.reflexes = reflexes == null ? ReflexTally.EMPTY : reflexes;
        this.taming = taming == null ? TamingTally.EMPTY : taming;
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
    /** How many times this life brought an explosive into a carriage somebody built. */
    public float sabotage() { return sabotage; }
    /** How many times this life fed an animal by hand. */
    public float fed() { return fed; }
    /** This life's reflex evidence (see {@link ReflexTally}). */
    public ReflexTally reflexes() { return reflexes; }
    /** This life's animal-handling evidence (see {@link TamingTally}). */
    public TamingTally taming() { return taming; }

    /** True for a life with no recorded conduct (used to skip empty saves). */
    public boolean isEmpty() {
        return damageDealt == 0.0F && kills == 0 && kindness == 0.0F && harms == 0 && attacks == 0
            && timidity == 0.0F && sabotage == 0.0F && fed == 0.0F && reflexes.isEmpty()
            && taming.isEmpty();
    }

    /** The kind of player→mob action being credited; routes a magnitude to the right tally. */
    public enum Signal { ATTACK, KILL, CROUCH, GIFT, TRAVEL, DEFEND, HARM, FLEE, TAME, SABOTAGE, FEED }

    /** As {@link #credit(Signal, float, boolean)}, treating the action as unprovoked. */
    public PlayerLifeRecord credit(Signal signal, float magnitude) {
        return credit(signal, magnitude, false);
    }

    /**
     * Return a fresh record with {@code signal} applied. {@code magnitude} is the
     * damage amount for {@link Signal#ATTACK}, the gift value for {@link Signal#GIFT}, and the
     * Flight points banked (or, when negative, handed back) for {@link Signal#FLEE}, and the
     * number of acts for {@link Signal#SABOTAGE} and {@link Signal#FEED}; it is ignored for the fixed-weight signals.
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
                    defensive ? defensiveDamage + dealt : defensiveDamage, defensiveKills, timidity,
                    sabotage, fed, reflexes, taming);
            }
            case KILL   -> new PlayerLifeRecord(damageDealt, kills + 1, kindness, harms, attacks,
                               defensiveDamage, defensive ? defensiveKills + 1 : defensiveKills,
                               timidity, sabotage, fed, reflexes, taming);
            case CROUCH -> withKindness(CROUCH_KINDNESS);
            case TRAVEL -> withKindness(TRAVEL_KINDNESS);
            case DEFEND -> withKindness(DEFEND_KINDNESS);
            case TAME   -> withKindness(TAME_KINDNESS);
            case GIFT   -> withKindness(Math.max(0.0F, magnitude));
            case HARM   -> new PlayerLifeRecord(damageDealt, kills, kindness, harms + 1, attacks,
                               defensiveDamage, defensiveKills, timidity, sabotage, fed, reflexes, taming);
            // Signed: a negative magnitude is a mob handing back what it had banked, once the
            // player finally hit it. The constructor floors the tally at 0.
            case FLEE   -> new PlayerLifeRecord(damageDealt, kills, kindness, harms, attacks,
                               defensiveDamage, defensiveKills, timidity + magnitude, sabotage, fed, reflexes, taming);
            case SABOTAGE -> new PlayerLifeRecord(damageDealt, kills, kindness, harms, attacks,
                               defensiveDamage, defensiveKills, timidity,
                               sabotage + Math.max(0.0F, magnitude), fed, reflexes, taming);
            case FEED   -> new PlayerLifeRecord(damageDealt, kills, kindness, harms, attacks,
                               defensiveDamage, defensiveKills, timidity, sabotage,
                               fed + Math.max(0.0F, magnitude), reflexes, taming);
        };
    }

    /** Return a fresh record with {@code update} applied to the reflex tally. */
    public PlayerLifeRecord withReflexes(UnaryOperator<ReflexTally> update) {
        return new PlayerLifeRecord(damageDealt, kills, kindness, harms, attacks,
            defensiveDamage, defensiveKills, timidity, sabotage, fed, update.apply(reflexes), taming);
    }

    /** Return a fresh record with {@code update} applied to the taming tally. */
    public PlayerLifeRecord withTaming(UnaryOperator<TamingTally> update) {
        return new PlayerLifeRecord(damageDealt, kills, kindness, harms, attacks,
            defensiveDamage, defensiveKills, timidity, sabotage, fed, reflexes, update.apply(taming));
    }

    private PlayerLifeRecord withKindness(float delta) {
        return new PlayerLifeRecord(damageDealt, kills, kindness + delta, harms, attacks,
            defensiveDamage, defensiveKills, timidity, sabotage, fed, reflexes, taming);
    }

    /**
     * Distil this life into the three locked traits the reincarnated mob is born with.
     * <ul>
     *   <li><b>Fight/Flight</b> rises with combat aggression (damage dealt + kills) and falls
     *       with timidity — damage taken from a mob that never got a blow back, plus a bonus per
     *       mob broken away from clean. The two weigh the same per point, so a life reads
     *       neutral 5 only when it never met violence, or gave back exactly what it took.
     *       Sabotage — explosives brought into a carriage somebody built — pushes it up too,
     *       on its own capped term, and touches Friendliness not at all.</li>
     *   <li><b>Friendliness</b> = neutral 5 shifted up by kindness and down by cruelty
     *       (damage, kills, harming loved ones). A cruel life reincarnates unfriendly;
     *       a kind one, welcoming. Feeding animals by hand pushes it up too, on its own
     *       capped term.</li>
     *   <li><b>Reaction speed</b> comes from the player's reflexes, not their conduct — how fast
     *       they struck back, whether they landed the first hit, dodged, bridged, kept their hands
     *       busy and kept moving down the train. Scored as a weighted average pulled toward 5,
     *       so a life with no evidence reads exactly 5 — see {@link ReflexTally}.</li>
     * </ul>
     * Both combat terms weigh self-defence — blows against a mob that picked the fight —
     * at {@link #DEFENSIVE_SCALE}: killing what came for you is neither aggression nor
     * cruelty in the way hunting something down is. {@code harms} is never discounted;
     * there is no self-defence in hurting someone's loved one.
     * All three are rounded and clamped to {@code [0, 10]} and marked explicit so the
     * mob's spawn roll leaves them untouched.
     */
    public DispositionTraits toTraits() {
        // Defensive tallies are subsets of the totals: full-weight the rest, tenth-weight those.
        double weightedDamage = (damageDealt - defensiveDamage) + defensiveDamage * DEFENSIVE_SCALE;
        double weightedKills = (kills - defensiveKills) + defensiveKills * DEFENSIVE_SCALE;

        double aggression = weightedDamage + weightedKills * KILL_AGGRESSION;
        double cruelty = weightedDamage * DAMAGE_CRUELTY + weightedKills * KILL_CRUELTY
            + harms * HARM_CRUELTY;

        // Sabotage is scored apart from aggression and capped: see MAX_SABOTAGE_FIGHT.
        double sabotageFight = Math.min(sabotage * SABOTAGE_TO_FIGHT, MAX_SABOTAGE_FIGHT);

        int fightFlight = clampTrait(DispositionTraits.DEFAULT
            + aggression * AGGRESSION_TO_FIGHT
            - timidity * TIMIDITY_TO_FLIGHT
            + sabotageFight);
        // Feeding is scored apart from kindness and capped: see MAX_FEED_FRIENDLY.
        double feedFriendly = Math.min(fed * FEED_TO_FRIENDLY, MAX_FEED_FRIENDLY);

        int friendliness = clampTrait(DispositionTraits.DEFAULT + kindness * KINDNESS_TO_FRIENDLY
            + feedFriendly - cruelty);

        DispositionTraits traits = new DispositionTraits();
        traits.setFightFlight(fightFlight);
        traits.setFriendliness(friendliness);
        // Reaction speed reads the reflex tally, never the conduct above: damage or kindness is no
        // proxy for reflexes. Always set — the snapshot writes the key either way, and an unset
        // trait used to reach every echo as a pinned 5.
        traits.setReactionSpeed(reflexes.reactionSpeed());
        // …and one per kind of action, each leaning on that overall score (see ReflexTally).
        reflexes.channelSpeeds().forEach(traits::setReactionSpeed);
        // Taming is a hobby, scored from what the life did with animals — never from conduct.
        traits.setTaming(taming.score());
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
        tag.putFloat(TAG_SABOTAGE, sabotage);
        tag.putFloat(TAG_FED, fed);
        if (!reflexes.isEmpty()) {
            tag.put(ReflexTally.TAG, reflexes.save());
        }
        if (!taming.isEmpty()) {
            tag.put(TamingTally.TAG, taming.save());
        }
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
            NbtCompat.getFloatOr(tag, TAG_TIMIDITY, 0f),
            // Missing on saves from before sabotage was scored ⇒ 0 ⇒ that life reads exactly
            // as it did on the build that wrote it.
            NbtCompat.getFloatOr(tag, TAG_SABOTAGE, 0f),
            // Missing on saves from before feeding was scored ⇒ 0 ⇒ likewise unchanged.
            NbtCompat.getFloatOr(tag, TAG_FED, 0f),
            // Missing on saves from before reflexes were scored ⇒ EMPTY ⇒ reaction speed 5,
            // exactly what every echo carried on the build that wrote it.
            ReflexTally.load(NbtCompat.getCompoundOrEmpty(tag, ReflexTally.TAG)),
            // Missing on saves from before taming was scored ⇒ EMPTY ⇒ Taming 0.
            TamingTally.load(NbtCompat.getCompoundOrEmpty(tag, TamingTally.TAG)));
    }
}
