package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.EnumSet;

/**
 * Walk to nearby chests/barrels and swap in better gear.
 *
 * <p>State machine:</p>
 * <ol>
 *   <li><b>Scan</b> — every time the goal selector evaluates {@link #canUse},
 *       iterate a cube of {@code scanRadius} around the mob, find the nearest
 *       lootable container that isn't in the mob's "recently explored"
 *       cooldown set, and target it.</li>
 *   <li><b>Path</b> — navigate to the container. 5-second timeout in case
 *       it's unreachable.</li>
 *   <li><b>Loot</b> — pause briefly (10 ticks) for visual "rummage", then
 *       iterate slots and swap into the mob's equipment via
 *       {@link EquipmentEvaluator#tryReplaceFromContainer}.</li>
 *   <li><b>Cleanup</b> — mark the container position as recently explored
 *       so the mob doesn't loop back immediately, brief cooldown before
 *       rescanning.</li>
 * </ol>
 *
 * <p>Honours the {@code mobGriefing} gamerule — when off, the goal never
 * fires (matches vanilla convention for mobs that mutate world state).</p>
 *
 * <p>Combat preempts raiding: registered at priority 3, below
 * {@link WeaponAwareAttackGoal} at priority 2 — when the mob picks up a
 * target the attack goal grabs the MOVE flag and this goal stops.</p>
 */
public final class RaidContainersGoal extends Goal {

    private static final int LOOT_PAUSE_TICKS = 10;
    private static final int PATH_TIMEOUT_TICKS = 100; // 5 seconds
    private static final int POST_VISIT_COOLDOWN = 20;
    private static final int EMPTY_SCAN_COOLDOWN = 40;
    private static final double REACH_DISTANCE_SQR = 4.0; // 2 blocks

    private enum Phase { IDLE, PATHING, LOOTING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final int scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private BlockPos targetPos;

    public RaidContainersGoal(PlayerMobEntity mob, double moveSpeed, int scanRadius) {
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
        if (mob.getTarget() != null) return false; // combat preempts
        if (!mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;

        BlockPos found = findClosestContainer();
        if (found == null) {
            scanCooldown = EMPTY_SCAN_COOLDOWN;
            return false;
        }
        targetPos = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE
                && targetPos != null
                && mob.getTarget() == null;
    }

    @Override
    public void start() {
        phase = Phase.PATHING;
        phaseTicks = 0;
        mob.getNavigation().moveTo(
            targetPos.getX() + 0.5,
            targetPos.getY(),
            targetPos.getZ() + 0.5,
            moveSpeed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (targetPos != null) {
            mob.markBlockExplored(targetPos);
            targetPos = null;
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public void tick() {
        if (targetPos == null) return;
        phaseTicks++;

        switch (phase) {
            case PATHING -> tickPathing();
            case LOOTING -> tickLooting();
            default -> { /* IDLE — should not happen while ticking */ }
        }
    }

    private void tickPathing() {
        double dx = (targetPos.getX() + 0.5) - mob.getX();
        double dy = (targetPos.getY() + 0.5) - mob.getY();
        double dz = (targetPos.getZ() + 0.5) - mob.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < REACH_DISTANCE_SQR) {
            phase = Phase.LOOTING;
            phaseTicks = 0;
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(
                targetPos.getX() + 0.5,
                targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5);
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop();
        }
    }

    private void tickLooting() {
        if (phaseTicks < LOOT_PAUSE_TICKS) return;
        BlockEntity be = mob.level().getBlockEntity(targetPos);
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                mob.tryReplaceFromContainer(container, i);
            }
        }
        stop();
    }

    /**
     * Brute-force scan of {@code scanRadius³} positions around the mob.
     * For a 12-block radius that's ~14k positions per scan — only invoked
     * every {@link #EMPTY_SCAN_COOLDOWN} ticks when no target. {@code getBlockEntity}
     * is O(1) on the loaded chunk's BE map so this is acceptable for a single
     * mob; if PlayerMob spawn rates ever climb, swap to a chunk-walk that
     * iterates the chunk's blockEntities map directly.
     */
    private BlockPos findClosestContainer() {
        BlockPos mobPos = mob.blockPosition();
        Level level = mob.level();
        long now = mob.tickCount;
        BlockPos closest = null;
        double closestDistSq = Double.MAX_VALUE;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    cursor.set(mobPos.getX() + dx, mobPos.getY() + dy, mobPos.getZ() + dz);
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (!isLootableContainer(be)) continue;
                    if (mob.isBlockExplored(cursor, now)) continue;
                    double distSq = mobPos.distSqr(cursor);
                    if (distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closest = cursor.immutable();
                    }
                }
            }
        }
        return closest;
    }

    private static boolean isLootableContainer(BlockEntity be) {
        // ChestBlockEntity covers regular + trapped chests (TrappedChestBlockEntity extends ChestBlockEntity).
        // Whitelisting only chest + barrel keeps the mob from raiding hoppers,
        // dispensers, droppers, brewing stands, shulker boxes — items that
        // usually have specific purposes and shouldn't be touched.
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity;
    }
}
