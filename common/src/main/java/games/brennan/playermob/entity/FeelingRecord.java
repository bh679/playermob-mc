package games.brennan.playermob.entity;

/**
 * The per-individual state a {@link PlayerMobEntity} accumulates toward one
 * player or other PlayerMob — the value type stored in {@link FeelingLedger}.
 * Phase A stored a bare {@code float} feeling; Phase B promotes it to this small
 * record so the positive feeling-events can enforce their per-individual caps.
 *
 * <p><b>Immutable.</b> Every transition returns a <em>new</em> record (per the
 * project's immutability rule); the ledger is a mutable container of immutable
 * records. World-free and primitive-only, so it unit-tests like
 * {@link DispositionResolver} / {@code EquipmentEvaluator} without a live world.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code feeling} — 0–10, {@link #DEFAULT 5} = neutral (hate→love).</li>
 *   <li>{@code crouchBudgetUsed} / {@code crouchCap} — lifetime crouch-bonus spent and its
 *       ceiling. The cap starts at {@link #CROUCH_CAP_BASE 2} (+2 feeling max from crouching)
 *       and an attack that costs ≥ {@link #CROUCH_LOSS_THRESHOLD 1} feeling re-opens
 *       {@link #CROUCH_REEARN +1} of headroom (apology), bounded by {@link #CROUCH_CAP_MAX}.</li>
 *   <li>{@code defendCount} — times this individual has been credited for defending the mob
 *       (capped at {@link #DEFEND_CAP 2}).</li>
 *   <li>{@code lastCarriageIndex} — the Dungeon-Train carriage this pairing last co-rode
 *       ({@link #NO_CARRIAGE} until first seen on a train; the index is <em>signed</em>, so a
 *       plain {@code 0} would collide with the real central carriage).</li>
 *   <li>{@code lastWitnessTick} — game tick of the last witnessed combat event (defend / harm)
 *       credited for this individual; debounces repeat-crediting of one event. <b>Session-scoped:
 *       not persisted</b> — vanilla combat timestamps reset on reload, so a stale saved value would
 *       wrongly block every future event ({@link FeelingLedger} loads it as 0).</li>
 * </ul>
 */
public record FeelingRecord(float feeling, float crouchBudgetUsed, float crouchCap,
                            int defendCount, int lastCarriageIndex, int lastWitnessTick) {

    public static final float DEFAULT = 5.0F;
    public static final float MIN = 0.0F;
    public static final float MAX = 10.0F;

    /** Feeling gained per debounced crouch gesture. */
    public static final float CROUCH_STEP = 0.1F;
    /** Lifetime crouch bonus a fresh individual may earn (+2 feeling). */
    public static final float CROUCH_CAP_BASE = 2.0F;
    /** Extra crouch headroom re-opened by a qualifying attack-loss. */
    public static final float CROUCH_REEARN = 1.0F;
    /** An attack that costs at least this much feeling re-opens crouch headroom. */
    public static final float CROUCH_LOSS_THRESHOLD = 1.0F;
    /** Hard ceiling on {@code crouchCap} so repeated attacks can't grow it without bound. */
    public static final float CROUCH_CAP_MAX = 10.0F;

    /** Feeling gained per credited defence. */
    public static final float DEFEND_STEP = 1.0F;
    /** Lifetime defences that earn feeling, per individual. */
    public static final int DEFEND_CAP = 2;

    /** Feeling gained per Dungeon-Train carriage travelled together. */
    public static final float TRAVEL_STEP = 0.2F;

    /** Smallest / largest feeling a gift can add. */
    public static final float GIFT_MIN = 0.5F;
    public static final float GIFT_MAX = 3.0F;
    /** Feeling added per point the gift out-scores the mob's current gear. */
    public static final double GIFT_PER_SCORE = 0.25;

    /** Sentinel "not yet seen on a train" carriage index (real indices are signed, incl. 0). */
    public static final int NO_CARRIAGE = Integer.MIN_VALUE;

    /** A fresh, neutral individual — the default returned for anyone not in the ledger. */
    public static final FeelingRecord NEUTRAL =
        new FeelingRecord(DEFAULT, 0.0F, CROUCH_CAP_BASE, 0, NO_CARRIAGE, 0);

    /** Copy with an absolute feeling (clamped); all other state preserved. */
    public FeelingRecord withFeeling(float value) {
        return new FeelingRecord(clamp(value), crouchBudgetUsed, crouchCap,
            defendCount, lastCarriageIndex, lastWitnessTick);
    }

    /** Copy with the witnessed-event tick updated (debounce bookkeeping). */
    public FeelingRecord withWitnessTick(int tick) {
        return new FeelingRecord(feeling, crouchBudgetUsed, crouchCap,
            defendCount, lastCarriageIndex, tick);
    }

    /**
     * Generic feeling shift (gift +, harm −), clamped. Deliberately has <b>no</b>
     * crouch-cap side effect — only a direct attack ({@link #afterAttack}) re-opens
     * crouch headroom, so harming a loved one can't reward the aggressor.
     */
    public FeelingRecord adjusted(float delta) {
        return withFeeling(feeling + delta);
    }

    /**
     * Apply an attack-driven feeling change ({@code delta} is negative). A loss of
     * at least {@link #CROUCH_LOSS_THRESHOLD} re-opens {@link #CROUCH_REEARN} of
     * crouch headroom (bounded by {@link #CROUCH_CAP_MAX}) so the attacker can make
     * amends by crouching.
     */
    public FeelingRecord afterAttack(float delta) {
        float newCap = crouchCap;
        if (delta <= -CROUCH_LOSS_THRESHOLD) {
            newCap = Math.min(crouchCap + CROUCH_REEARN, CROUCH_CAP_MAX);
        }
        return new FeelingRecord(clamp(feeling + delta), crouchBudgetUsed, newCap,
            defendCount, lastCarriageIndex, lastWitnessTick);
    }

    /** One debounced crouch at neutral strength — see {@link #afterCrouch(float)}. */
    public FeelingRecord afterCrouch() {
        return afterCrouch(1.0F);
    }

    /**
     * One debounced crouch scaled by {@code scale} (the mob's friendliness-driven
     * {@link DispositionResolver#kindnessScale}): +{@link #CROUCH_STEP}·scale feeling, the
     * scaled step also charged against the lifetime {@code crouchCap}. A friendlier mob reacts
     * more per bow and reaches the cap sooner; the +2 ceiling itself is unchanged.
     */
    public FeelingRecord afterCrouch(float scale) {
        if (crouchBudgetUsed >= crouchCap) {
            return this;
        }
        float step = CROUCH_STEP * scale;
        return new FeelingRecord(clamp(feeling + step), crouchBudgetUsed + step,
            crouchCap, defendCount, lastCarriageIndex, lastWitnessTick);
    }

    /** One credited defence at neutral strength — see {@link #afterDefend(float)}. */
    public FeelingRecord afterDefend() {
        return afterDefend(1.0F);
    }

    /**
     * One credited defence scaled by {@code scale} (the mob's friendliness-driven
     * {@link DispositionResolver#kindnessScale}): +{@link #DEFEND_STEP}·scale feeling, until
     * {@link #DEFEND_CAP} defences are counted. The cap is on the defence <em>count</em>, so a
     * friendlier mob banks more total feeling across its (still ≤ {@link #DEFEND_CAP}) rescues.
     */
    public FeelingRecord afterDefend(float scale) {
        if (defendCount >= DEFEND_CAP) {
            return this;
        }
        return new FeelingRecord(clamp(feeling + DEFEND_STEP * scale), crouchBudgetUsed, crouchCap,
            defendCount + 1, lastCarriageIndex, lastWitnessTick);
    }

    /**
     * Advance the shared-carriage tracker. The first time this pairing is seen on a
     * train ({@code lastCarriageIndex == NO_CARRIAGE}) it only records the position,
     * with no feeling credit; thereafter a change of carriage adds
     * {@link #TRAVEL_STEP}. A poll at the same carriage is a no-op.
     */
    public FeelingRecord afterCarriageAdvance(int newIndex) {
        if (lastCarriageIndex == NO_CARRIAGE) {
            return new FeelingRecord(feeling, crouchBudgetUsed, crouchCap,
                defendCount, newIndex, lastWitnessTick);
        }
        if (newIndex == lastCarriageIndex) {
            return this;
        }
        return new FeelingRecord(clamp(feeling + TRAVEL_STEP), crouchBudgetUsed, crouchCap,
            defendCount, newIndex, lastWitnessTick);
    }

    /**
     * Feeling a gift adds, weighted by how much it out-scores the mob's current
     * gear: the {@link #GIFT_MIN} floor for an equal-or-worse item (and all
     * non-gear, which {@code EquipmentEvaluator.score} rates 0), scaling up to the
     * {@link #GIFT_MAX} ceiling for a big upgrade.
     */
    public static float giftDelta(double giftScore, double currentScore) {
        double improvement = Math.max(0.0, giftScore - currentScore);
        // improvement >= 0 and GIFT_PER_SCORE > 0, so delta is already >= GIFT_MIN.
        double delta = GIFT_MIN + improvement * GIFT_PER_SCORE;
        return (float) Math.min(delta, GIFT_MAX);
    }

    /**
     * How "interesting" this relationship is — the save-cap keeps the highest
     * magnitudes. Includes the non-feeling investment (crouch budget, defends,
     * re-earned cap) so an entry that is feeling-neutral but carries real history
     * is not pruned ahead of a bare one.
     */
    public double magnitude() {
        return Math.abs(feeling - DEFAULT)
            + crouchBudgetUsed
            + 2.0 * defendCount
            + Math.max(0.0, crouchCap - CROUCH_CAP_BASE);
    }

    /** True when every field is at its default — written to NBT as just {@code {UUID, Feeling}}. */
    public boolean isDefault() {
        return feeling == DEFAULT
            && crouchBudgetUsed == 0.0F
            && crouchCap == CROUCH_CAP_BASE
            && defendCount == 0
            && lastCarriageIndex == NO_CARRIAGE
            && lastWitnessTick == 0;
    }

    static float clamp(float value) {
        // Snap to 1/1000 before clamping. Feelings accumulate in 0.1f steps, and float
        // drift lands an intended 7.0 at 6.999998 — a hair under the love threshold, so
        // a mob built up to "7" never counts as loved (defend/gift gates silently fail).
        // This is the single chokepoint for every feeling read/write/load, so rounding
        // here keeps the threshold comparisons (>= FEELING_LOVE 7, gift tiers 8/9,
        // hate <= 3) honest without distorting the 0.1-grid the events move on.
        float snapped = Math.round(value * 1000.0F) / 1000.0F;
        if (snapped < MIN) return MIN;
        if (snapped > MAX) return MAX;
        return snapped;
    }
}
