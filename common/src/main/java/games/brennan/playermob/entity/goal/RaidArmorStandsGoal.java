package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * Walk to nearby armor stands and swap in better gear from them.
 *
 * <p>Mirror of {@link RaidContainersGoal} but for {@link ArmorStand} entities
 * instead of block entities. Swap mechanic differs slightly: armor stand
 * keeps the displaced piece in the same slot (true swap), whereas containers
 * have spare slots so the displaced piece goes into "any free slot".</p>
 *
 * <p>Honours {@code mobGriefing} gamerule and combat-preemption priority
 * exactly like the container variant.</p>
 */
public final class RaidArmorStandsGoal extends Goal {

    private static final int LOOT_PAUSE_TICKS = 10;
    private static final int PATH_TIMEOUT_TICKS = 100;
    private static final int POST_VISIT_COOLDOWN = 20;
    private static final int EMPTY_SCAN_COOLDOWN = 40;
    private static final double REACH_DISTANCE_SQR = 4.0;

    private enum Phase { IDLE, PATHING, LOOTING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final double scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private ArmorStand target;

    public RaidArmorStandsGoal(PlayerMobEntity mob, double moveSpeed, double scanRadius) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        this.scanRadius = scanRadius;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        if (mob.getTarget() != null) return false;
        if (!mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;

        ArmorStand found = findClosestArmorStand();
        if (found == null) {
            scanCooldown = EMPTY_SCAN_COOLDOWN;
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
                && mob.getTarget() == null;
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
        if (target != null) {
            mob.markEntityExplored(target.getUUID());
            target = null;
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) {
            stop();
            return;
        }
        phaseTicks++;

        switch (phase) {
            case PATHING -> {
                if (mob.distanceToSqr(target) < REACH_DISTANCE_SQR) {
                    phase = Phase.LOOTING;
                    phaseTicks = 0;
                    mob.getNavigation().stop();
                    mob.getLookControl().setLookAt(target, 30f, 30f);
                } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
                    stop();
                }
            }
            case LOOTING -> {
                if (phaseTicks < LOOT_PAUSE_TICKS) return;
                // Cover all 6 equipment slots — head/chest/legs/feet + mainhand/offhand.
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    mob.tryReplaceFromArmorStand(target, slot);
                }
                stop();
            }
            default -> { /* IDLE */ }
        }
    }

    private ArmorStand findClosestArmorStand() {
        AABB box = mob.getBoundingBox().inflate(scanRadius);
        long now = mob.tickCount;
        List<ArmorStand> nearby = mob.level().getEntitiesOfClass(
            ArmorStand.class, box,
            s -> s.isAlive() && !mob.isEntityExplored(s.getUUID(), now));
        ArmorStand closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (ArmorStand s : nearby) {
            double dsq = mob.distanceToSqr(s);
            if (dsq < closestDistSq) {
                closestDistSq = dsq;
                closest = s;
            }
        }
        return closest;
    }
}
