package games.brennan.playermob.entity;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

import java.util.EnumMap;
import java.util.Map;

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
    /** Per-channel reaction speeds (see {@link ReactionChannel}); absent on wild mobs and old saves. */
    public static final String TAG_REACTION_SPEEDS = "ReactionSpeeds";
    /** The Taming hobby (0–10); absent on wild mobs and saves from before it existed ⇒ 0. */
    public static final String TAG_TAMING = "Taming";

    public static final int MIN = 0;
    public static final int MAX = 10;
    public static final int DEFAULT = 5;

    private int fightFlight = DEFAULT;
    private int friendliness = DEFAULT;
    private int reactionSpeed = DEFAULT;
    private boolean fightFlightExplicit = false;
    private boolean friendlinessExplicit = false;
    private boolean reactionSpeedExplicit = false;
    /**
     * The Taming <em>hobby</em> — how drawn this mob is to feeding animals (see
     * {@code FeedAnimalsGoal}). Unlike the three traits above it is never rolled: a wild mob has no
     * hobby (0) and behaves exactly as it did before hobbies existed; an echo carries its player's
     * score, and {@code /summon … {Taming:n}} sets one by hand.
     */
    private int taming = 0;
    /** Channels with a value of their own; any channel missing here reads the overall trait. */
    private final Map<ReactionChannel, Integer> channelSpeeds = new EnumMap<>(ReactionChannel.class);

    public int fightFlight() {
        return fightFlight;
    }

    public int friendliness() {
        return friendliness;
    }

    public int reactionSpeed() {
        return reactionSpeed;
    }

    /** The Taming hobby, {@code [0, 10]}; 0 means the mob never feeds animals. */
    public int taming() {
        return taming;
    }

    /** Set the Taming hobby (clamped to [0,10]). */
    public void setTaming(int value) {
        taming = clamp(value);
    }

    /**
     * The reaction speed for one kind of action: the channel's own value when the mob has one
     * (an echo, scored from its player's life), otherwise the overall trait — so a mob without
     * channel values behaves exactly as it did before channels existed.
     */
    public int reactionSpeed(ReactionChannel channel) {
        if (channel == null || channel == ReactionChannel.OVERALL) {
            return reactionSpeed;
        }
        Integer own = channelSpeeds.get(channel);
        return own == null ? reactionSpeed : own;
    }

    /** True if {@code channel} carries a value of its own rather than following the overall trait. */
    public boolean hasOwnReactionSpeed(ReactionChannel channel) {
        return channelSpeeds.containsKey(channel);
    }

    /** Give one channel its own value (clamped). {@link ReactionChannel#OVERALL} sets the overall trait. */
    public void setReactionSpeed(ReactionChannel channel, int value) {
        if (channel == null || channel == ReactionChannel.OVERALL) {
            setReactionSpeed(value);
            return;
        }
        channelSpeeds.put(channel, clamp(value));
    }

    /**
     * Move the overall trait and every channel that has its own value by {@code delta} — how the
     * trait editor's one reaction-speed control acts on an echo with per-channel values.
     */
    public void shiftReactionSpeed(int delta) {
        setReactionSpeed(reactionSpeed + delta);
        channelSpeeds.replaceAll((channel, value) -> clamp(value + delta));
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
        tag.putInt(TAG_TAMING, taming);
        if (!channelSpeeds.isEmpty()) {
            CompoundTag channels = new CompoundTag();
            channelSpeeds.forEach((channel, value) -> channels.putInt(channel.key(), value));
            tag.put(TAG_REACTION_SPEEDS, channels);
        }
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
        if (tag.contains(TAG_TAMING)) {
            setTaming(NbtCompat.getIntOr(tag, TAG_TAMING, 0));
        }
        // Per-channel values: absent on wild mobs and saves from before channels — every channel
        // then follows the overall trait, exactly as before.
        if (tag.contains(TAG_REACTION_SPEEDS)) {
            CompoundTag channels = NbtCompat.getCompoundOrEmpty(tag, TAG_REACTION_SPEEDS);
            channelSpeeds.clear();
            for (ReactionChannel channel : ReactionChannel.SPECIFIC) {
                if (channels.contains(channel.key())) {
                    channelSpeeds.put(channel, clamp(NbtCompat.getIntOr(channels, channel.key(), reactionSpeed)));
                }
            }
        }
    }

    static int clamp(int value) {
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }
}
