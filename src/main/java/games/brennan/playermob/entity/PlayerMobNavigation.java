package games.brennan.playermob.entity;

import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Ground navigation for a {@link PlayerMobEntity}. Identical to vanilla except for the speed its
 * stuck detection sees.
 *
 * <p>Vanilla decides "stuck" from {@code mob.getSpeed()}: the mob must cover {@code speed^2 x 25}
 * blocks every 100 ticks, and gets {@code distance / speed x 60} ticks to reach each node. With a
 * player's 0.10 speed those become a quarter of a block per five seconds and thirty seconds per
 * block — a wedged mob would stand there far longer than the recovery behaviours in this mod were
 * tuned for. So for the duration of the check the entity reports
 * {@link PlayerSpeeds#STUCK_DETECTION_SPEED}, the value those thresholds always had.</p>
 */
public class PlayerMobNavigation extends GroundPathNavigation {

    private final PlayerMobEntity playerMob;

    public PlayerMobNavigation(PlayerMobEntity mob, Level level) {
        super(mob, level);
        this.playerMob = mob;
    }

    @Override
    protected void doStuckDetection(Vec3 positionVec3) {
        playerMob.setStuckDetectionView(true);
        try {
            super.doStuckDetection(positionVec3);
        } finally {
            playerMob.setStuckDetectionView(false);
        }
    }
}
