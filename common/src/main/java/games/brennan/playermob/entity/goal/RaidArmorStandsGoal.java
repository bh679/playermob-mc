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
 * Walk to nearby armor stands and swap in better gear from them, one piece
 * at a time with a random 0.3–2 second delay per swap (matches
 * {@link RaidContainersGoal}'s pace).
 *
 * <p>Mirror of the chest raider but for {@link ArmorStand} entities. Swap
 * semantics differ: the armor stand keeps the displaced piece in the same
 * slot (true swap), since stands have no spare slots to dump into.</p>
 *
 * <p>No "open" visual — armor stands aren't containers in the lid-animation
 * sense. The mob just walks up, pauses, and methodically equips piece by
 * piece.</p>
 *
 * <p>Honours {@code mobGriefing} gamerule + combat-preemption priority.</p>
 */
public final class RaidArmorStandsGoal extends Goal {

    private static final int APPROACH_PAUSE_TICKS = 20;    // 1s once in reach before first swap
    private static final int MIN_SWAP_DELAY_TICKS = 6;     // 0.3s minimum per swap (matches RaidContainersGoal)
    private static final int MAX_SWAP_DELAY_TICKS = 40;    // 2s maximum per swap (matches RaidContainersGoal)
    private static final int PATH_TIMEOUT_TICKS = 100;     // 5s to reach
    private static final int POST_VISIT_COOLDOWN = 20;
    private static final int EMPTY_SCAN_COOLDOWN = 40;
    private static final double REACH_DISTANCE_SQR = 4.0;

    private enum Phase { IDLE, PATHING, APPROACHING, LOOTING }

    /** Iteration order over the 6 equipment slots. Caches as field to avoid per-tick allocation. */
    private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final double scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private ArmorStand target;

    private int slotCursor = 0;       // index into SLOTS
    private int nextSwapAt = -1;      // tick to fire next swap, -1 = unscheduled

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
        slotCursor = 0;
        nextSwapAt = -1;
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
        slotCursor = 0;
        nextSwapAt = -1;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
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
                    mob.getNavigation().stop();
                    mob.getLookControl().setLookAt(target, 30f, 30f);
                    phase = Phase.APPROACHING;
                    phaseTicks = 0;
                } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
                    stop();
                }
            }
            case APPROACHING -> {
                if (phaseTicks >= APPROACH_PAUSE_TICKS) {
                    phase = Phase.LOOTING;
                    phaseTicks = 0;
                    slotCursor = 0;
                    nextSwapAt = -1;
                }
            }
            case LOOTING -> tickLooting();
            default -> { /* IDLE */ }
        }
    }

    private void tickLooting() {
        if (nextSwapAt == -1) {
            // Advance to the next slot we actually want from the stand.
            while (slotCursor < SLOTS.length
                    && !mob.wouldReplaceFromArmorStand(target, SLOTS[slotCursor])) {
                slotCursor++;
            }
            if (slotCursor >= SLOTS.length) {
                stop();
                return;
            }
            int delay = MIN_SWAP_DELAY_TICKS
                + mob.getRandom().nextInt(MAX_SWAP_DELAY_TICKS - MIN_SWAP_DELAY_TICKS + 1);
            nextSwapAt = mob.tickCount + delay;
            return;
        }

        if (mob.tickCount >= nextSwapAt) {
            mob.tryReplaceFromArmorStand(target, SLOTS[slotCursor]);
            slotCursor++;
            nextSwapAt = -1;
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
