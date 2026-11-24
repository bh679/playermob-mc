package games.brennan.playermob.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How far from its target a {@link PlayerMobEntity} stands in a hand-to-hand fight, and how far its
 * swing reaches. A player swings from up to {@link #PLAYER_REACH} blocks and good fighters use that,
 * keeping just inside it; a novice crowds in. So the mob holds a distance set by its melee skill
 * ({@code reactionSpeed(ReactionChannel.MELEE)}, 0 to 10): skill 0 holds at
 * {@link #MIN_HOLD_FRACTION} of reach, skill 10 at {@link #MAX_HOLD_FRACTION}.
 *
 * <p>Every distance here is a player's reach measure: from the mob's eyes to the nearest point of the
 * target's hitbox ({@link #eyeToBox}). The policy is stateless, so it unit-tests without a server
 * (mirrors {@link ReachPolicy}).</p>
 */
public final class MeleeSpacing {

    private MeleeSpacing() {}

    /** A player's melee reach (vanilla {@code entity_interaction_range}, survival). */
    public static final double PLAYER_REACH = 3.0;
    /** Fraction of reach a skill-0 mob holds at. */
    public static final double MIN_HOLD_FRACTION = 0.45;
    /** Fraction of reach a skill-10 mob holds at. */
    public static final double MAX_HOLD_FRACTION = 0.85;
    /** Half-width of the dead band around the hold distance, so the mob doesn't jitter in and out. */
    public static final double BAND = 0.35;
    /** The mob always closes to at least this far inside reach, so it can still land a swing. */
    public static final double SWING_MARGIN = 0.15;

    /** What the mob should do this tick. */
    public enum Move {
        /** Too far: walk in. */
        ADVANCE,
        /** In the band: stand and fight. */
        HOLD,
        /** Too close: step back. */
        RETREAT
    }

    /** Fraction of reach the mob holds at, for melee skill {@code skill} (clamped to 0 to 10). */
    public static double holdFraction(int skill) {
        int s = Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, skill));
        return MIN_HOLD_FRACTION + (MAX_HOLD_FRACTION - MIN_HOLD_FRACTION) * s / (double) DispositionTraits.MAX;
    }

    /** The eye-to-hitbox distance the mob aims to fight at. */
    public static double holdDistance(int skill) {
        return holdFraction(skill) * PLAYER_REACH;
    }

    /** Beyond this the mob walks in: the top of the band, but never past swing reach. */
    public static double advanceBeyond(int skill) {
        return Math.min(holdDistance(skill) + BAND, PLAYER_REACH - SWING_MARGIN);
    }

    /** Closer than this the mob steps back. */
    public static double retreatWithin(int skill) {
        return holdDistance(skill) - BAND;
    }

    /** Whether to advance, hold or step back at eye-to-hitbox distance {@code dist}. */
    public static Move move(int skill, double dist) {
        if (dist > advanceBeyond(skill)) return Move.ADVANCE;
        if (dist < retreatWithin(skill)) return Move.RETREAT;
        return Move.HOLD;
    }

    /** True if a swing from eye-to-hitbox distance {@code dist} connects. */
    public static boolean inSwingReach(double dist) {
        return dist <= PLAYER_REACH;
    }

    /** Reach distance from {@code mob}'s eyes to {@code target}'s hitbox, as a player's reach is measured. */
    public static double reach(LivingEntity mob, LivingEntity target) {
        Vec3 eye = mob.getEyePosition();
        AABB box = target.getBoundingBox();
        return eyeToBox(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * Distance from the point {@code (ex, ey, ez)} (the mob's eyes) to the nearest point of the box
     * {@code [minX..maxX] x [minY..maxY] x [minZ..maxZ]} (the target's hitbox); 0 when inside it.
     */
    public static double eyeToBox(double ex, double ey, double ez,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - ex, 0.0), ex - maxX);
        double dy = Math.max(Math.max(minY - ey, 0.0), ey - maxY);
        double dz = Math.max(Math.max(minZ - ez, 0.0), ez - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
