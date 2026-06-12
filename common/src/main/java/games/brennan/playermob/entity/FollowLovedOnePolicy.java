package games.brennan.playermob.entity;

import java.util.UUID;

/**
 * Pure decision core for {@link games.brennan.playermob.entity.goal.FollowLovedOneGoal} —
 * the distances that decide when a mob should drop what it's doing and <em>catch up</em> to
 * an individual it loves, plus the deterministic leadership tiebreak that picks which of a
 * mutually-loving pair does the chasing. Primitives + {@link UUID} only (no Minecraft types),
 * so it unit-tests without a game bootstrap, like {@link GiftPolicy} / {@link DispositionResolver}.
 *
 * <p><b>Catch-up, not a constant shadow.</b> Following is a <em>leash</em>, not a glue: the
 * mob does its normal thing until the loved one gets too far, then sprints to rejoin and goes
 * back to normal once caught up. "Too far" is measured in carriages on a Dungeon Train and in
 * blocks anywhere else:
 * <ul>
 *   <li>On a train — start once <b>more than {@value #FOLLOW_CARRIAGES} carriages</b> apart
 *       ({@link #followByCarriages}); keep catching up until back within
 *       {@value #KEEP_CARRIAGES} ({@link #keepByCarriages}). Separation is the difference of
 *       {@code TrainConfinement.carriageIndex} (the room index is monotonic along the whole
 *       train), so "2 carriages apart" is literal.</li>
 *   <li>Off a train — start past {@link #FOLLOW_BLOCKS} blocks ({@link #followByBlocks}); keep
 *       going until within {@link #KEEP_BLOCKS} ({@link #keepByBlocks}).</li>
 * </ul>
 * Either way the chase runs at {@link #CATCH_UP_SPEED} (a sprint — it only ever happens when
 * far), and inside the gap the mob just behaves normally.</p>
 *
 * <p><b>Leadership.</b> When A and B love each other, only one should chase — {@link
 * #leads(UUID, UUID)} makes the lower-UUID mob the leader (it keeps doing its own thing) and
 * the other the follower. Total, stable order → no per-tick flicker; a loved player can't
 * follow back, so a mob always chases a loved player outright.</p>
 */
public final class FollowLovedOnePolicy {

    private FollowLovedOnePolicy() {}

    /** On a train, begin catching up once MORE than this many carriages separate them. */
    public static final int FOLLOW_CARRIAGES = 2;
    /** ...and keep catching up until back within this many carriages (hysteresis below FOLLOW). */
    public static final int KEEP_CARRIAGES = 1;

    /** Off a train, begin catching up once farther apart than this (blocks). */
    public static final double FOLLOW_BLOCKS = 24.0;
    /** ...and keep going until this close (blocks). */
    public static final double KEEP_BLOCKS = 10.0;

    /** How far to look for a loved one to chase (blocks) — generous, several carriages. */
    public static final double SCAN_RANGE = 64.0;

    /** Catch-up pathfinder speed multiplier; following only ever happens when far, so always a sprint. */
    public static final double CATCH_UP_SPEED = 1.3;

    /** On a train: begin following once the carriage gap exceeds {@link #FOLLOW_CARRIAGES}. */
    public static boolean followByCarriages(int carriagesApart) {
        return carriagesApart > FOLLOW_CARRIAGES;
    }

    /** On a train: keep following until caught up to within {@link #KEEP_CARRIAGES}. */
    public static boolean keepByCarriages(int carriagesApart) {
        return carriagesApart > KEEP_CARRIAGES;
    }

    /** Off a train: begin following past {@link #FOLLOW_BLOCKS}. */
    public static boolean followByBlocks(double distance) {
        return distance > FOLLOW_BLOCKS;
    }

    /** Off a train: keep following until within {@link #KEEP_BLOCKS} (and still inside scan range). */
    public static boolean keepByBlocks(double distance) {
        return distance > KEEP_BLOCKS && distance <= SCAN_RANGE;
    }

    /**
     * In a mutual-love bond, whether {@code self} is the leader (lower UUID) — and so should
     * <em>not</em> chase {@code other} ({@code other} chases it instead). Total, stable order,
     * never flips tick-to-tick. Never consulted for a loved player.
     */
    public static boolean leads(UUID self, UUID other) {
        return self.compareTo(other) < 0;
    }
}
