package games.brennan.playermob.entity;

import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;

/**
 * Decides <em>when</em> a PlayerMob reaches for its flint and steel. Pure logic — no {@code Level},
 * no world writes; the actual light-the-ground ritual lives in
 * {@code games.brennan.playermob.entity.goal.FlintAndSteelIgniteGoal}.
 *
 * <p>Two separate drives share the tool:</p>
 * <ul>
 *   <li><b>Cook the kill.</b> A food animal about to die from the mob's next swing
 *       ({@link #finisherHealthReached}) gets set alight first, so vanilla's smelting loot
 *       condition fires and the drop comes out cooked. The mob then puts that fire back out.</li>
 *   <li><b>Occasional combat ignite.</b> Anything else it's fighting that isn't already burning
 *       may get the ground under it torched, at most every {@link #COMBAT_GAP_MIN_TICKS}–
 *       {@link #COMBAT_GAP_MAX_TICKS} ticks. That fire is left to burn as an area hazard.</li>
 * </ul>
 *
 * <p>Both drives draw on one shared rate budget — {@link #RATE_MAX_IGNITES} lights per
 * {@link #RATE_WINDOW_TICKS} ticks — so no combination of triggers can turn a PlayerMob into a
 * flamethrower. The window is a plain {@code long[]} of tick stamps threaded through the goal;
 * {@link #recordIgnite} returns a <em>new</em> array rather than mutating the one handed in.</p>
 */
public final class FlintAndSteelPolicy {

    private FlintAndSteelPolicy() {}

    /** Rolling rate cap: at most this many lights per {@link #RATE_WINDOW_TICKS}. */
    public static final int RATE_MAX_IGNITES = 5;
    /** Width of the rate window, in ticks (10 seconds). */
    public static final int RATE_WINDOW_TICKS = 200;

    /**
     * Wind-up for drawing the flint and steel, and for putting it away again — the neutral
     * (reaction 5) window. {@code FlintAndSteelIgniteGoal} rolls it through
     * {@code PlayerMobEntity.reactRoll}, so a quick-reacting mob lands low in it more often.
     */
    public static final int SWAP_MIN_TICKS = 4;
    public static final int SWAP_MAX_TICKS = 20;

    /** How long a cooking fire is left standing before the mob stamps it out. */
    public static final int BURN_MIN_TICKS = 3;
    public static final int BURN_MAX_TICKS = 40;

    /** Gap between <em>combat</em> ignites — the "occasionally" in "uses it occasionally". */
    public static final int COMBAT_GAP_MIN_TICKS = 40;
    public static final int COMBAT_GAP_MAX_TICKS = 120;

    /** A tick stamp older than any real one — the empty-slot marker in a rate window. */
    private static final long NEVER = Long.MIN_VALUE;

    /** Flint and steel is the only igniter this behaviour uses (a fire charge is a throwable, not a ground-lighter). */
    public static boolean isFlintAndSteel(ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL);
    }

    /** First backpack slot holding flint and steel, or {@code -1}. */
    public static int firstSlot(Container pack) {
        for (int i = 0; i < pack.getContainerSize(); i++) {
            if (isFlintAndSteel(pack.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Flint and steel in the pack, or already in the main hand (operator-placed). */
    public static boolean hasFlintAndSteel(Container pack, ItemStack mainhand) {
        return isFlintAndSteel(mainhand) || firstSlot(pack) >= 0;
    }

    /**
     * Whether burning {@code target} to death would cook its drops — the adult food animals
     * (cow/pig/chicken/sheep/rabbit) whose loot tables carry vanilla's smelting condition. Shares
     * {@link ForagePolicy#isHuntableFoodAnimal} so "worth hunting" and "worth cooking" can't drift apart.
     */
    public static boolean cooksWhenBurned(Entity target) {
        return ForagePolicy.isHuntableFoodAnimal(target);
    }

    /**
     * Whether setting {@code target} alight is meaningful at all: not already burning (re-lighting a
     * burning enemy is wasted durability — and it's the condition the behaviour was specified with)
     * and not fire immune (blazes, striders, wither skeletons — the fire would simply do nothing).
     */
    public static boolean ignitable(LivingEntity target) {
        return !target.isOnFire() && !target.fireImmune();
    }

    /**
     * Whether the mob's next melee swing would finish {@code health} — the cue to light the animal
     * <em>now</em>, so it's burning when it dies. {@code attackDamage} is the mob's live
     * {@code ATTACK_DAMAGE} attribute, which already folds in whatever weapon it's holding.
     *
     * <p>Erring early is harmless: the fire ticks finish a barely-surviving animal themselves, and
     * that kill cooks the drop just the same.</p>
     */
    public static boolean finisherHealthReached(float health, double attackDamage) {
        return health <= attackDamage;
    }

    /** The cook-the-kill trigger: a food animal, ignitable, and one swing from death. */
    public static boolean wantsCookFinisher(LivingEntity target, double attackDamage) {
        return cooksWhenBurned(target)
            && ignitable(target)
            && finisherHealthReached(target.getHealth(), attackDamage);
    }

    /**
     * The combat trigger: anything that <em>isn't</em> a cookable animal (those are the finisher's
     * business — torching one early would waste the meat's one chance at being cooked on the kill)
     * and isn't already on fire.
     */
    public static boolean wantsCombatIgnite(LivingEntity target) {
        return !cooksWhenBurned(target) && ignitable(target);
    }

    /** A fresh, empty rate window. */
    public static long[] newRateWindow() {
        long[] window = new long[RATE_MAX_IGNITES];
        Arrays.fill(window, NEVER);
        return window;
    }

    /**
     * Whether another light is allowed at {@code now} — i.e. fewer than {@link #RATE_MAX_IGNITES}
     * of the recorded stamps fall inside the trailing {@link #RATE_WINDOW_TICKS}.
     */
    public static boolean withinRate(long[] window, long now) {
        int recent = 0;
        for (long stamp : window) {
            if (stamp != NEVER && now - stamp < RATE_WINDOW_TICKS) {
                recent++;
            }
        }
        return recent < RATE_MAX_IGNITES;
    }

    /**
     * A <em>new</em> window with {@code now} recorded and the oldest stamp evicted. The caller swaps
     * its reference for the result — the array passed in is never mutated.
     */
    public static long[] recordIgnite(long[] window, long now) {
        long[] next = new long[window.length];
        System.arraycopy(window, 1, next, 0, window.length - 1);
        next[window.length - 1] = now;
        return next;
    }

    /** Ticks a cooking fire is left standing — {@value #BURN_MIN_TICKS}–{@value #BURN_MAX_TICKS}. */
    public static int burnTicks(RandomSource random) {
        return inclusive(random, BURN_MIN_TICKS, BURN_MAX_TICKS);
    }

    /** Ticks before the mob may consider another combat ignite — {@value #COMBAT_GAP_MIN_TICKS}–{@value #COMBAT_GAP_MAX_TICKS}. */
    public static int combatGapTicks(RandomSource random) {
        return inclusive(random, COMBAT_GAP_MIN_TICKS, COMBAT_GAP_MAX_TICKS);
    }

    private static int inclusive(RandomSource random, int minInclusive, int maxInclusive) {
        return random.nextInt(maxInclusive - minInclusive + 1) + minInclusive;
    }
}
