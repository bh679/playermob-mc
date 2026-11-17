package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * One life's evidence of the player's <em>reflexes</em> — the input to the reincarnated echo's
 * {@code ReactionSpeed} trait. Sibling of the conduct tallies in {@link PlayerLifeRecord}, held
 * there as one field so the record's constructor stays readable.
 *
 * <p>Immutable value type: every {@code with…} returns a fresh tally. Pure (no world) so the
 * scoring is unit-testable — see {@code ReflexTallyTest}. The live, tick-timed bookkeeping that
 * <em>produces</em> these samples lives in {@link ReflexTracker}; only finished samples land here.</p>
 *
 * <p><b>Scored as a rate, not a volume.</b> Fight/Flight and Friendliness add up from 5, which
 * suits conduct. Reflexes are a quality, so {@link #reactionSpeed()} is a weighted <em>average</em>
 * of per-signal scores, pulled toward neutral by a prior of {@link #PRIOR_WEIGHT} points at 5. A
 * long life therefore doesn't drift to 10, and a thin one stays near 5. A life with no evidence at
 * all scores exactly 5 — what every echo carried before this existed. Tallies are never capped,
 * so every weight here stays re-tunable.</p>
 */
public final class ReflexTally {

    // ---- Scoring ----------------------------------------------------------
    /** Weight of the neutral-5 prior every life starts with. */
    static final double PRIOR_WEIGHT = 5.0;

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

    /** Bonus per block in a speed-bridge run or a jump-placed pillar, and the shared cap. */
    static final double MOVE_BONUS_PER_BLOCK = 0.1;
    static final double MAX_MOVE_BONUS = 1.5;

    // ---- NBT keys ---------------------------------------------------------
    public static final String TAG = "Reflexes";
    static final String TAG_STRIKE_SAMPLES = "StrikeSamples";
    static final String TAG_STRIKE_SCORE = "StrikeScore";
    static final String TAG_FIRST_HITS = "FirstHits";
    static final String TAG_CLEAN_FIRST_HITS = "CleanFirstHits";
    static final String TAG_DODGES = "Dodges";
    static final String TAG_PROJECTILE_HITS = "ProjectileHits";
    static final String TAG_ACTIONS = "Actions";
    static final String TAG_ACTIVE_SECONDS = "ActiveSeconds";
    static final String TAG_PACE_CARRIAGES = "PaceCarriages";
    static final String TAG_PACE_SECONDS = "PaceSeconds";
    static final String TAG_BRIDGE_BLOCKS = "BridgeBlocks";
    static final String TAG_JUMP_BLOCKS = "JumpBlocks";

    public static final ReflexTally EMPTY =
        new ReflexTally(0, 0F, 0, 0, 0, 0, 0, 0F, 0F, 0F, 0, 0);

    private final int strikeSamples;
    /** Sum of each strike-back sample's 0–10 score. */
    private final float strikeScore;
    /** First hits that did not end clean (disjoint from {@link #cleanFirstHits}). */
    private final int firstHits;
    private final int cleanFirstHits;
    private final int dodges;
    private final int projectileHits;
    private final int actions;
    private final float activeSeconds;
    private final float paceCarriages;
    private final float paceSeconds;
    private final int bridgeBlocks;
    private final int jumpBlocks;

    ReflexTally(int strikeSamples, float strikeScore, int firstHits, int cleanFirstHits,
                int dodges, int projectileHits, int actions, float activeSeconds,
                float paceCarriages, float paceSeconds, int bridgeBlocks, int jumpBlocks) {
        this.strikeSamples = Math.max(0, strikeSamples);
        this.strikeScore = Math.max(0F, strikeScore);
        this.firstHits = Math.max(0, firstHits);
        this.cleanFirstHits = Math.max(0, cleanFirstHits);
        this.dodges = Math.max(0, dodges);
        this.projectileHits = Math.max(0, projectileHits);
        this.actions = Math.max(0, actions);
        this.activeSeconds = Math.max(0F, activeSeconds);
        this.paceCarriages = Math.max(0F, paceCarriages);
        this.paceSeconds = Math.max(0F, paceSeconds);
        this.bridgeBlocks = Math.max(0, bridgeBlocks);
        this.jumpBlocks = Math.max(0, jumpBlocks);
    }

    public int strikeSamples() { return strikeSamples; }
    public float strikeScore() { return strikeScore; }
    public int firstHits() { return firstHits; }
    public int cleanFirstHits() { return cleanFirstHits; }
    public int dodges() { return dodges; }
    public int projectileHits() { return projectileHits; }
    public int actions() { return actions; }
    public float activeSeconds() { return activeSeconds; }
    public float paceCarriages() { return paceCarriages; }
    public float paceSeconds() { return paceSeconds; }
    public int bridgeBlocks() { return bridgeBlocks; }
    public int jumpBlocks() { return jumpBlocks; }

    public boolean isEmpty() {
        return strikeSamples == 0 && firstHits == 0 && cleanFirstHits == 0 && dodges == 0
            && projectileHits == 0 && actions == 0 && activeSeconds == 0F && paceCarriages == 0F
            && paceSeconds == 0F && bridgeBlocks == 0 && jumpBlocks == 0;
    }

    // ---- Credits ----------------------------------------------------------

    /** One strike back, {@code latencyTicks} after the mob's hit landed. */
    public ReflexTally withStrikeBack(int latencyTicks) {
        return new ReflexTally(strikeSamples + 1, strikeScore + (float) strikeBackScore(latencyTicks),
            firstHits, cleanFirstHits, dodges, projectileHits, actions, activeSeconds,
            paceCarriages, paceSeconds, bridgeBlocks, jumpBlocks);
    }

    /** One resolved first hit — {@code clean} when the player went on to kill it untouched. */
    public ReflexTally withFirstHit(boolean clean) {
        return new ReflexTally(strikeSamples, strikeScore,
            clean ? firstHits : firstHits + 1, clean ? cleanFirstHits + 1 : cleanFirstHits,
            dodges, projectileHits, actions, activeSeconds, paceCarriages, paceSeconds,
            bridgeBlocks, jumpBlocks);
    }

    /** One mob projectile aimed at the player: {@code dodged} if it passed close and missed. */
    public ReflexTally withProjectile(boolean dodged) {
        return new ReflexTally(strikeSamples, strikeScore, firstHits, cleanFirstHits,
            dodged ? dodges + 1 : dodges, dodged ? projectileHits : projectileHits + 1,
            actions, activeSeconds, paceCarriages, paceSeconds, bridgeBlocks, jumpBlocks);
    }

    /** A flushed window of input: {@code count} actions over {@code seconds} active seconds. */
    public ReflexTally withActions(int count, float seconds) {
        return new ReflexTally(strikeSamples, strikeScore, firstHits, cleanFirstHits, dodges,
            projectileHits, actions + Math.max(0, count), activeSeconds + Math.max(0F, seconds),
            paceCarriages, paceSeconds, bridgeBlocks, jumpBlocks);
    }

    /** A flushed pace window: {@code carriages} new carriages reached in {@code seconds} on the train. */
    public ReflexTally withPace(float carriages, float seconds) {
        return new ReflexTally(strikeSamples, strikeScore, firstHits, cleanFirstHits, dodges,
            projectileHits, actions, activeSeconds, paceCarriages + Math.max(0F, carriages),
            paceSeconds + Math.max(0F, seconds), bridgeBlocks, jumpBlocks);
    }

    /** Blocks laid in a speed-bridge run. */
    public ReflexTally withBridgeBlocks(int count) {
        return new ReflexTally(strikeSamples, strikeScore, firstHits, cleanFirstHits, dodges,
            projectileHits, actions, activeSeconds, paceCarriages, paceSeconds,
            bridgeBlocks + Math.max(0, count), jumpBlocks);
    }

    /** Blocks jump-placed under the player's own feet. */
    public ReflexTally withJumpBlocks(int count) {
        return new ReflexTally(strikeSamples, strikeScore, firstHits, cleanFirstHits, dodges,
            projectileHits, actions, activeSeconds, paceCarriages, paceSeconds, bridgeBlocks,
            jumpBlocks + Math.max(0, count));
    }

    // ---- Scoring ----------------------------------------------------------

    /** Score of one strike back: 10 at {@value #STRIKE_FAST_TICKS} ticks, 0 at {@value #STRIKE_SLOW_TICKS}. */
    static double strikeBackScore(int latencyTicks) {
        return 10.0 - ramp(latencyTicks, STRIKE_FAST_TICKS, STRIKE_NEUTRAL_TICKS, STRIKE_SLOW_TICKS);
    }

    /**
     * Piecewise-linear 0→5→10 as {@code x} rises through {@code lo}, {@code mid}, {@code hi};
     * clamped outside.
     */
    static double ramp(double x, double lo, double mid, double hi) {
        if (x <= lo) return 0.0;
        if (x >= hi) return 10.0;
        if (x <= mid) return 5.0 * (x - lo) / (mid - lo);
        return 5.0 + 5.0 * (x - mid) / (hi - mid);
    }

    /** Running weighted sum: {@code [numerator, denominator]}. */
    private double[] weighted() {
        double num = PRIOR_WEIGHT * DispositionTraits.DEFAULT;
        double den = PRIOR_WEIGHT;

        num += strikeScore;
        den += strikeSamples;

        num += firstHits * FIRST_HIT_WEIGHT * FIRST_HIT_SCORE;
        den += firstHits * FIRST_HIT_WEIGHT;
        num += cleanFirstHits * CLEAN_FIRST_HIT_WEIGHT * CLEAN_FIRST_HIT_SCORE;
        den += cleanFirstHits * CLEAN_FIRST_HIT_WEIGHT;

        int attempts = dodges + projectileHits;
        if (attempts > 0) {
            double w = Math.min(attempts * DODGE_WEIGHT_PER_ATTEMPT, DODGE_MAX_WEIGHT);
            num += w * dodgeScore();
            den += w;
        }
        if (activeSeconds > 0F) {
            double w = Math.min(activeSeconds / 60.0, ACTIONS_MAX_WEIGHT);
            num += w * actionScore();
            den += w;
        }
        if (paceSeconds > 0F) {
            double w = Math.min(paceSeconds / 60.0, PACE_MAX_WEIGHT);
            num += w * paceScore();
            den += w;
        }
        return new double[] {num, den};
    }

    double dodgeScore() {
        int attempts = dodges + projectileHits;
        if (attempts == 0) return DispositionTraits.DEFAULT;
        double rate = (double) dodges / attempts;
        return Math.min(10.0, 10.0 * rate / (2.0 * DODGE_NEUTRAL_RATE));
    }

    double actionScore() {
        if (activeSeconds <= 0F) return DispositionTraits.DEFAULT;
        return ramp(actions / activeSeconds, ACTIONS_SLOW, ACTIONS_NEUTRAL, ACTIONS_FAST);
    }

    double paceScore() {
        if (paceSeconds <= 0F) return DispositionTraits.DEFAULT;
        return ramp(paceCarriages / (paceSeconds / 60.0), PACE_SLOW, PACE_NEUTRAL, PACE_FAST);
    }

    double moveBonus() {
        return Math.min((bridgeBlocks + jumpBlocks) * MOVE_BONUS_PER_BLOCK, MAX_MOVE_BONUS);
    }

    /** Unrounded score before clamping — exposed for the debug breakdown and tests. */
    double rawScore() {
        double[] w = weighted();
        return w[0] / w[1] + moveBonus();
    }

    /** The echo's reaction speed, rounded and clamped to {@code [0, 10]}. */
    public int reactionSpeed() {
        long rounded = Math.round(rawScore());
        if (rounded < DispositionTraits.MIN) return DispositionTraits.MIN;
        if (rounded > DispositionTraits.MAX) return DispositionTraits.MAX;
        return (int) rounded;
    }

    /** One-line, operator-facing breakdown for the debug readout. */
    public String breakdown() {
        return String.format(Locale.ROOT,
            "reaction %d (raw %.2f) | strike %d avg %.1f | first %d clean %d | dodge %d/%d -> %.1f"
                + " | actions %d/%.0fs -> %.1f | pace %.0f/%.0fs -> %.1f | bridge %d jump %d (+%.1f)",
            reactionSpeed(), rawScore(), strikeSamples,
            strikeSamples == 0 ? 0.0 : strikeScore / strikeSamples, firstHits, cleanFirstHits,
            dodges, dodges + projectileHits, dodgeScore(), actions, activeSeconds, actionScore(),
            paceCarriages, paceSeconds, paceScore(), bridgeBlocks, jumpBlocks, moveBonus());
    }

    // ---- NBT --------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_STRIKE_SAMPLES, strikeSamples);
        tag.putFloat(TAG_STRIKE_SCORE, strikeScore);
        tag.putInt(TAG_FIRST_HITS, firstHits);
        tag.putInt(TAG_CLEAN_FIRST_HITS, cleanFirstHits);
        tag.putInt(TAG_DODGES, dodges);
        tag.putInt(TAG_PROJECTILE_HITS, projectileHits);
        tag.putInt(TAG_ACTIONS, actions);
        tag.putFloat(TAG_ACTIVE_SECONDS, activeSeconds);
        tag.putFloat(TAG_PACE_CARRIAGES, paceCarriages);
        tag.putFloat(TAG_PACE_SECONDS, paceSeconds);
        tag.putInt(TAG_BRIDGE_BLOCKS, bridgeBlocks);
        tag.putInt(TAG_JUMP_BLOCKS, jumpBlocks);
        return tag;
    }

    /** Read back; every missing key is 0, so an absent tag loads as {@link #EMPTY}. */
    public static ReflexTally load(CompoundTag tag) {
        return new ReflexTally(
            NbtCompat.getIntOr(tag, TAG_STRIKE_SAMPLES, 0),
            NbtCompat.getFloatOr(tag, TAG_STRIKE_SCORE, 0F),
            NbtCompat.getIntOr(tag, TAG_FIRST_HITS, 0),
            NbtCompat.getIntOr(tag, TAG_CLEAN_FIRST_HITS, 0),
            NbtCompat.getIntOr(tag, TAG_DODGES, 0),
            NbtCompat.getIntOr(tag, TAG_PROJECTILE_HITS, 0),
            NbtCompat.getIntOr(tag, TAG_ACTIONS, 0),
            NbtCompat.getFloatOr(tag, TAG_ACTIVE_SECONDS, 0F),
            NbtCompat.getFloatOr(tag, TAG_PACE_CARRIAGES, 0F),
            NbtCompat.getFloatOr(tag, TAG_PACE_SECONDS, 0F),
            NbtCompat.getIntOr(tag, TAG_BRIDGE_BLOCKS, 0),
            NbtCompat.getIntOr(tag, TAG_JUMP_BLOCKS, 0));
    }
}
