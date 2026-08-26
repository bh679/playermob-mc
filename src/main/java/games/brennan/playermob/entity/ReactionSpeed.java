package games.brennan.playermob.entity;

import net.minecraft.util.RandomSource;

/**
 * The tick-timing half of the {@code reactionSpeed} trait: pure maths turning a
 * {@link DispositionTraits#reactionSpeed() reaction speed} in {@code [0, 10]} into the
 * delay a {@link PlayerMobEntity} actually waits. Sibling of {@link DispositionResolver}
 * — primitives only, so it unit-tests without a live world.
 *
 * <p>Reaction speed never changes <em>what</em> a mob decides (that stays with
 * {@link DispositionResolver}); it changes only how quickly the mob acts on the decision.
 * A high-reaction mob notices sooner, commits to a shot sooner, and lays blocks faster; a
 * low-reaction one is visibly sluggish and eats arrows it "saw" coming.</p>
 *
 * <p>Two shapes, because the goal code has two kinds of delay:</p>
 * <ul>
 *   <li>{@link #ticks(int, int)} — a <b>fixed</b> delay constant, multiplied by
 *       {@link #scale(int)}: 2× at reaction 0, 1× at 5, 0.5× at 10.</li>
 *   <li>{@link #roll(int, int, int, RandomSource)} — a <b>random range</b> (the 5–20 tick
 *       family). A flat multiplier would collapse the window the goals were tuned around,
 *       so instead the endpoints are kept and the <em>distribution</em> is bent toward the
 *       low end for a fast mob and the high end for a slow one.</li>
 * </ul>
 *
 * <p>Both curves are geometric about the neutral {@link #PIVOT 5}, so reaction 0 and
 * reaction 10 are exactly equal-and-opposite, and reaction 5 reproduces the mod's
 * pre-trait behaviour precisely — {@code roll} at 5 is distributionally identical to the
 * {@code min + random.nextInt(span + 1)} it replaces.</p>
 */
public final class ReactionSpeed {

    /**
     * The neutral reaction speed, where every curve here is the identity. Matches
     * {@link DispositionTraits#DEFAULT} so an unrolled or legacy mob behaves exactly as it
     * did before the trait existed.
     */
    static final int PIVOT = DispositionTraits.DEFAULT;

    /**
     * The multiplier at the extremes: a reaction-0 mob waits {@code EXTREME}× the base
     * delay, a reaction-10 mob {@code 1/EXTREME}×. Raising this widens the spread between
     * the fastest and slowest mobs — it is the single tuning knob for the whole trait.
     */
    static final double EXTREME = 2.0;

    private ReactionSpeed() {
    }

    /**
     * Multiplier applied to a fixed delay: {@code EXTREME^((PIVOT - reactionSpeed) / PIVOT)}
     * — 2.0 at 0, 1.0 at 5, 0.5 at 10. Clamped to {@code [0, 10]}.
     */
    public static double scale(int reactionSpeed) {
        return Math.pow(EXTREME, (double) (PIVOT - DispositionTraits.clamp(reactionSpeed)) / PIVOT);
    }

    /**
     * Scale a fixed delay constant by {@link #scale(int)}. A positive base never scales
     * below one tick — a zero-length cooldown would busy-loop the goal that owns it — and a
     * non-positive base (an "act immediately" sentinel) passes through untouched.
     */
    public static int ticks(int reactionSpeed, int baseTicks) {
        if (baseTicks <= 0) {
            return baseTicks;
        }
        long scaled = Math.round(baseTicks * scale(reactionSpeed));
        return (int) Math.max(1L, scaled);
    }

    /**
     * Distribution exponent for {@link #roll}: {@code EXTREME^((reactionSpeed - PIVOT) / PIVOT)}
     * — 0.5 at reaction 0 (skews the roll toward the slow end of the window), 1.0 at 5
     * (uniform), 2.0 at 10 (skews toward the fast end). The reciprocal of {@link #scale},
     * so the two curves stay symmetric.
     */
    static double skew(int reactionSpeed) {
        return Math.pow(EXTREME, (double) (DispositionTraits.clamp(reactionSpeed) - PIVOT) / PIVOT);
    }

    /**
     * Roll a delay in the inclusive window {@code [min, max]}, biased by reaction speed: a
     * fast mob lands low in the window more often, a slow one high. The endpoints are
     * unchanged — a reaction-10 mob can still roll the maximum, just rarely — which keeps
     * the "sometimes fast, sometimes slow" texture the goals were tuned for.
     *
     * <p>Drawn as {@code min + floor(u^k * (span + 1))} with {@code u} uniform in
     * {@code [0, 1)} and {@code k = }{@link #skew}. At the neutral pivot {@code k == 1} and
     * this is exactly the uniform {@code min + random.nextInt(span + 1)} it replaces.</p>
     *
     * @param min inclusive low end of the window
     * @param max inclusive high end; a {@code max <= min} degenerate window returns {@code min}
     */
    public static int roll(int reactionSpeed, int min, int max, RandomSource random) {
        if (max <= min) {
            return min;
        }
        int span = max - min;
        double biased = Math.pow(random.nextDouble(), skew(reactionSpeed));
        int offset = (int) (biased * (span + 1));
        // nextDouble() is < 1 so this only guards against floating-point reaching the top bucket.
        return min + Math.min(offset, span);
    }
}
