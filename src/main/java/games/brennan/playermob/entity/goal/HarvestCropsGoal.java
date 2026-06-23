package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.EquipmentEvaluator;
import games.brennan.playermob.entity.ForagePolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.List;

/**
 * Walk to nearby ripe food crops, break them with a swing, and bank the edible
 * drops in the backpack — the low-priority "go farm something to eat" idle drive.
 *
 * <p><b>Phase machine</b> (modeled on {@link RaidContainersGoal}):</p>
 * <ol>
 *   <li><b>Scan</b> — on each {@link #canUse} evaluation, sweep a cube of
 *       {@code scanRadius} around the mob for the nearest {@link ForagePolicy#isRipeFoodCrop}
 *       block not on the recently-explored cooldown.</li>
 *   <li><b>Path</b> — navigate to it; {@link #PATH_TIMEOUT_TICKS} timeout for unreachable.</li>
 *   <li><b>Harvest</b> — swing, hold a brief windup so the break reads as a
 *       deliberate action, then collect the crop's edible drops straight into the
 *       backpack (non-food drops like seeds spill to the ground) and remove the
 *       block. {@code EatFoodGoal} eats the banked food when HP drops.</li>
 * </ol>
 *
 * <p>Gated on {@link PlayerMobEntity#wantsFood()} and the {@code mobGriefing}
 * gamerule (it breaks blocks). Registered at goalSelector priority 6 — below
 * combat (2) and raiding/eating/collecting (3) — so it only farms when there's
 * nothing more urgent to do. Combat (and the hunt target goal, which runs at
 * combat priority) preempt it via the {@code mob.getTarget() != null} check.
 * Reuses the entity's existing recently-explored cooldown maps, so it adds no
 * new save state.</p>
 */
public final class HarvestCropsGoal extends Goal implements DescribableGoal {

    private static final int HARVEST_WINDUP_TICKS = 10;   // 0.5s swing before the break lands
    private static final int PATH_TIMEOUT_TICKS = 100;     // 5s to reach the crop
    private static final int POST_VISIT_COOLDOWN = 20;     // 1s after harvesting before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 40;     // 2s between scans when nothing found
    private static final double REACH_DISTANCE_SQR = 4.0;  // 2 blocks

    private enum Phase { IDLE, PATHING, HARVESTING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final int scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private BlockPos targetPos;

    public HarvestCropsGoal(PlayerMobEntity mob, double moveSpeed, int scanRadius) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        this.scanRadius = scanRadius;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Harvesting crops";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case PATHING -> "approaching";
            case HARVESTING -> "harvesting";
            default -> null;
        };
    }

    @Override
    public boolean canUse() {
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        if (mob.getTarget() != null) return false;       // combat / hunting preempts
        if (!mob.wantsFood()) return false;
        if (!GameRuleCompat.mobGriefing(mob.level())) return false;

        BlockPos found = findClosestRipeCrop();
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
                && mob.isAlive()
                && !mob.isDeadOrDying()
                && mob.getTarget() == null
                && mob.wantsFood()
                && TrainConfinement.allowsTarget(mob, targetPos);
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
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (targetPos == null) return;
        phaseTicks++;

        switch (phase) {
            case PATHING -> tickPathing();
            case HARVESTING -> tickHarvesting();
            default -> { /* IDLE */ }
        }
    }

    private void tickPathing() {
        double dx = (targetPos.getX() + 0.5) - mob.getX();
        double dy = (targetPos.getY() + 0.5) - mob.getY();
        double dz = (targetPos.getZ() + 0.5) - mob.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < REACH_DISTANCE_SQR) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(
                targetPos.getX() + 0.5,
                targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5);
            phase = Phase.HARVESTING;
            phaseTicks = 0;
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop();
        }
    }

    /** Swing on the first windup tick, break + collect once the windup elapses. */
    private void tickHarvesting() {
        BlockState state = mob.level().getBlockState(targetPos);
        if (!ForagePolicy.isRipeFoodCrop(state)) {
            // Crop changed/destroyed since we targeted it (grew? trampled? raided?).
            stop();
            return;
        }
        if (phaseTicks == 1) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        if (phaseTicks >= HARVEST_WINDUP_TICKS) {
            harvest(state);
            stop();
        }
    }

    /**
     * Collect the crop's edible drops straight into the backpack (spilling
     * non-food drops like seeds to the ground), then remove the block.
     * {@code destroyBlock} emits the break particles + sound itself, so we don't
     * double them with a separate level event.
     */
    private void harvest(BlockState state) {
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;
        List<ItemStack> drops = Block.getDrops(
            state, serverLevel, targetPos, /* blockEntity */ null, mob, mob.getMainHandItem());
        for (ItemStack drop : drops) {
            if (ForagePolicy.isEdible(drop)) {
                ItemStack leftover = EquipmentEvaluator.addToContainer(mob.getInventory(), drop);
                if (!leftover.isEmpty()) mob.dropAtLocation(leftover);
            } else {
                mob.dropAtLocation(drop);
            }
        }
        serverLevel.destroyBlock(targetPos, /* dropBlock */ false, mob);
    }

    /**
     * Brute-force scan of {@code (2*scanRadius+1)³} positions for the nearest
     * ripe food crop not on cooldown. Mirrors {@link RaidContainersGoal#findClosestContainer}
     * but reads block <em>state</em> (palette lookup) instead of block entities;
     * only invoked every {@link #EMPTY_SCAN_COOLDOWN} ticks when idle.
     */
    private BlockPos findClosestRipeCrop() {
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
                    if (!ForagePolicy.isRipeFoodCrop(level.getBlockState(cursor))) continue;
                    if (mob.isBlockExplored(cursor, now)) continue;
                    if (!TrainConfinement.allowsTarget(mob, cursor)) continue;
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
}
