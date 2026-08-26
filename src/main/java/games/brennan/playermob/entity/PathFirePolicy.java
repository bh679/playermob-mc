package games.brennan.playermob.entity;

import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The fire-avoidance reflex's decisions: <em>did the mob spot it</em>, and <em>how long does it
 * hesitate</em>. Pure logic — the live scan and the actual dousing live in
 * {@code games.brennan.playermob.entity.goal.DouseFireInPathGoal}.
 *
 * <p>Deliberately fallible. A PlayerMob that always stopped for fire would simply never burn on a
 * path fire, which reads as omniscience; {@link #NOTICE_CHANCE} leaves it walking into roughly one
 * fire in four, where the priority-0 {@code FireBucketGoal} takes over and it runs for water. The
 * hesitation is long ({@value #REACTION_MIN_TICKS}–{@value #REACTION_MAX_TICKS} ticks) for the same
 * reason: it should read as a double-take, not a reflex.</p>
 *
 * <p>Split pure-core / live-scan the way {@link DoorObstruction} is — the rolls and the "is it
 * actually walking" threshold are unit-tested here, the world scan is covered by the in-game
 * Gate 2 test.</p>
 */
public final class PathFirePolicy {

    private PathFirePolicy() {}

    /** Odds the mob spots a fire in its path at all. The rest of the time it walks straight in. */
    public static final float NOTICE_CHANCE = 0.75F;

    /**
     * The delayed reaction — how long it stands and looks at the fire before acting. The neutral
     * (reaction 5) window; {@code DouseFireInPathGoal} rolls it through
     * {@code PlayerMobEntity.reactRoll}, so a quick-reacting mob lands low in it more often.
     */
    public static final int REACTION_MIN_TICKS = 5;
    public static final int REACTION_MAX_TICKS = 40;

    /**
     * Wind-up for swapping a water bucket into the main hand, matching the flint-and-steel swap.
     * Reaction-skewed at the call site, like {@link #REACTION_MIN_TICKS}.
     */
    public static final int BUCKET_SWAP_MIN_TICKS = 4;
    public static final int BUCKET_SWAP_MAX_TICKS = 20;

    /**
     * How far ahead the mob looks, in blocks. Kept inside a player's ~4.5-block reach, so that when it
     * stops on spotting one the fire is already close enough to put out without stepping into it.
     */
    public static final double LOOKAHEAD_BLOCKS = 4.0;

    /** Squared form of {@link #LOOKAHEAD_BLOCKS}, for distance comparisons. */
    public static final double LOOKAHEAD_SQR = LOOKAHEAD_BLOCKS * LOOKAHEAD_BLOCKS;

    /** Below this per-tick horizontal displacement the mob counts as standing still. */
    private static final double MOVE_EPS_SQR = 0.025 * 0.025;

    /** Fire or soul fire — both burn, both count. */
    public static boolean isFire(BlockState state) {
        return state.is(BlockTags.FIRE);
    }

    /** The notice roll, made once per fire the mob comes across. */
    public static boolean notices(RandomSource random) {
        return random.nextFloat() < NOTICE_CHANCE;
    }

    /**
     * How many whole blocks ahead of itself the mob should probe, given its per-tick horizontal
     * displacement — {@code 0} when it isn't really going anywhere (idle jitter), else two steps,
     * which is what fits inside {@link #LOOKAHEAD_BLOCKS}.
     *
     * <p>Pure so the "is it actually walking" threshold is testable without a level, and rejecting
     * jitter the way {@link DoorObstruction} does for its heading latch: standing still must not read
     * as heading somewhere, or a stationary mob would keep probing whatever block it happens to face.</p>
     */
    public static int stepsAhead(double dx, double dz) {
        return (dx * dx + dz * dz) < MOVE_EPS_SQR ? 0 : 2;
    }
}
