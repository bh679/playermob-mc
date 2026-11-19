package games.brennan.playermob.entity;

/**
 * The pure decisions behind a PlayerMob's Taming hobby ({@code FeedAnimalsGoal}): how often it
 * acts on it, which animals it will feed, and how much food it takes from a chest. World-free —
 * primitives only — so it unit-tests like {@link ForagePolicy}; the goal reads the world and asks
 * here.
 *
 * <p><b>Never tames.</b> An animal that could be tamed but is still wild (a stray wolf, cat,
 * parrot, an unbroken horse) is never a feed target — an echo takes no animal for its own. See
 * {@link #mayFeed}.</p>
 */
public final class TamingPolicy {

    private TamingPolicy() {}

    /**
     * Most animals of one kind that may stand within {@link #HERD_RADIUS} before the mob stops
     * breeding them — a cap so a devoted echo can't grow a pen without bound. Babies are still fed
     * (it only speeds growth); a herd at the cap gets no more love.
     */
    public static final int HERD_CAP = 8;
    /** Radius (blocks) the herd cap and the partner search look within. */
    public static final double HERD_RADIUS = 8.0;
    /** Items taken from a chest per fetch: enough for one breeding pair, never a hoard. */
    public static final int FETCH_TAKE = 2;
    /** Containers tried per feeding before the mob gives up on finding food. */
    public static final int MAX_CONTAINER_TRIES = 3;

    /**
     * Chance a scan acts on the hobby: {@code taming / 10}. 0 never feeds; 10 feeds whenever it
     * finds an animal it may.
     */
    public static double scanChance(int taming) {
        if (taming <= DispositionTraits.MIN) return 0.0;
        if (taming >= DispositionTraits.MAX) return 1.0;
        return taming / (double) DispositionTraits.MAX;
    }

    /** True if a roll in {@code [0,1)} acts on the hobby at this score. */
    public static boolean rollsToAct(int taming, double roll) {
        return roll < scanChance(taming);
    }

    /**
     * Whether the mob may feed this animal right now.
     *
     * @param baby           the animal is a baby — feeding speeds its growth
     * @param age            {@code AgeableMob#getAge}: 0 for an adult ready to breed, &gt;0 on cooldown
     * @param inLove         already in love — feeding again would waste food
     * @param canFallInLove  vanilla's own breed-readiness check
     * @param wildTamable    a tamable animal nobody owns — never fed (the mob never tames)
     * @param herdSize       animals of this kind within {@link #HERD_RADIUS}, this one included
     * @param hasPartner     another adult of its kind nearby that could breed with it
     */
    public static boolean mayFeed(boolean baby, int age, boolean inLove, boolean canFallInLove,
                                  boolean wildTamable, int herdSize, boolean hasPartner) {
        if (wildTamable) return false;
        if (baby) return true;
        return age == 0 && !inLove && canFallInLove && hasPartner && herdSize < HERD_CAP;
    }

    /**
     * Whether another adult can complete a pair: same kind, adult, not a wild tamable, and either
     * already in love or ready to be fed into it.
     */
    public static boolean isPartner(boolean baby, int age, boolean inLove, boolean canFallInLove,
                                    boolean wildTamable) {
        if (wildTamable || baby) return false;
        return inLove || (age == 0 && canFallInLove);
    }
}
