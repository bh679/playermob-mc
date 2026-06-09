package games.brennan.playermob.mixin.support;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.phys.AABB;

import java.util.function.Predicate;

/**
 * Shared support for the Brain/Sensor-based hostility mixins (piglin, piglin brute, hoglin).
 *
 * <p>Those mobs resolve their attack target through a {@code findNearestValidAttackTarget(…)}
 * method that returns an {@code Optional<? extends LivingEntity>} — the seam where a
 * {@link PlayerMobEntity} is a type-valid target (unlike the {@code Player}-typed brain
 * <em>memories</em>, which can't hold one). Each mixin reuses this single scan so a PlayerMob
 * is validated by the same rule vanilla applies to the player candidate.</p>
 *
 * <p>Validity is delegated to {@link Sensor#isEntityAttackable(net.minecraft.world.entity.LivingEntity,
 * net.minecraft.world.entity.LivingEntity)} — vanilla's own helper covering attackable type,
 * within-follow-range distance, line-of-sight, and team checks — so we neither loosen nor
 * reimplement vanilla's targeting rules.</p>
 */
public final class PlayerMobTargets {

    private PlayerMobTargets() {
    }

    /**
     * The nearest {@link PlayerMobEntity} that {@code mob} could attack right now, or
     * {@code null} if none. Candidates must pass {@link Sensor#isEntityAttackable} and the
     * optional {@code extra} predicate (e.g. the piglin gold-armor exemption).
     *
     * @param mob   the hunting mob (brain-AI mob; always has a follow-range attribute)
     * @param extra additional per-mob gate, or {@code null} for none
     */
    public static PlayerMobEntity nearestAttackable(Mob mob, Predicate<PlayerMobEntity> extra) {
        double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        AABB searchBox = mob.getBoundingBox().inflate(range, range, range);

        PlayerMobEntity nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (PlayerMobEntity candidate : mob.level().getEntitiesOfClass(PlayerMobEntity.class, searchBox)) {
            if (!Sensor.isEntityAttackable(mob, candidate)) {
                continue;
            }
            if (extra != null && !extra.test(candidate)) {
                continue;
            }
            double distanceSq = mob.distanceToSqr(candidate);
            if (distanceSq < nearestSq) {
                nearestSq = distanceSq;
                nearest = candidate;
            }
        }
        return nearest;
    }
}
