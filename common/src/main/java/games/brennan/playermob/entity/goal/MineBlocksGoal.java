package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.CraftingLadder;
import games.brennan.playermob.entity.CraftingLadder.Need;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
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
 * The "raw materials" half of the gather-and-craft loop (crafting half is
 * {@link CraftItemsGoal}).
 *
 * <p><b>What it gathers</b> is driven by {@link CraftingLadder#neededRawMaterial}:
 * logs while climbing the wood tier, then stone once the mob owns a pickaxe and
 * has a small wood reserve — so it stops over-gathering one resource. Drops are
 * computed tool-aware and harvested into the {@code InventoryCarrier} backpack.</p>
 *
 * <p><b>Reach &amp; line of sight</b> mimic a player: any block within ~4.5
 * blocks of the mob's <em>eyes</em> (so above head height counts) that it has a
 * clear {@link Level#clip} line of sight to. It never mines through walls.</p>
 *
 * <p><b>Finding stone</b> (when stone is needed but none is reachable+visible):
 * the mob first <b>roams</b> to a few nearby spots to look for exposed stone
 * (cliffs, caves), and only if that fails does it <b>dig straight down</b>
 * through soft terrain (dirt/grass/gravel/sand…) until it hits stone — guarded
 * against fluids, voids, and a depth cap.</p>
 *
 * <p><b>Phase machine:</b> SCAN (in {@link #canUse}) → PATHING → BREAKING; or,
 * when stone can't be seen, ROAMING → (rescan) → DIGGING. Honours
 * {@code mobGriefing}; preempted by combat (@2) and raiding (@3). The transient
 * {@code breakingBlock} flag is held during a break so {@link CraftItemsGoal}
 * (@5) won't interrupt it.</p>
 */
public final class MineBlocksGoal extends Goal {

    private static final int PATH_TIMEOUT_TICKS = 100;       // 5s to reach a target block
    private static final double REACH_DISTANCE_SQR = 20.25;   // 4.5 blocks from the eyes (player-like)
    private static final int SWING_INTERVAL_TICKS = 8;        // arm-swing cadence while mining
    private static final int POST_VISIT_COOLDOWN = 20;        // 1s after finishing before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 40;        // 2s between scans when nothing found
    private static final int MAX_LOS_CHECKS = 32;             // cap raycasts per scan (bounds cost)

    /** Ticks-per-hardness-unit; tunes how long a break visibly takes. */
    private static final double HARDNESS_TICKS_PER_UNIT = 20.0;
    private static final int MIN_BREAK_TICKS = 20;            // 1s floor
    private static final int MAX_BREAK_TICKS = 120;           // 6s ceiling

    // Stone-seeking: roam a few spots before resorting to digging.
    private static final int MAX_ROAM_ATTEMPTS = 4;
    private static final double ROAM_RANGE = 12.0;
    private static final int ROAM_TIMEOUT_TICKS = 120;        // 6s to reach a roam spot
    private static final int MAX_DIG_DEPTH = 16;              // staircase no deeper than this

    private enum Phase { IDLE, PATHING, BREAKING, ROAMING, DIGGING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final int scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private BlockPos targetPos;
    private Need need = Need.LOGS;

    private int breakTicksTotal = 0;
    private int breakTicksElapsed = 0;
    private int lastProgressStage = -1;
    private int lastSwingTick = 0;

    private int roamAttempts = 0;
    private int digDepth = 0;

    /** Set in {@link #canUse}: no visible target but stone is needed → enter the seek flow. */
    private boolean plannedDig = false;
    /** True while the current BREAKING block is part of a dig shaft (continue after it breaks). */
    private boolean phaseFollowUpDig = false;

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

        need = CraftingLadder.neededRawMaterial(mob.getInventory(), mob.equippedToolItems());
        if (need == Need.NONE) return false; // objective complete — idle

        BlockPos found = findClosestMineable(need);
        if (found != null) {
            targetPos = found;
            plannedDig = false;
            return true;
        }
        // Needed stone but couldn't see any → go look for it (roam, then dig).
        if (need == Need.STONE) {
            plannedDig = true;
            return true;
        }
        scanCooldown = EMPTY_SCAN_COOLDOWN;
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.IDLE || !mob.isAlive() || mob.isDeadOrDying() || mob.getTarget() != null) {
            return false;
        }
        // While actively mining a block, that block must still be a valid target.
        if (phase == Phase.PATHING || phase == Phase.BREAKING) {
            return targetPos != null && isNeededTarget(mob.level().getBlockState(targetPos), need);
        }
        return true; // ROAMING / DIGGING manage their own exit
    }

    @Override
    public void start() {
        phaseTicks = 0;
        breakTicksElapsed = 0;
        breakTicksTotal = 0;
        lastProgressStage = -1;
        roamAttempts = 0;
        digDepth = 0;

        if (plannedDig) {
            beginStoneSeek();
        } else {
            phase = Phase.PATHING;
            mob.getNavigation().moveTo(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, moveSpeed);
        }
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
        roamAttempts = 0;
        digDepth = 0;
        plannedDig = false;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        switch (phase) {
            case PATHING -> tickPathing();
            case BREAKING -> tickBreaking();
            case ROAMING -> tickRoaming();
            case DIGGING -> tickDigging();
            default -> { /* IDLE */ }
        }
    }

    // ---- Normal mining ----------------------------------------------------

    private void tickPathing() {
        if (targetPos == null) { stop(); return; }
        if (canReachAndSee(targetPos)) {
            mob.getNavigation().stop();
            beginBreaking(targetPos);
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop(); // unreachable — mark explored so we don't re-pick it immediately
        }
    }

    /** Common transition into BREAKING: face the block, equip the right tool, size the break. */
    private void beginBreaking(BlockPos pos) {
        targetPos = pos;
        lookAt(pos);
        if (!hasLineOfSight(pos)) { stop(); return; }

        BlockState state = mob.level().getBlockState(pos);
        mob.equipBestToolFor(state);

        float hardness = state.getDestroySpeed(mob.level(), pos);
        if (hardness < 0.0f) { stop(); return; } // unbreakable

        breakTicksTotal = (int) Math.max(MIN_BREAK_TICKS,
            Math.min(MAX_BREAK_TICKS, Math.round(hardness * HARDNESS_TICKS_PER_UNIT)));
        breakTicksElapsed = 0;
        lastProgressStage = -1;
        lastSwingTick = mob.tickCount;
        mob.setBreakingBlock(true);
        phase = Phase.BREAKING;
        phaseTicks = 0;
    }

    private void tickBreaking() {
        Level level = mob.level();
        BlockState state = level.getBlockState(targetPos);
        if (state.isAir() || !hasLineOfSight(targetPos)) {
            stop(); // block gone or occluded mid-break
            return;
        }
        lookAt(targetPos);

        if (!mob.isToolReady()) return; // brief "reach for the tool" pause after a swap

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
            harvest(targetPos, state);
            // If we were digging a shaft, keep going from the new floor.
            if (phaseFollowUpDig) {
                phaseFollowUpDig = false;
                continueDigging();
            } else {
                stop();
            }
        }
    }

    /** Harvest a block into the backpack and play the break FX (no auto-pickup item). */
    private void harvest(BlockPos pos, BlockState state) {
        Level level = mob.level();
        if (level instanceof ServerLevel server) {
            List<ItemStack> drops = Block.getDrops(
                state, server, pos, level.getBlockEntity(pos), mob, mob.getMainHandItem());
            level.destroyBlock(pos, /* dropBlock */ false, mob);
            level.levelEvent(2001, pos, Block.getId(state)); // break particles + sound
            for (ItemStack drop : drops) {
                ItemStack leftover = mob.stashInInventory(drop);
                if (!leftover.isEmpty()) mob.spawnAtLocation(leftover);
            }
        } else {
            level.destroyBlock(pos, false, mob);
        }
        clearBreakProgress();
        mob.setBreakingBlock(false);
    }

    // ---- Stone-seeking: roam, then dig -----------------------------------

    private void beginStoneSeek() {
        roamAttempts = 0;
        startRoamMove();
    }

    /** Pick a random nearby land spot and walk to it, then re-scan for exposed stone. */
    private void startRoamMove() {
        Vec3 dest = LandRandomPos.getPos(mob, (int) ROAM_RANGE, 4);
        phase = Phase.ROAMING;
        phaseTicks = 0;
        if (dest != null) {
            mob.getNavigation().moveTo(dest.x, dest.y, dest.z, moveSpeed);
        }
    }

    private void tickRoaming() {
        // Each tick, opportunistically check whether stone is now visible.
        BlockPos found = findClosestMineable(Need.STONE);
        if (found != null) {
            targetPos = found;
            mob.getNavigation().stop();
            phase = Phase.PATHING;
            phaseTicks = 0;
            return;
        }
        boolean arrivedOrStuck = mob.getNavigation().isDone() || phaseTicks > ROAM_TIMEOUT_TICKS;
        if (arrivedOrStuck) {
            roamAttempts++;
            if (roamAttempts >= MAX_ROAM_ATTEMPTS) {
                beginDigging(); // gave up roaming — dig down to find stone
            } else {
                startRoamMove();
            }
        }
    }

    private void beginDigging() {
        digDepth = 0;
        continueDigging();
    }

    /**
     * Choose the next block to dig downward and start breaking it — or finish if
     * stone is reached, the depth cap is hit, or a safety guard trips.
     */
    private void continueDigging() {
        if (digDepth >= MAX_DIG_DEPTH) { stop(); return; }

        Level level = mob.level();
        BlockPos feet = mob.blockPosition();
        BlockPos below = feet.below();
        BlockState belowState = level.getBlockState(below);

        // Reached stone/ore? Mine it normally (drops cobblestone for the ladder).
        if (isNeededTarget(belowState, Need.STONE)) {
            phase = Phase.PATHING;
            phaseTicks = 0;
            targetPos = below;
            return;
        }

        if (!isDiggable(below) || !isDigSafe(below)) {
            stop(); // hit bedrock/fluid/void/unknown — abort the shaft
            return;
        }

        digDepth++;
        phaseFollowUpDig = true;
        beginBreaking(below);
    }

    private void tickDigging() {
        // DIGGING is a transient state; real work happens in continueDigging →
        // BREAKING. If we ever land here with nothing scheduled, recover.
        continueDigging();
    }

    /** Soft terrain the mob is willing to tunnel through on the way to stone. */
    private boolean isDiggable(BlockPos pos) {
        BlockState s = mob.level().getBlockState(pos);
        if (s.isAir()) return true; // can step down into air
        if (mob.level().getBlockState(pos).getDestroySpeed(mob.level(), pos) < 0) return false; // bedrock
        return s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.PODZOL) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.DIRT_PATH)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
            || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(BlockTags.SNOW);
    }

    /**
     * Refuse to dig into danger: no fluid in or adjacent to the target, and the
     * block two down must be solid (no void/cliff plunge).
     */
    private boolean isDigSafe(BlockPos pos) {
        Level level = mob.level();
        if (!level.getBlockState(pos).getFluidState().isEmpty()) return false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.getFluidState(n).isEmpty()) return false; // lava/water touching
        }
        // Two-below must be solid so the mob doesn't open a drop under itself.
        BlockPos twoDown = pos.below();
        BlockState floor = level.getBlockState(twoDown);
        return !floor.isAir() && floor.getFluidState().isEmpty();
    }

    // ---- Shared helpers ---------------------------------------------------

    private void clearBreakProgress() {
        if (lastProgressStage >= 0 && targetPos != null) {
            mob.level().destroyBlockProgress(mob.getId(), targetPos, -1);
            lastProgressStage = -1;
        }
    }

    private void lookAt(BlockPos pos) {
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** True if {@code pos} is within player-like eye reach AND has line of sight. */
    private boolean canReachAndSee(BlockPos pos) {
        return distanceFromEyesSqr(pos) < REACH_DISTANCE_SQR && hasLineOfSight(pos);
    }

    private double distanceFromEyesSqr(BlockPos pos) {
        Vec3 eye = mob.getEyePosition();
        double dx = (pos.getX() + 0.5) - eye.x;
        double dy = (pos.getY() + 0.5) - eye.y;
        double dz = (pos.getZ() + 0.5) - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Brute-force cube scan around the mob for the closest block of the needed
     * material that isn't on cooldown, is harvestable with an owned tool, has an
     * exposed face, and that the mob can see. Only runs from {@link #canUse}.
     */
    private BlockPos findClosestMineable(Need want) {
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
                    if (!isNeededTarget(state, want)) continue;
                    if (mob.isBlockExplored(cursor, now)) continue;
                    if (!mob.canHarvestWithOwnedTools(state)) continue;
                    if (state.getDestroySpeed(level, cursor) < 0.0f) continue;
                    if (!hasExposedFace(level, cursor)) continue;
                    candidates.add(cursor.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble((BlockPos p) -> mobPos.distSqr(p)));
        int checks = 0;
        for (BlockPos pos : candidates) {
            if (checks++ >= MAX_LOS_CHECKS) break;
            if (hasLineOfSight(pos)) return pos;
        }
        return null;
    }

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
     * reaches that exact block (nothing solid in the way). Stops the mob mining
     * through walls.
     */
    private boolean hasLineOfSight(BlockPos pos) {
        Vec3 eye = mob.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = mob.level().clip(
            new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** Whether {@code state} is a block the mob wants for the {@code want} material. */
    private static boolean isNeededTarget(BlockState state, Need want) {
        return switch (want) {
            case LOGS -> state.is(BlockTags.LOGS);
            // Stone phase also collects ores it passes (coal/iron banked as loot).
            case STONE -> state.is(Blocks.STONE)
                    || state.is(BlockTags.COAL_ORES)
                    || state.is(BlockTags.IRON_ORES);
            case NONE -> false;
        };
    }
}
