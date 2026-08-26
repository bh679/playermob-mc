package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * Walk to the nearest wanted item lying on the ground and pick it up.
 *
 * <p>Third member of the "raider" goal family alongside
 * {@link RaidContainersGoal} and {@link RaidArmorStandsGoal}, and structurally
 * a trimmed {@link RaidArmorStandsGoal}: scan → path → grab. Simpler because
 * items are <em>consumed</em> on pickup (or despawn on their own), so there's
 * no "recently explored" cooldown map and no per-slot swap delay — the pickup
 * is instantaneous once the mob is in reach.</p>
 *
 * <p>The <em>what</em> (equip upgrade vs hoard vs ignore) lives entirely on
 * {@link PlayerMobEntity#wantsToPickUp} (the scan filter) and
 * {@link PlayerMobEntity#tryPickUpFloorItem} (the grab). This goal only owns
 * the <em>seeking</em>. Calling {@code tryPickUpFloorItem} directly (rather
 * than leaning on vanilla's proximity auto-pickup) means the behaviour works
 * even on mobs whose saved {@code CanPickUpLoot} flag is false.</p>
 *
 * <p>Combat preempts (registered below {@link WeaponAwareAttackGoal}); honours
 * the {@code mobGriefing} gamerule, matching the other raid goals.</p>
 */
public final class CollectFloorItemsGoal extends Goal implements DescribableGoal {

    private static final int PATH_TIMEOUT_TICKS = 100;    // 5s to reach an item before giving up
    private static final int POST_VISIT_COOLDOWN = 10;    // 0.5s after a pickup before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 20;    // 1s between scans when nothing found
    private static final double REACH_DISTANCE_SQR = 4.0; // 2 blocks

    private enum Phase { IDLE, PATHING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final double scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private ItemEntity target;

    public CollectFloorItemsGoal(PlayerMobEntity mob, double moveSpeed, double scanRadius) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        this.scanRadius = scanRadius;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Collecting items";
    }

    @Override
    public String subObjective() {
        return phase == Phase.PATHING ? "approaching" : null;
    }

    @Override
    public boolean canUse() {
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        if (mob.getTarget() != null) return false; // combat preempts
        if (!GameRuleCompat.mobGriefing(mob.level())) return false;
        if (!mob.allowsScavenging(PlayerMobConfig.collectFloorItems())) return false; // floor-item collecting gated by config

        ItemEntity found = findClosestWantedItem();
        if (found == null) {
            scanCooldown = mob.reactTicks(EMPTY_SCAN_COOLDOWN);
            return false;
        }
        target = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE
                && target != null
                && target.isAlive()
                && !target.isRemoved()
                && mob.isAlive()
                && mob.getTarget() == null
                && TrainConfinement.allowsTarget(mob, target);
    }

    @Override
    public void start() {
        phase = Phase.PATHING;
        phaseTicks = 0;
        mob.getNavigation().moveTo(target, moveSpeed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
        phase = Phase.IDLE;
        phaseTicks = 0;
        scanCooldown = mob.reactTicks(POST_VISIT_COOLDOWN);
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
            // Path finished short of reach (item nudged by slopes/flow) — re-issue.
            mob.getNavigation().moveTo(target, moveSpeed);
        }
    }

    private ItemEntity findClosestWantedItem() {
        AABB box = mob.getBoundingBox().inflate(scanRadius);
        List<ItemEntity> nearby = mob.level().getEntitiesOfClass(
            ItemEntity.class, box,
            //? if >=26 {
            /*// 26.x added a leading ServerLevel arg to Mob.wantsToPickUp; the scan runs server-side.
            e -> e.isAlive() && !e.hasPickUpDelay()
                && mob.level() instanceof net.minecraft.server.level.ServerLevel sl
                && mob.wantsToPickUp(sl, e.getItem())
                && TrainConfinement.allowsTarget(mob, e));
            *///?} else {
            e -> e.isAlive() && !e.hasPickUpDelay() && mob.wantsToPickUp(e.getItem())
                && TrainConfinement.allowsTarget(mob, e));
            //?}
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
