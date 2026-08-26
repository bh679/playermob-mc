package games.brennan.playermob.entity;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * The three locked personal traits a {@link PlayerMobEntity} is born with:
 * <b>fight/flight</b> (how it reacts when pushed — high fights, low flees),
 * <b>friendliness</b> (how welcoming vs. territorial it is), and <b>reaction speed</b>
 * (how quickly it acts on whatever it has decided — see {@link ReactionSpeed}). All are
 * integers in {@code [0, 10]}, set once at spawn and never changed afterward
 * (relationships evolve via {@link FeelingLedger}, not these). Extracted from the entity
 * so this slice is unit-testable without a live world — mirrors the old
 * {@code PersonalityProfile}.
 *
 * <p>The first two decide <em>what</em> the mob does ({@link DispositionResolver});
 * reaction speed decides only <em>how fast</em> it does it, so it composes with the other
 * two rather than overlapping them.</p>
 *
 * <p><b>Explicit-set tracking.</b> Each trait remembers whether it was set
 * deliberately (NBT load, a spawn-egg {@code entity_data} component, or
 * {@code /summon}). {@link #rollIfUnset(RandomSource)} — called from
 * {@code finalizeSpawn} — then randomises only the traits left unset. This is
 * what keeps an archetype egg's pinned values from being clobbered by the spawn
 * roll, and is robust to whether {@code finalizeSpawn} runs before or after the
 * egg's NBT is merged (load is the last writer either way). A {@code /summon}
 * that pins only one trait still rolls the others.</p>
 *
 * <p>Constructor default {@link #DEFAULT} (neutral) for every trait, so a save written
 * before a given trait existed — which has no key for it, and whose {@code finalizeSpawn}
 * never re-runs on load — degrades to a balanced mob rather than a degenerate 0. That
 * covers both legacy pre-trait saves and saves made before reaction speed was added.</p>
 */
public final class DispositionTraits {

    public static final String TAG_FIGHT_FLIGHT = "FightFlight";
    public static final String TAG_FRIENDLINESS = "Friendliness";
    public static final String TAG_REACTION_SPEED = "ReactionSpeed";

    public static final int MIN = 0;
    public static final int MAX = 10;
    public static final int DEFAULT = 5;

    private int fightFlight = DEFAULT;
    private int friendliness = DEFAULT;
    private int reactionSpeed = DEFAULT;
    private boolean fightFlightExplicit = false;
    private boolean friendlinessExplicit = false;
    private boolean reactionSpeedExplicit = false;

    public int fightFlight() {
        return fightFlight;
    }

    public int friendliness() {
        return friendliness;
    }

    public int reactionSpeed() {
        return reactionSpeed;
    }

    /** Set fight/flight and mark it explicit (won't be re-rolled at spawn). Clamps to [0,10]. */
    public void setFightFlight(int value) {
        fightFlight = clamp(value);
        fightFlightExplicit = true;
    }

    /** Set friendliness and mark it explicit (won't be re-rolled at spawn). Clamps to [0,10]. */
    public void setFriendliness(int value) {
        friendliness = clamp(value);
        friendlinessExplicit = true;
    }

    /** Set reaction speed and mark it explicit (won't be re-rolled at spawn). Clamps to [0,10]. */
    public void setReactionSpeed(int value) {
        reactionSpeed = clamp(value);
        reactionSpeedExplicit = true;
    }

    /** Convenience: set every trait explicit (e.g. an archetype preset). */
    public void set(int fightFlight, int friendliness, int reactionSpeed) {
        setFightFlight(fightFlight);
        setFriendliness(friendliness);
        setReactionSpeed(reactionSpeed);
    }

    /** True only when every trait was set deliberately (load / egg / summon). */
    public boolean isExplicit() {
        return fightFlightExplicit && friendlinessExplicit && reactionSpeedExplicit;
    }

    /**
     * Uniformly randomise each trait in {@code [0, 10]} that was not explicitly
     * set. Called once from {@code finalizeSpawn}.
     */
    public void rollIfUnset(RandomSource random) {
        if (!fightFlightExplicit) {
            fightFlight = random.nextInt(MAX + 1);
        }
        if (!friendlinessExplicit) {
            friendliness = random.nextInt(MAX + 1);
        }
        if (!reactionSpeedExplicit) {
            reactionSpeed = random.nextInt(MAX + 1);
        }
    }

    public void save(CompoundTag tag) {
        tag.putInt(TAG_FIGHT_FLIGHT, fightFlight);
        tag.putInt(TAG_FRIENDLINESS, friendliness);
        tag.putInt(TAG_REACTION_SPEED, reactionSpeed);
    }

    /**
     * Read whichever trait keys are present (each marks that trait explicit and
     * is clamped). Missing keys keep the constructor default — so a legacy save
     * with none of them loads as a neutral mob, and a save written before reaction
     * speed existed keeps its two stored traits and loads neutral for the third.
     * Legacy {@code *Personality} keys are never read.
     */
    public void load(CompoundTag tag) {
        if (tag.contains(TAG_FIGHT_FLIGHT)) {
            setFightFlight(NbtCompat.getIntOr(tag, TAG_FIGHT_FLIGHT, DEFAULT));
        }
        if (tag.contains(TAG_FRIENDLINESS)) {
            setFriendliness(NbtCompat.getIntOr(tag, TAG_FRIENDLINESS, DEFAULT));
        }
        if (tag.contains(TAG_REACTION_SPEED)) {
            setReactionSpeed(NbtCompat.getIntOr(tag, TAG_REACTION_SPEED, DEFAULT));
        }
    }

    static int clamp(int value) {
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }
}
