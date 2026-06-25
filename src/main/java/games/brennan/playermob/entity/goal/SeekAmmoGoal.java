package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * Mid-combat restock: a PlayerMob that's out of ammo but still holding a bow/crossbow walks to the nearest
 * dropped round its weapon accepts — provided the enemy isn't breathing down its neck.
 *
 * <p>Structurally a combat-time {@link CollectFloorItemsGoal}: scan for a nearby ammo {@link ItemEntity} →
 * path to it → grab via {@link PlayerMobEntity#tryPickUpFloorItem}. It runs <em>during</em> a fight (a live
 * target), only when the mob owns a ranged weapon it can't currently feed
 * ({@link PlayerMobEntity#hasRangedAmmo(net.minecraft.world.item.ItemStack)} is false for its best ranged
 * weapon) and the target is farther than {@link #TOO_CLOSE_SQR}, so the mob never turns its back on an adjacent
 * enemy to chase ammo. What counts as "ammo" is weapon-aware via {@link PlayerMobEntity#wantsAsAmmo} — arrows
 * for any ranged weapon, plus fireworks when the mob owns a crossbow to fire them with.</p>
 *
 * <p>Registered at priority 2 <em>before</em> {@link WeaponAwareAttackGoal} so this narrow case wins the MOVE
 * slot; once a round is grabbed its {@code canUse} goes false and the attack goal re-draws the ranged weapon.
 * When the mob can't restock — nothing nearby, or the enemy is too close — this goal stands down and the attack
 * goal equips melee instead. Gated by {@link PlayerMobConfig#seekArrowsWhenEmpty()}; a no-op when
 * {@code requireArrows} is off (ammo is never empty).</p>
 */
public final class SeekAmmoGoal extends Goal implements DescribableGoal {

    private static final int PATH_TIMEOUT_TICKS = 100;     // 5s to reach a round before giving up
    private static final int POST_VISIT_COOLDOWN = 10;     // 0.5s after a pickup before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 20;     // 1s between scans when nothing found
    private static final double REACH_DISTANCE_SQR = 4.0;  // 2 blocks
    private static final double TOO_CLOSE_SQR = 36.0;      // don't fetch if the enemy is within 6 blocks

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final double scanRadius;

    private int scanCooldown = 0;
    private int phaseTicks = 0;
    private ItemEntity target;

    public SeekAmmoGoal(PlayerMobEntity mob, double moveSpeed, double scanRadius) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        this.scanRadius = scanRadius;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Fetching ammo";
    }

    @Override
    public String subObjective() {
        return target != null ? "restocking" : null;
    }

    @Override
    public boolean canUse() {
        if (!PlayerMobConfig.seekArrowsWhenEmpty()) return false;
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        LivingEntity enemy = mob.getTarget();
        if (enemy == null || !enemy.isAlive()) return false;             // only during combat
        if (!mob.ownsRangedWeapon()) return false;                       // only worth it with a ranged weapon
        if (mob.hasRangedAmmo(mob.bestRangedWeaponStack())) return false; // only when we can't fire it
        if (mob.distanceToSqr(enemy) <= TOO_CLOSE_SQR) return false;      // enemy too close → melee instead

        ItemEntity found = findClosestAmmo();
        if (found == null) {
            scanCooldown = EMPTY_SCAN_COOLDOWN;
            return false;
        }
        target = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity enemy = mob.getTarget();
        return target != null
            && target.isAlive()
            && !target.isRemoved()
            && mob.isAlive()
            && enemy != null
            && enemy.isAlive()
            && !mob.hasRangedAmmo(mob.bestRangedWeaponStack())
            && mob.distanceToSqr(enemy) > TOO_CLOSE_SQR
            && phaseTicks <= PATH_TIMEOUT_TICKS
            && TrainConfinement.allowsTarget(mob, target);
    }

    @Override
    public void start() {
        phaseTicks = 0;
        mob.getNavigation().moveTo(target, moveSpeed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
        phaseTicks = 0;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            stop();
            return;
        }
        phaseTicks++;
        mob.getLookControl().setLookAt(target, 30f, 30f);

        if (mob.distanceToSqr(target) < REACH_DISTANCE_SQR) {
            mob.getNavigation().stop();
            mob.tryPickUpFloorItem(target);
            stop();
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop();
        } else if (mob.getNavigation().isDone()) {
            // Path finished short of reach (round nudged by slopes/flow) — re-issue.
            mob.getNavigation().moveTo(target, moveSpeed);
        }
    }

    private ItemEntity findClosestAmmo() {
        AABB box = mob.getBoundingBox().inflate(scanRadius);
        List<ItemEntity> nearby = mob.level().getEntitiesOfClass(
            ItemEntity.class, box,
            e -> e.isAlive() && !e.hasPickUpDelay()
                && mob.wantsAsAmmo(e.getItem())
                && TrainConfinement.allowsTarget(mob, e));
        ItemEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (ItemEntity e : nearby) {
            double dsq = mob.distanceToSqr(e);
            if (dsq < closestDistSq) {
                closestDistSq = dsq;
                closest = e;
            }
        }
        return closest;
    }
}
