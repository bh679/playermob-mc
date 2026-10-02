package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Walking a Dungeon-Train end pad. A pad is a walkway three blocks wide with a drop on both sides,
 * running from a group's end door to the seam with the next group. General path-following is free
 * to cut a corner there, and at a sprint a mob that does goes over the edge.
 *
 * <p>So while a mob stands on a pad, the train goals steer it themselves instead: straight along
 * the train's centre line toward where it is going, through the move control. The route across a
 * pad is a straight line by construction — pad, seam, pad, door all share that centre line — and the
 * gait is still the mob's own (see {@code PlayerLikeMoveControl}); only the steering changes.</p>
 */
final class PadWalk {

    /** How far ahead on the centre line to aim, so a mob that stepped onto the pad off-centre eases back onto it. */
    private static final double LOOKAHEAD = 2.0;

    private PadWalk() {}

    /**
     * If {@code mob} is on an end pad, steer it straight along the centre line toward
     * {@code target} (whose Z is that centre line) and return {@code true}; the caller must not
     * issue a navigation path this tick. Returns {@code false}, touching nothing, anywhere else.
     */
    static boolean drive(PlayerMobEntity mob, Vec3 target, double speed) {
        if (target == null || TrainConfinement.padSide(mob) == 0) {
            return false;
        }
        mob.getNavigation().stop();
        double dx = target.x - mob.getX();
        double aimX = mob.getX() + Math.max(-LOOKAHEAD, Math.min(LOOKAHEAD, dx));
        mob.getMoveControl().setWantedPosition(aimX, mob.getY(), target.z, speed);
        return true;
    }
}
