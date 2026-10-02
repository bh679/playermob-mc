package games.brennan.playermob.entity;

/**
 * When a mob riding a carriage should hop because it is blocked by something a door can't explain.
 * Pure (primitives only), so it unit-tests without a game bootstrap.
 *
 * <p>The case this exists for: a doorway that opens straight onto carpet. The path is clear and the
 * doors are open, yet a mob standing in the door block cannot walk up the carpet's 1/16-block lip
 * and stands there forever — while the door stuck-probe, with nothing better to try, flaps the
 * doors shut on it. A player in that spot taps jump; so does the mob.</p>
 */
public final class LipHopPolicy {

    private LipHopPolicy() {}

    /** Ticks without headway before a hop — about a third of a second, long enough not to hop at every hesitation. */
    public static final int STALL_TICKS = 7;
    /** Ticks between hops, so a mob that is genuinely walled in doesn't bounce on the spot. */
    public static final int COOLDOWN_TICKS = 20;
    /**
     * Hops in a row that got the mob nowhere before it stops trying. Whatever is in the way is not
     * a lip; hopping on the spot at the end of the train helps no one. Counting restarts once the
     * mob has moved {@link #RESET_DISTANCE} from where it gave up.
     */
    public static final int MAX_FRUITLESS_HOPS = 3;
    /** Distance (blocks, carriage frame) from the last hop that counts as having got somewhere. */
    public static final double RESET_DISTANCE = 1.0;

    /** Whether another hop is worth trying after {@code fruitlessHops} that went nowhere. */
    public static boolean mayStillTry(int fruitlessHops) {
        return fruitlessHops < MAX_FRUITLESS_HOPS;
    }

    /** True once the mob is {@link #RESET_DISTANCE} from where it last hopped. */
    public static boolean gotSomewhere(double dx, double dz) {
        return dx * dx + dz * dz >= RESET_DISTANCE * RESET_DISTANCE;
    }

    /** Per-tick movement (blocks, in the carriage's frame) below which the mob is not getting anywhere. */
    public static final double HEADWAY = 0.02;

    /** True if a displacement of {@code (dx, dz)} over one tick counts as no headway. */
    public static boolean stalled(double dx, double dz) {
        return dx * dx + dz * dz < HEADWAY * HEADWAY;
    }

    /**
     * Whether to hop this tick.
     *
     * @param tryingToMove   a goal is driving the mob and it isn't deliberately standing still
     * @param onGround       the mob has footing to jump from
     * @param stallTicks     consecutive ticks without headway
     * @param cooldownTicks  ticks left since the last hop
     * @param doorObstructs  a nearby door is in the way of the mob's heading — then the door is the
     *                       problem and the door reflex's to solve, not a hop's
     */
    public static boolean shouldHop(boolean tryingToMove, boolean onGround, int stallTicks,
                                    int cooldownTicks, boolean doorObstructs) {
        return tryingToMove && onGround && !doorObstructs
            && cooldownTicks <= 0 && stallTicks >= STALL_TICKS;
    }
}
