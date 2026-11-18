package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.ReactionChannel;
import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * One life's evidence of the player's <em>reflexes</em> — the input to the reincarnated echo's
 * reaction speeds: one overall {@code ReactionSpeed}, plus one per {@link ReactionChannel}. Held in
 * {@link PlayerLifeRecord} as one field so the record's constructor stays readable.
 *
 * <p>Immutable value type: every {@code with…} returns a fresh tally. Pure (no world) so the
 * scoring is unit-testable — see {@code ReflexTallyTest}. The live, tick-timed bookkeeping that
 * <em>produces</em> these samples lives in {@link ReflexTracker}; only finished samples land here.
 * The tallies sit in one array indexed by {@link Stat}, each with its NBT key.</p>
 *
 * <p><b>Scored as a rate, not a volume.</b> {@link #reactionSpeed()} is a weighted <em>average</em>
 * of per-signal scores, pulled toward neutral by a prior of {@link #PRIOR_WEIGHT} points at 5, so a
 * long life doesn't drift to 10 and a thin one stays near 5. No evidence at all scores exactly 5.</p>
 *
 * <p><b>Channels lean on the overall score, not on 5.</b> {@link #reactionSpeed(ReactionChannel)}
 * averages that channel's own evidence with {@link #CHANNEL_PRIOR_WEIGHT} points at the overall
 * score. A player who never drew a bow leaves an echo whose ranged speed is their general speed; a
 * player who shot all life leaves one with their own number. A channel with no evidence equals the
 * overall score exactly.</p>
 */
public final class ReflexTally {

    // ---- Scoring ----------------------------------------------------------
    /** Weight of the neutral-5 prior every life starts with (overall score). */
    static final double PRIOR_WEIGHT = 5.0;
    /** Weight of the overall-score prior each channel starts with. */
    static final double CHANNEL_PRIOR_WEIGHT = 5.0;

    /** Strike-back latency (ticks) scoring 10, 5 and 0 — linear between. */
    static final double STRIKE_FAST_TICKS = 6.0;
    static final double STRIKE_NEUTRAL_TICKS = 14.0;
    static final double STRIKE_SLOW_TICKS = 30.0;

    /** A first hit on a mob already coming for you: score and evidence weight. */
    static final double FIRST_HIT_SCORE = 8.0;
    static final double FIRST_HIT_WEIGHT = 2.0;
    /** A first hit followed by the kill, never hit back in between. */
    static final double CLEAN_FIRST_HIT_SCORE = 10.0;
    static final double CLEAN_FIRST_HIT_WEIGHT = 3.0;

    /** Dodge rate that scores 5 (score = 10 * rate / (2 * this), capped at 10). */
    static final double DODGE_NEUTRAL_RATE = 0.3;
    static final double DODGE_WEIGHT_PER_ATTEMPT = 0.5;
    static final double DODGE_MAX_WEIGHT = 10.0;

    /** Actions per active second scoring 0, 5 and 10. */
    static final double ACTIONS_SLOW = 0.5;
    static final double ACTIONS_NEUTRAL = 2.0;
    static final double ACTIONS_FAST = 4.0;
    static final double ACTIONS_MAX_WEIGHT = 10.0;

    /** New carriages reached per minute on the train scoring 0, 5 and 10. Provisional. */
    static final double PACE_SLOW = 0.0;
    static final double PACE_NEUTRAL = 2.0;
    static final double PACE_FAST = 6.0;
    static final double PACE_MAX_WEIGHT = 10.0;

    /** Share of the player's own arrows that hit a living target: scores 5 at 30%, 10 at 60%. */
    static final double ARROW_HIT_NEUTRAL_RATE = 0.3;
    /** Share of bow shots loosed fully drawn (critical): scores 0, 5 and 10. */
    static final double CRIT_SLOW_RATE = 0.1;
    static final double CRIT_NEUTRAL_RATE = 0.5;
    static final double CRIT_FAST_RATE = 0.9;
    static final double ARROW_WEIGHT_PER_SHOT = 0.5;
    static final double ARROW_MAX_WEIGHT = 10.0;

    /** Mean ticks between speed-bridge blocks scoring 10, 5 and 0 (runs are at most 8 apart). */
    static final double BRIDGE_GAP_FAST = 2.0;
    static final double BRIDGE_GAP_NEUTRAL = 5.0;
    static final double BRIDGE_GAP_SLOW = 8.0;
    /** Mean ticks between jump-placed blocks scoring 10, 5 and 0 — bounded below by the jump arc. */
    static final double JUMP_GAP_FAST = 9.0;
    static final double JUMP_GAP_NEUTRAL = 11.0;
    static final double JUMP_GAP_SLOW = 13.0;
    static final double GAP_WEIGHT_PER_BLOCK = 0.5;
    static final double GAP_MAX_WEIGHT = 10.0;

    /** Bonus per block in a speed-bridge run or a jump-placed pillar, and the shared cap. */
    static final double MOVE_BONUS_PER_BLOCK = 0.1;
    static final double MAX_MOVE_BONUS = 1.5;

    // ---- Tallies ----------------------------------------------------------
    public static final String TAG = "Reflexes";

    /** Every tally, with its NBT key. Missing keys load as 0, so new stats never break old saves. */
    enum Stat {
        /** All strike backs (melee and ranged) and the sum of their 0–10 scores. */
        STRIKE_SAMPLES("StrikeSamples"), STRIKE_SCORE("StrikeScore"),
        /** First hits that did not end clean, and clean ones (disjoint). */
        FIRST_HITS("FirstHits"), CLEAN_FIRST_HITS("CleanFirstHits"),
        /** Melee subsets of the four above. */
        MELEE_STRIKE_SAMPLES("MeleeStrikeSamples"), MELEE_STRIKE_SCORE("MeleeStrikeScore"),
        MELEE_FIRST_HITS("MeleeFirstHits"), MELEE_CLEAN_FIRST_HITS("MeleeCleanFirstHits"),
        /** Mob projectiles that passed close and missed, and ones that hit. */
        DODGES("Dodges"), PROJECTILE_HITS("ProjectileHits"),
        ACTIONS("Actions"), ACTIVE_SECONDS("ActiveSeconds"),
        PACE_CARRIAGES("PaceCarriages"), PACE_SECONDS("PaceSeconds"),
        BRIDGE_BLOCKS("BridgeBlocks"), JUMP_BLOCKS("JumpBlocks"),
        /** Gaps (ticks) between consecutive blocks inside counted runs, summed, and how many. */
        BRIDGE_GAP_SUM("BridgeGapSum"), BRIDGE_GAPS("BridgeGaps"),
        JUMP_GAP_SUM("JumpGapSum"), JUMP_GAPS("JumpGaps"),
        /** The player's own arrows that landed somewhere, and those that hit a living target. */
        ARROW_SHOTS("ArrowShots"), ARROW_HITS("ArrowHits"),
        /** Bow shots (crossbows always crit, so they don't count), and the fully drawn ones. */
        BOW_SHOTS("BowShots"), CRIT_SHOTS("CritShots");

        final String key;

        Stat(String key) {
            this.key = key;
        }
    }

    private static final int STATS = Stat.values().length;

    public static final ReflexTally EMPTY = new ReflexTally(new float[STATS]);

    private final float[] v;

    private ReflexTally(float[] values) {
        this.v = values;
    }

    float get(Stat stat) {
        return v[stat.ordinal()];
    }

    /** A copy with each {@code (stat, delta)} pair added; tallies floor at 0. */
    private ReflexTally plus(Object... statDeltas) {
        float[] next = Arrays.copyOf(v, STATS);
        for (int i = 0; i < statDeltas.length; i += 2) {
            int idx = ((Stat) statDeltas[i]).ordinal();
            next[idx] = Math.max(0F, next[idx] + ((Number) statDeltas[i + 1]).floatValue());
        }
        return new ReflexTally(next);
    }

    // ---- Accessors (int-valued tallies round-trip exactly through float) ----
    public int strikeSamples() { return (int) get(Stat.STRIKE_SAMPLES); }
    public float strikeScore() { return get(Stat.STRIKE_SCORE); }
    public int firstHits() { return (int) get(Stat.FIRST_HITS); }
    public int cleanFirstHits() { return (int) get(Stat.CLEAN_FIRST_HITS); }
    public int meleeStrikeSamples() { return (int) get(Stat.MELEE_STRIKE_SAMPLES); }
    public int meleeFirstHits() { return (int) get(Stat.MELEE_FIRST_HITS); }
    public int meleeCleanFirstHits() { return (int) get(Stat.MELEE_CLEAN_FIRST_HITS); }
    public int dodges() { return (int) get(Stat.DODGES); }
    public int projectileHits() { return (int) get(Stat.PROJECTILE_HITS); }
    public int actions() { return (int) get(Stat.ACTIONS); }
    public float activeSeconds() { return get(Stat.ACTIVE_SECONDS); }
    public float paceCarriages() { return get(Stat.PACE_CARRIAGES); }
    public float paceSeconds() { return get(Stat.PACE_SECONDS); }
    public int bridgeBlocks() { return (int) get(Stat.BRIDGE_BLOCKS); }
    public int jumpBlocks() { return (int) get(Stat.JUMP_BLOCKS); }
    public int bridgeGaps() { return (int) get(Stat.BRIDGE_GAPS); }
    public int jumpGaps() { return (int) get(Stat.JUMP_GAPS); }
    public int arrowShots() { return (int) get(Stat.ARROW_SHOTS); }
    public int arrowHits() { return (int) get(Stat.ARROW_HITS); }
    public int bowShots() { return (int) get(Stat.BOW_SHOTS); }
    public int critShots() { return (int) get(Stat.CRIT_SHOTS); }

    public boolean isEmpty() {
        for (float x : v) {
            if (x != 0F) return false;
        }
        return true;
    }

    // ---- Credits ----------------------------------------------------------

    /** One melee strike back, {@code latencyTicks} after the mob's hit landed. */
    public ReflexTally withStrikeBack(int latencyTicks) {
        return withStrikeBack(latencyTicks, true);
    }

    /** One strike back; {@code melee} when it was the player's own blow, not a projectile. */
    public ReflexTally withStrikeBack(int latencyTicks, boolean melee) {
        float score = (float) strikeBackScore(latencyTicks);
        return melee
            ? plus(Stat.STRIKE_SAMPLES, 1, Stat.STRIKE_SCORE, score,
                   Stat.MELEE_STRIKE_SAMPLES, 1, Stat.MELEE_STRIKE_SCORE, score)
            : plus(Stat.STRIKE_SAMPLES, 1, Stat.STRIKE_SCORE, score);
    }

    /** One resolved melee first hit — {@code clean} when the player went on to kill it untouched. */
    public ReflexTally withFirstHit(boolean clean) {
        return withFirstHit(clean, true);
    }

    /** One resolved first hit; {@code melee} when the opening blow was the player's own. */
    public ReflexTally withFirstHit(boolean clean, boolean melee) {
        Stat all = clean ? Stat.CLEAN_FIRST_HITS : Stat.FIRST_HITS;
        Stat meleeStat = clean ? Stat.MELEE_CLEAN_FIRST_HITS : Stat.MELEE_FIRST_HITS;
        return melee ? plus(all, 1, meleeStat, 1) : plus(all, 1);
    }

    /** One mob projectile aimed at the player: {@code dodged} if it passed close and missed. */
    public ReflexTally withProjectile(boolean dodged) {
        return plus(dodged ? Stat.DODGES : Stat.PROJECTILE_HITS, 1);
    }

    /** A flushed window of input: {@code count} actions over {@code seconds} active seconds. */
    public ReflexTally withActions(int count, float seconds) {
        return plus(Stat.ACTIONS, count, Stat.ACTIVE_SECONDS, seconds);
    }

    /** A flushed pace window: {@code carriages} new carriages reached in {@code seconds} on the train. */
    public ReflexTally withPace(float carriages, float seconds) {
        return plus(Stat.PACE_CARRIAGES, carriages, Stat.PACE_SECONDS, seconds);
    }

    /** Blocks laid in a speed-bridge run. */
    public ReflexTally withBridgeBlocks(int count) {
        return plus(Stat.BRIDGE_BLOCKS, count);
    }

    /** Blocks laid in a speed-bridge run, with the {@code gaps} between them summing to {@code gapTicks}. */
    public ReflexTally withBridgeBlocks(int count, int gaps, int gapTicks) {
        return plus(Stat.BRIDGE_BLOCKS, count, Stat.BRIDGE_GAPS, gaps, Stat.BRIDGE_GAP_SUM, gapTicks);
    }

    /** Blocks jump-placed under the player's own feet. */
    public ReflexTally withJumpBlocks(int count) {
        return plus(Stat.JUMP_BLOCKS, count);
    }

    /** Blocks jump-placed, with the {@code gaps} between them summing to {@code gapTicks}. */
    public ReflexTally withJumpBlocks(int count, int gaps, int gapTicks) {
        return plus(Stat.JUMP_BLOCKS, count, Stat.JUMP_GAPS, gaps, Stat.JUMP_GAP_SUM, gapTicks);
    }

    /**
     * One of the player's own arrows landed: {@code hit} if it struck a living target.
     * {@code crit} is whether it left the bow fully drawn, or {@code null} for a shot that can't
     * tell (a crossbow bolt is always critical; a trident never is).
     */
    public ReflexTally withArrow(boolean hit, Boolean crit) {
        ReflexTally t = plus(Stat.ARROW_SHOTS, 1, Stat.ARROW_HITS, hit ? 1 : 0);
        return crit == null ? t : t.plus(Stat.BOW_SHOTS, 1, Stat.CRIT_SHOTS, crit ? 1 : 0);
    }

    // ---- Scoring helpers --------------------------------------------------

    /** Score of one strike back: 10 at {@value #STRIKE_FAST_TICKS} ticks, 0 at {@value #STRIKE_SLOW_TICKS}. */
    static double strikeBackScore(int latencyTicks) {
        return 10.0 - ramp(latencyTicks, STRIKE_FAST_TICKS, STRIKE_NEUTRAL_TICKS, STRIKE_SLOW_TICKS);
    }

    /** Piecewise-linear 0→5→10 as {@code x} rises through {@code lo}, {@code mid}, {@code hi}; clamped. */
    static double ramp(double x, double lo, double mid, double hi) {
        if (x <= lo) return 0.0;
        if (x >= hi) return 10.0;
        if (x <= mid) return 5.0 * (x - lo) / (mid - lo);
        return 5.0 + 5.0 * (x - mid) / (hi - mid);
    }

    double dodgeScore() {
        int attempts = dodges() + projectileHits();
        if (attempts == 0) return DispositionTraits.DEFAULT;
        double rate = (double) dodges() / attempts;
        return Math.min(10.0, 10.0 * rate / (2.0 * DODGE_NEUTRAL_RATE));
    }

    double actionScore() {
        if (activeSeconds() <= 0F) return DispositionTraits.DEFAULT;
        return ramp(actions() / activeSeconds(), ACTIONS_SLOW, ACTIONS_NEUTRAL, ACTIONS_FAST);
    }

    double paceScore() {
        if (paceSeconds() <= 0F) return DispositionTraits.DEFAULT;
        return ramp(paceCarriages() / (paceSeconds() / 60.0), PACE_SLOW, PACE_NEUTRAL, PACE_FAST);
    }

    double arrowHitScore() {
        if (arrowShots() == 0) return DispositionTraits.DEFAULT;
        double rate = (double) arrowHits() / arrowShots();
        return Math.min(10.0, 10.0 * rate / (2.0 * ARROW_HIT_NEUTRAL_RATE));
    }

    double critScore() {
        if (bowShots() == 0) return DispositionTraits.DEFAULT;
        return ramp((double) critShots() / bowShots(), CRIT_SLOW_RATE, CRIT_NEUTRAL_RATE, CRIT_FAST_RATE);
    }

    double bridgeGapScore() {
        if (bridgeGaps() == 0) return DispositionTraits.DEFAULT;
        double mean = get(Stat.BRIDGE_GAP_SUM) / bridgeGaps();
        return 10.0 - ramp(mean, BRIDGE_GAP_FAST, BRIDGE_GAP_NEUTRAL, BRIDGE_GAP_SLOW);
    }

    double jumpGapScore() {
        if (jumpGaps() == 0) return DispositionTraits.DEFAULT;
        double mean = get(Stat.JUMP_GAP_SUM) / jumpGaps();
        return 10.0 - ramp(mean, JUMP_GAP_FAST, JUMP_GAP_NEUTRAL, JUMP_GAP_SLOW);
    }

    double moveBonus() {
        return Math.min((bridgeBlocks() + jumpBlocks()) * MOVE_BONUS_PER_BLOCK, MAX_MOVE_BONUS);
    }

    /** Running weighted sum {@code [numerator, denominator]} of evidence, without any prior. */
    private static final class Acc {
        double num;
        double den;

        void add(double weight, double score) {
            if (weight > 0) {
                num += weight * score;
                den += weight;
            }
        }

        /** Sum-of-scores evidence: {@code samples} at weight 1 each, scores summing to {@code scoreSum}. */
        void addSum(double samples, double scoreSum) {
            num += scoreSum;
            den += samples;
        }

        double meanWithPrior(double priorWeight, double priorValue) {
            return (priorWeight * priorValue + num) / (priorWeight + den);
        }
    }

    private void addMelee(Acc a, boolean meleeOnly) {
        if (meleeOnly) {
            a.addSum(meleeStrikeSamples(), get(Stat.MELEE_STRIKE_SCORE));
            a.add(meleeFirstHits() * FIRST_HIT_WEIGHT, FIRST_HIT_SCORE);
            a.add(meleeCleanFirstHits() * CLEAN_FIRST_HIT_WEIGHT, CLEAN_FIRST_HIT_SCORE);
        } else {
            a.addSum(strikeSamples(), strikeScore());
            a.add(firstHits() * FIRST_HIT_WEIGHT, FIRST_HIT_SCORE);
            a.add(cleanFirstHits() * CLEAN_FIRST_HIT_WEIGHT, CLEAN_FIRST_HIT_SCORE);
        }
    }

    private void addRanged(Acc a) {
        a.add(Math.min(arrowShots() * ARROW_WEIGHT_PER_SHOT, ARROW_MAX_WEIGHT), arrowHitScore());
        a.add(Math.min(bowShots() * ARROW_WEIGHT_PER_SHOT, ARROW_MAX_WEIGHT), critScore());
    }

    private void addDefence(Acc a) {
        a.add(Math.min((dodges() + projectileHits()) * DODGE_WEIGHT_PER_ATTEMPT, DODGE_MAX_WEIGHT),
            dodgeScore());
    }

    private void addBuilding(Acc a) {
        a.add(Math.min(bridgeGaps() * GAP_WEIGHT_PER_BLOCK, GAP_MAX_WEIGHT), bridgeGapScore());
        a.add(Math.min(jumpGaps() * GAP_WEIGHT_PER_BLOCK, GAP_MAX_WEIGHT), jumpGapScore());
    }

    private void addMovement(Acc a) {
        a.add(Math.min(paceSeconds() / 60.0, PACE_MAX_WEIGHT), paceScore());
    }

    /**
     * Unrounded overall score, exactly as v0.124.0 scored it: every strike back and first hit
     * (melee and ranged), dodges, pace and actions per second, plus the move bonus. Arrow accuracy
     * and block-gap timing feed only their own channels: a qualifying-but-unhurried bridge would
     * otherwise drag down the overall score a player who never bridges keeps.
     */
    double rawScore() {
        Acc a = new Acc();
        addMelee(a, false);
        addDefence(a);
        addMovement(a);
        a.add(Math.min(activeSeconds() / 60.0, ACTIONS_MAX_WEIGHT), actionScore());
        return a.meanWithPrior(PRIOR_WEIGHT, DispositionTraits.DEFAULT) + moveBonus();
    }

    /** The echo's overall reaction speed, rounded and clamped to {@code [0, 10]}. */
    public int reactionSpeed() {
        return clampTrait(rawScore());
    }

    /** Unrounded score for one channel, leaning on the overall score. */
    double rawScore(ReactionChannel channel) {
        int overall = reactionSpeed();
        if (channel == null || channel == ReactionChannel.OVERALL) {
            return overall;
        }
        Acc a = new Acc();
        switch (channel) {
            case MELEE -> addMelee(a, true);
            case RANGED -> addRanged(a);
            case DEFENCE -> addDefence(a);
            case BUILDING -> addBuilding(a);
            case MOVEMENT -> addMovement(a);
            case ITEMS -> { /* no player signal yet — follows the overall score */ }
            default -> { }
        }
        return a.meanWithPrior(CHANNEL_PRIOR_WEIGHT, overall);
    }

    /** The echo's reaction speed for one kind of action, rounded and clamped to {@code [0, 10]}. */
    public int reactionSpeed(ReactionChannel channel) {
        return clampTrait(rawScore(channel));
    }

    /** Every specific channel's score, in {@link ReactionChannel#SPECIFIC} order. */
    public Map<ReactionChannel, Integer> channelSpeeds() {
        Map<ReactionChannel, Integer> out = new EnumMap<>(ReactionChannel.class);
        for (ReactionChannel channel : ReactionChannel.SPECIFIC) {
            out.put(channel, reactionSpeed(channel));
        }
        return out;
    }

    private static int clampTrait(double value) {
        long rounded = Math.round(value);
        if (rounded < DispositionTraits.MIN) return DispositionTraits.MIN;
        if (rounded > DispositionTraits.MAX) return DispositionTraits.MAX;
        return (int) rounded;
    }

    /** Operator-facing breakdown for the debug readout. */
    public String breakdown() {
        StringBuilder channels = new StringBuilder();
        for (ReactionChannel channel : ReactionChannel.SPECIFIC) {
            channels.append(' ').append(channel.key()).append(' ').append(reactionSpeed(channel));
        }
        return String.format(Locale.ROOT,
            "reaction %d (raw %.2f) |%s | strike %d avg %.1f (melee %d) | first %d clean %d"
                + " | dodge %d/%d -> %.1f | arrows %d/%d -> %.1f crit %d/%d -> %.1f"
                + " | actions %d/%.0fs -> %.1f | pace %.0f/%.0fs -> %.1f"
                + " | bridge %d gaps %d -> %.1f | jump %d gaps %d -> %.1f (+%.1f)",
            reactionSpeed(), rawScore(), channels,
            strikeSamples(), strikeSamples() == 0 ? 0.0 : strikeScore() / strikeSamples(),
            meleeStrikeSamples(), firstHits(), cleanFirstHits(),
            dodges(), dodges() + projectileHits(), dodgeScore(),
            arrowHits(), arrowShots(), arrowHitScore(), critShots(), bowShots(), critScore(),
            actions(), activeSeconds(), actionScore(), paceCarriages(), paceSeconds(), paceScore(),
            bridgeBlocks(), bridgeGaps(), bridgeGapScore(), jumpBlocks(), jumpGaps(), jumpGapScore(),
            moveBonus());
    }

    // ---- NBT --------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (Stat stat : Stat.values()) {
            tag.putFloat(stat.key, get(stat));
        }
        return tag;
    }

    /**
     * Read back; every missing key is 0, so an absent tag loads as {@link #EMPTY} and a tally
     * written before a stat existed simply has none of it. Reads ints too: the first build wrote
     * the count stats as ints.
     */
    public static ReflexTally load(CompoundTag tag) {
        float[] values = new float[STATS];
        for (Stat stat : Stat.values()) {
            values[stat.ordinal()] = Math.max(0F, NbtCompat.getFloatOr(tag, stat.key, 0F));
        }
        return new ReflexTally(values);
    }
}
