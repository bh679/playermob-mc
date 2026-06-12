package games.brennan.playermob.entity;

import java.util.UUID;

/**
 * Pure decision core for {@link games.brennan.playermob.entity.goal.FollowLovedOneGoal} —
 * the distance bands that turn "I love this entity" into follow / park / sprint, plus the
 * deterministic leadership tiebreak that stops two mutually-loving mobs from freezing
 * face-to-face. Primitives + {@link UUID} only (no Minecraft types), so it unit-tests
 * without a game bootstrap, exactly like {@link GiftPolicy} and {@link DispositionResolver}.
 *
 * <p><b>Bands</b> (blocks, hysteretic so the mob doesn't jitter at the edge):
 * <ul>
 *   <li>≤ {@link #STOP_DISTANCE} — close enough; stop following so the mob's own goals run.</li>
 *   <li>{@link #STOP_DISTANCE}..{@link #START_DISTANCE} — dead-band: keep doing whatever it
 *       was (follow if already following, idle if parked).</li>
 *   <li>&gt; {@link #START_DISTANCE} — begin following.</li>
 *   <li>&gt; {@link #SPRINT_DISTANCE} — too far for a walk to keep pace; sprint to catch up.</li>
 *   <li>&gt; {@link #SCAN_RANGE} — out of range; the loved one is lost.</li>
 * </ul>
 *
 * <p><b>Leadership.</b> When A and B love each other, only one should follow — otherwise
 * both walk to {@code STOP} of each other and stall. {@link #leads(UUID, UUID)} breaks the
 * tie by UUID order (lower leads): a total, stable choice with no per-tick flicker, so the
 * leader keeps doing its own thing and the follower trails it — the pair travels together.
 * A loved player can't follow back, so a mob always follows a loved player outright.</p>
 */
public final class FollowLovedOnePolicy {

    private FollowLovedOnePolicy() {}

    /** Park within this many blocks — stop following so the mob's own goals get a turn. */
    public static final double STOP_DISTANCE = 3.0;
    /** Begin following once the loved one is farther than this (hysteresis above STOP). */
    public static final double START_DISTANCE = 6.0;
    /** Beyond this a walk won't keep pace — sprint to catch up. */
    public static final double SPRINT_DISTANCE = 10.0;
    /** Past this the loved one is out of follow range and is dropped. */
    public static final double SCAN_RANGE = 24.0;

    /** Normal follow pathfinder speed multiplier. */
    public static final double WALK_SPEED = 1.0;
    /** Catch-up pathfinder speed multiplier when farther than {@link #SPRINT_DISTANCE}. */
    public static final double SPRINT_SPEED = 1.3;

    /** Whether to <em>begin</em> following — the loved one has drifted past the start band. */
    public static boolean wantsToFollow(double distance) {
        return distance > START_DISTANCE;
    }

    /** Whether to <em>keep</em> following — still beyond park distance and within range. */
    public static boolean keepFollowing(double distance) {
        return distance > STOP_DISTANCE && distance <= SCAN_RANGE;
    }

    /** Follow speed for the current gap: sprint when far, otherwise walk. */
    public static double speedFor(double distance) {
        return distance > SPRINT_DISTANCE ? SPRINT_SPEED : WALK_SPEED;
    }

    /**
     * In a mutual-love bond, whether {@code self} is the leader — and so should <em>not</em>
     * follow {@code other} ({@code other} follows it instead). Lower UUID leads; the order is
     * total and stable, so the roles never flip tick-to-tick. Never consulted for a loved
     * player (players can't follow back).
     */
    public static boolean leads(UUID self, UUID other) {
        return self.compareTo(other) < 0;
    }
}
