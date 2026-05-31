package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.CraftingLadder;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Idle gathering goal: walk to a nearby harvestable block, mine it with a
 * believable breaking animation, and collect the drops into the mob's backpack.
 *
 * <p>This is the "raw materials" half of the gather-and-craft loop (the crafting
 * half is {@link CraftItemsGoal}). It ports vanilla's
 * {@link net.minecraft.world.entity.ai.goal.MoveToBlockGoal} /
 * {@link net.minecraft.world.entity.ai.goal.RemoveBlockGoal} pattern but, unlike
 * those, harvests <em>into the {@code InventoryCarrier} backpack</em> and targets
 * a whitelist of block types rather than a single one.</p>
 *
 * <p><b>Whitelist:</b> logs ({@link BlockTags#LOGS}), stone ({@link Blocks#STONE}
 * → cobblestone), and coal / iron ores ({@link BlockTags#COAL_ORES},
 * {@link BlockTags#IRON_ORES}). Drops are computed tool-aware, so the mob only
 * targets a block it can currently harvest with a tool it owns — logs bare-handed
 * from the start, stone/ore only once it has crafted (and can equip) a correct
 * pickaxe. That tool-gating is what drives the natural progression.</p>
 *
 * <p><b>Line of sight:</b> the mob only mines blocks it can actually see — never
 * tunnelling to a hidden block. The scan skips fully-buried blocks (no exposed
 * face) cheaply, then raycasts ({@link Level#clip}) from the mob's eyes to the
 * block and accepts it only if the ray reaches that block. LOS is re-checked on
 * arrival and during the break, so a block that becomes occluded is abandoned.</p>
 *
 * <p><b>Phase machine:</b> SCAN (in {@link #canUse}) → PATHING → BREAKING (which
 * grabs the right tool, then advances {@code destroyBlockProgress} over a
 * hardness-scaled tick budget) → on completion, COLLECT drops + break FX → mark
 * explored + cooldown.</p>
 *
 * <p>Honours the {@code mobGriefing} gamerule and is preempted by combat (@2) and
 * raiding (@3). The mob's transient {@code breakingBlock} flag is held while a
 * break is in progress so {@link CraftItemsGoal} (@5) won't interrupt it.</p>
 */
public final class MineBlocksGoal extends Goal {

    private static final int PATH_TIMEOUT_TICKS = 100;     // 5s to reach the block
    private static final double REACH_DISTANCE_SQR = 6.25;  // 2.5 blocks
    private static final int SWING_INTERVAL_TICKS = 8;      // arm-swing cadence while mining
    private static final int POST_VISIT_COOLDOWN = 20;      // 1s after finishing before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 40;      // 2s between scans when nothing found
    private static final int MAX_LOS_CHECKS = 24;           // cap raycasts per scan (bounds cost)

    /** Ticks-per-hardness-unit; tunes how long a break visibly takes. */
    private static final double HARDNESS_TICKS_PER_UNIT = 20.0;
    private static final int MIN_BREAK_TICKS = 20;          // 1s floor
    private static final int MAX_BREAK_TICKS = 120;         // 6s ceiling

    private enum Phase { IDLE, PATHING, BREAKING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final int scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private BlockPos targetPos;

    private int breakTicksTotal = 0;
    private int breakTicksElapsed = 0;
    private int lastProgressStage = -1;
    private int lastSwingTick = 0;

    public MineBlocksGoal(PlayerMobEntity mob, double moveSpeed, int scanRadius) {
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
        // Gather only while there's a crafting objective — a fully-kitted mob idles.
        if (!CraftingLadder.hasToolObjective(mob.getInventory(), mob.equippedToolItems())) return false;

        BlockPos found = findClosestMineable();
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
                && isMineableTarget(mob.level().getBlockState(targetPos));
    }

    @Override
    public void start() {
        phase = Phase.PATHING;
        phaseTicks = 0;
        breakTicksElapsed = 0;
        breakTicksTotal = 0;
        lastProgressStage = -1;
        mob.getNavigation().moveTo(
            targetPos.getX() + 0.5,
            targetPos.getY(),
            targetPos.getZ() + 0.5,
            moveSpeed);
    }

    @Override
    public void stop() {
        clearBreakProgress();
        mob.setBreakingBlock(false);
        mob.getNavigation().stop();
        if (targetPos != null) {
            mob.markBlockExplored(targetPos);
            targetPos = null;
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        breakTicksElapsed = 0;
        breakTicksTotal = 0;
        lastProgressStage = -1;
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
            case BREAKING -> tickBreaking();
            default -> { /* IDLE */ }
        }
    }

    private void tickPathing() {
        if (distanceToTargetSqr() < REACH_DISTANCE_SQR) {
            mob.getNavigation().stop();
            lookAtTarget();

            // Don't mine what we can't see (e.g. a wall went up during approach).
            if (!hasLineOfSight(targetPos)) {
                stop();
                return;
            }

            BlockState state = mob.level().getBlockState(targetPos);
            // Swap to the most appropriate tool (axe for wood, pickaxe for stone/ore);
            // a real swap triggers a brief pause before the break (see tickBreaking).
            mob.equipBestToolFor(state);

            float hardness = state.getDestroySpeed(mob.level(), targetPos);
            if (hardness < 0.0f) { // unbreakable somehow — abandon
                stop();
                return;
            }
            breakTicksTotal = (int) Math.max(MIN_BREAK_TICKS,
                Math.min(MAX_BREAK_TICKS, Math.round(hardness * HARDNESS_TICKS_PER_UNIT)));
            breakTicksElapsed = 0;
            lastProgressStage = -1;
            lastSwingTick = mob.tickCount;
            mob.setBreakingBlock(true);
            phase = Phase.BREAKING;
            phaseTicks = 0;
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop(); // unreachable — mark explored so we don't re-pick it immediately
        }
    }

    private void tickBreaking() {
        Level level = mob.level();
        BlockState state = level.getBlockState(targetPos);
        if (!isMineableTarget(state) || !hasLineOfSight(targetPos)) {
            // Block changed/removed, or got occluded mid-break — abandon.
            stop();
            return;
        }
        lookAtTarget();

        // Brief "reach for the tool" pause after a swap before the break advances.
        if (!mob.isToolReady()) return;

        if (mob.tickCount - lastSwingTick >= SWING_INTERVAL_TICKS) {
            mob.swing(InteractionHand.MAIN_HAND);
            lastSwingTick = mob.tickCount;
        }

        breakTicksElapsed++;
        int stage = (int) Math.floor((breakTicksElapsed / (double) breakTicksTotal) * 10.0);
        if (stage > 9) stage = 9;
        if (stage != lastProgressStage) {
            level.destroyBlockProgress(mob.getId(), targetPos, stage);
            lastProgressStage = stage;
        }

        if (breakTicksElapsed >= breakTicksTotal) {
            completeBreak(state);
        }
    }

    /** Harvest the block into the backpack, play the break FX, then reset. */
    private void completeBreak(BlockState state) {
        Level level = mob.level();
        if (level instanceof ServerLevel server) {
            List<ItemStack> drops = Block.getDrops(
                state, server, targetPos, level.getBlockEntity(targetPos),
                mob, mob.getMainHandItem());
            level.destroyBlock(targetPos, /* dropBlock */ false, mob);
            level.levelEvent(2001, targetPos, Block.getId(state)); // break particles + sound
            for (ItemStack drop : drops) {
                ItemStack leftover = mob.stashInInventory(drop);
                if (!leftover.isEmpty()) mob.spawnAtLocation(leftover);
            }
        } else {
            level.destroyBlock(targetPos, false, mob);
        }
        stop(); // clears progress, breaking flag, marks explored, sets cooldown
    }

    private void clearBreakProgress() {
        if (lastProgressStage >= 0 && targetPos != null) {
            mob.level().destroyBlockProgress(mob.getId(), targetPos, -1);
        }
    }

    private void lookAtTarget() {
        mob.getLookControl().setLookAt(
            targetPos.getX() + 0.5,
            targetPos.getY() + 0.5,
            targetPos.getZ() + 0.5);
    }

    private double distanceToTargetSqr() {
        double dx = (targetPos.getX() + 0.5) - mob.getX();
        double dy = (targetPos.getY() + 0.5) - mob.getY();
        double dz = (targetPos.getZ() + 0.5) - mob.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Brute-force cube scan around the mob for the closest whitelisted block that
     * (a) isn't on cooldown and (b) the mob can actually harvest with a tool it
     * owns. Mirrors {@link RaidContainersGoal#findClosestContainer}; only runs
     * every {@link #EMPTY_SCAN_COOLDOWN} ticks when there's no current target.
     */
    private BlockPos findClosestMineable() {
        BlockPos mobPos = mob.blockPosition();
        Level level = mob.level();
        long now = mob.tickCount;
        List<BlockPos> candidates = new ArrayList<>();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    cursor.set(mobPos.getX() + dx, mobPos.getY() + dy, mobPos.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!isMineableTarget(state)) continue;
                    if (mob.isBlockExplored(cursor, now)) continue;
                    if (!mob.canHarvestWithOwnedTools(state)) continue;
                    if (state.getDestroySpeed(level, cursor) < 0.0f) continue;
                    if (!hasExposedFace(level, cursor)) continue; // skip fully-buried blocks cheaply
                    candidates.add(cursor.immutable());
                }
            }
        }

        // Nearest first, then take the first one we actually have line of sight to.
        // The exposed-face prefilter keeps this list small; the raycast cap bounds the rest.
        candidates.sort(Comparator.comparingDouble((BlockPos p) -> mobPos.distSqr(p)));
        int checks = 0;
        for (BlockPos pos : candidates) {
            if (checks++ >= MAX_LOS_CHECKS) break;
            if (hasLineOfSight(pos)) return pos;
        }
        return null;
    }

    /**
     * Cheap occlusion prefilter: a block is worth a (more expensive) raycast only
     * if at least one of its 6 faces touches a non-full-cube (air, etc.). Fully
     * buried blocks are skipped without a raycast.
     */
    private boolean hasExposedFace(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.set(pos.getX() + d.getStepX(), pos.getY() + d.getStepY(), pos.getZ() + d.getStepZ());
            if (!level.getBlockState(n).isCollisionShapeFullBlock(level, n)) return true;
        }
        return false;
    }

    /**
     * Raycast from the mob's eyes to the block centre; true only if the ray
     * reaches that exact block (i.e. nothing solid is in the way). This is what
     * stops the mob mining "through" walls.
     */
    private boolean hasLineOfSight(BlockPos pos) {
        Vec3 eye = mob.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = mob.level().clip(
            new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /**
     * The harvest whitelist. Logs (any species), plain stone (drops cobblestone),
     * and coal / iron ores. Deliberately narrow: natural, "gather-able" blocks
     * that feed the crafting ladder — not arbitrary blocks or anything with a
     * block-entity/purpose (chests are the raid goal's job).
     */
    private static boolean isMineableTarget(BlockState state) {
        return state.is(BlockTags.LOGS)
                || state.is(Blocks.STONE)
                || state.is(BlockTags.COAL_ORES)
                || state.is(BlockTags.IRON_ORES);
    }
}
