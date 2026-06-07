package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.compat.TrainEnvironment;
import games.brennan.playermob.entity.BlockSourcePolicy;
import games.brennan.playermob.entity.EquipmentEvaluator;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * High-priority recovery goal: when a PlayerMob falls off a Dungeon Train
 * carriage onto the track bed (or just into a gap), it pathfinds back toward the
 * nearest carriage and <b>places blocks to bridge</b> the ~2-block step back up
 * — the mod's first block-placement behaviour. When it has no placeable block it
 * gathers a nearby one (and crafts logs → planks); see {@link BlockSourcePolicy}.
 *
 * <p><b>Best-effort.</b> The train keeps moving (+X at ~0.1 block/tick on the
 * 2 m/s default) and never waits — there is no slowdown, tether, or teleport
 * rescue. If the mob can't climb back before the carriage slides out of reach,
 * or it's stranded somewhere unreachable, recovery is abandoned and the mob is
 * lost. That trade-off was chosen deliberately (issue #35).</p>
 *
 * <p><b>Phase machine</b> (re-resolves the moving carriage box every tick):
 * APPROACH → BRIDGE → GATHER → CRAFT. Registered at goalSelector priority 1 so
 * it preempts combat (@2): falling off is existential. It still yields to
 * {@link net.minecraft.world.entity.ai.goal.FloatGoal} (@0) so a mob bridging
 * over water swims instead of drowning. All world mutation (place + gather) is
 * gated on {@code mobGriefing}, matching the raid/harvest goals.</p>
 *
 * <p>Fires only when the mob actually <em>fell off</em> — it was on a train
 * within {@link PlayerMobEntity#RECOVERY_WINDOW_TICKS} and isn't now — so a
 * ground PlayerMob merely walking past the tracks won't try to board a passing
 * train. That recency check is also the cheap short-circuit that keeps the
 * carriage scan off the hot path: it's never on a train (so the gate never
 * passes) on Fabric/Forge and on NeoForge without Dungeon Train, so the goal
 * never fires there.</p>
 */
public final class TrainRecoveryGoal extends Goal {

    /** How far to look for a carriage to re-board. A fall keeps the mob near its train. */
    private static final double SCAN_RADIUS = 28.0;
    /** Once the nearest carriage is farther than this (it outran us), give up. */
    private static final double ABANDON_DISTANCE = 24.0;
    /** Hard ceiling on a whole recovery attempt (10s) — best-effort, never loop forever. */
    private static final int GLOBAL_ABANDON_TICKS = 200;
    /** Re-issue navigation toward the moving carriage at this cadence. */
    private static final int PATH_REISSUE_TICKS = 5;
    /** Horizontal distance at which APPROACH hands off to BRIDGE. */
    private static final double BRIDGE_TRIGGER_DIST = 2.5;
    /** Max bridge blocks placed per recovery — bounded so a failing mob doesn't pave the tracks. */
    private static final int MAX_PLACEMENTS = 6;
    /** Ticks between bridge placements, leaving time to climb the previous step. */
    private static final int PLACE_INTERVAL_TICKS = 10;
    /** Gather scan cube half-extent and reach. */
    private static final int GATHER_SCAN_RADIUS = 5;
    private static final double GATHER_REACH_SQR = 9.0;     // 3 blocks
    private static final int GATHER_BREAK_TICKS = 12;        // brief, believable break
    private static final int GATHER_PATH_TIMEOUT = 80;       // 4s to reach a gather block
    /** Rescan delay after the goal stops (success or abandon). */
    private static final int POST_COOLDOWN_TICKS = 40;

    private enum Phase { IDLE, APPROACH, BRIDGE, GATHER, CRAFT }

    private final PlayerMobEntity mob;
    private final double moveSpeed;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int totalTicks = 0;
    private int cooldown = 0;
    private int placementsUsed = 0;

    /** Re-resolved every evaluation/tick — the carriage moves. */
    private TrainEnvironment.ReboardTarget target;
    private BlockPos gatherTargetPos;
    private int gatherBreakTicks = 0;

    public TrainRecoveryGoal(PlayerMobEntity mob, double moveSpeed) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (!(mob.level() instanceof ServerLevel)) return false;
        // Cheap gate first: only a mob that was recently ON a train is "recovering"
        // (and this is always false without a train mod), so it short-circuits the
        // carriage scan below for every other mob.
        if (mob.ticksSinceOnTrain() > PlayerMobEntity.RECOVERY_WINDOW_TICKS) return false;
        // World mutation gate (placement + gather); matches the raid/harvest goals.
        if (!mobGriefingOn()) return false;
        // Already aboard a carriage → nothing to recover.
        if (TrainConfinement.isConfined(mob)) return false;
        // Don't start mid-air / mid-fall; wait until we have footing on something.
        if (!mob.onGround()) return false;
        TrainEnvironment.ReboardTarget t = TrainConfinement.nearestCarriage(mob, SCAN_RADIUS);
        if (t == null) {
            return false;
        }
        this.target = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.IDLE || !mob.isAlive() || mob.isDeadOrDying()) return false;
        if (!mobGriefingOn()) return false;
        if (TrainConfinement.isConfined(mob)) return false;        // back aboard → success
        if (totalTicks >= GLOBAL_ABANDON_TICKS) return false;       // best-effort time cap
        TrainEnvironment.ReboardTarget t = TrainConfinement.nearestCarriage(mob, SCAN_RADIUS);
        if (t == null) return false;
        this.target = t;
        return horizontalDistToBox(t.worldBox()) <= ABANDON_DISTANCE; // not yet outrun
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        phaseTicks = 0;
        totalTicks = 0;
        placementsUsed = 0;
        gatherTargetPos = null;
        moveTowardCarriage();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (gatherTargetPos != null) {
            mob.markBlockExplored(gatherTargetPos);
            gatherTargetPos = null;
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        cooldown = POST_COOLDOWN_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) return;
        totalTicks++;
        phaseTicks++;
        switch (phase) {
            case APPROACH -> tickApproach();
            case BRIDGE -> tickBridge();
            case GATHER -> tickGather();
            case CRAFT -> tickCraft();
            default -> { /* IDLE */ }
        }
    }

    // ---- APPROACH ---------------------------------------------------------

    private void tickApproach() {
        AABB box = target.worldBox();
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            moveTowardCarriage();
        }
        // Close horizontally but still below the carriage floor → start bridging.
        if (horizontalDistToBox(box) <= BRIDGE_TRIGGER_DIST && mob.getY() < box.minY - 0.5) {
            phase = Phase.BRIDGE;
            phaseTicks = 0;
        }
        // Walking up onto the carriage by other means flips isConfined → canContinueToUse stops us.
    }

    // ---- BRIDGE -----------------------------------------------------------

    private void tickBridge() {
        AABB box = target.worldBox();
        // Keep heading at the carriage so the mob climbs each placed step.
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            moveTowardCarriage();
        }

        int slot = BlockSourcePolicy.firstPlaceableSlot(mob.getInventory());
        if (slot < 0) {                       // out of blocks → go gather
            phase = Phase.GATHER;
            phaseTicks = 0;
            gatherTargetPos = null;
            return;
        }
        // Prefer turning logs into planks (4× the bridge blocks) before placing.
        if (BlockSourcePolicy.isCraftableLog(mob.getInventory().getItem(slot))) {
            phase = Phase.CRAFT;
            phaseTicks = 0;
            return;
        }

        BlockPos place = BlockSourcePolicy.nextBridgePos(mob.blockPosition(), box);
        if (place == null) {
            // Level with the carriage's base layer — jump the final block onto it.
            mob.getJumpControl().jump();
            return;
        }
        // Pace placements so the mob has time to climb the previous one.
        if (phaseTicks % PLACE_INTERVAL_TICKS != 0) return;
        if (placementsUsed >= MAX_PLACEMENTS) {     // bridge isn't working → abandon
            stop();
            return;
        }
        mob.getLookControl().setLookAt(place.getX() + 0.5, place.getY() + 0.5, place.getZ() + 0.5);
        if (tryPlaceBridgeBlock(place, slot)) {
            placementsUsed++;
        }
    }

    /**
     * Place one block from backpack slot {@code slot} at {@code pos}, if the spot
     * is replaceable, not part of the train (carriage box or protected track),
     * and unoccupied. Re-checks {@code mobGriefing} immediately before mutating.
     */
    private boolean tryPlaceBridgeBlock(BlockPos pos, int slot) {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (!mobGriefingOn()) return false;
        ItemStack stack = mob.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;

        BlockState existing = level.getBlockState(pos);
        if (!existing.canBeReplaced()) return false;
        if (BlockSourcePolicy.isProtectedTrackBlock(existing)) return false;
        AABB cell = new AABB(pos);
        if (target != null && target.worldBox().intersects(cell)) return false; // never overwrite the carriage
        if (!level.getEntities(mob, cell).isEmpty()) return false;              // don't seal an entity in

        BlockState state = blockItem.getBlock().defaultBlockState();
        level.setBlock(pos, state, Block.UPDATE_ALL);
        stack.shrink(1);
        if (stack.isEmpty()) {
            mob.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        mob.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, pos, state.getSoundType().getPlaceSound(),
            SoundSource.BLOCKS, 1.0F, 0.9F + mob.getRandom().nextFloat() * 0.1F);
        return true;
    }

    // ---- GATHER -----------------------------------------------------------

    private void tickGather() {
        if (gatherTargetPos == null) {
            gatherTargetPos = findGatherTarget();
            if (gatherTargetPos == null) {       // nothing reachable to harvest → give up
                stop();
                return;
            }
            gatherBreakTicks = 0;
            mob.getNavigation().moveTo(
                gatherTargetPos.getX() + 0.5, gatherTargetPos.getY(), gatherTargetPos.getZ() + 0.5, moveSpeed);
            return;
        }
        // Block changed/destroyed since we picked it.
        BlockState state = mob.level().getBlockState(gatherTargetPos);
        if (!BlockSourcePolicy.isHandBreakable(state) && !BlockSourcePolicy.isStoneClass(state)) {
            mob.markBlockExplored(gatherTargetPos);
            gatherTargetPos = null;
            return;
        }
        double distSq = mob.distanceToSqr(
            gatherTargetPos.getX() + 0.5, gatherTargetPos.getY() + 0.5, gatherTargetPos.getZ() + 0.5);
        if (distSq > GATHER_REACH_SQR) {
            if (phaseTicks > GATHER_PATH_TIMEOUT) {   // can't reach it → abandon this block
                mob.markBlockExplored(gatherTargetPos);
                gatherTargetPos = null;
                phaseTicks = 0;
            }
            return;
        }
        // In reach: face it and break with a short windup. Gather targets are
        // hand-breakable (dirt/sand/gravel/logs), so no tool is needed — we just
        // swing, mirroring HarvestCropsGoal.
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(
            gatherTargetPos.getX() + 0.5, gatherTargetPos.getY() + 0.5, gatherTargetPos.getZ() + 0.5);
        if (gatherBreakTicks % 4 == 0) mob.swing(InteractionHand.MAIN_HAND);
        gatherBreakTicks++;
        if (gatherBreakTicks >= GATHER_BREAK_TICKS) {
            harvestGatherTarget(state);
            phase = Phase.BRIDGE;                       // re-evaluate with the new material
            phaseTicks = 0;
        }
    }

    /** Break the gather block into the backpack and play the break FX (mobGriefing re-checked). */
    private void harvestGatherTarget(BlockState state) {
        Level level = mob.level();
        if (level instanceof ServerLevel server && mobGriefingOn() && !state.isAir()) {
            List<ItemStack> drops = Block.getDrops(
                state, server, gatherTargetPos, level.getBlockEntity(gatherTargetPos), mob, mob.getMainHandItem());
            level.destroyBlock(gatherTargetPos, /* dropBlock */ false, mob);
            level.levelEvent(2001, gatherTargetPos, Block.getId(state)); // break particles + sound
            for (ItemStack drop : drops) {
                ItemStack leftover = EquipmentEvaluator.addToContainer(mob.getInventory(), drop);
                if (!leftover.isEmpty()) mob.spawnAtLocation(leftover);
            }
        }
        mob.markBlockExplored(gatherTargetPos);
        gatherTargetPos = null;
    }

    /** Nearest cheap, tool-harvestable, non-track block worth breaking for a bridge block. */
    private BlockPos findGatherTarget() {
        BlockPos origin = mob.blockPosition();
        BlockPos feetBelow = origin.below();
        Level level = mob.level();
        long now = mob.tickCount;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -GATHER_SCAN_RADIUS; dx <= GATHER_SCAN_RADIUS; dx++) {
            for (int dy = -GATHER_SCAN_RADIUS; dy <= GATHER_SCAN_RADIUS; dy++) {
                for (int dz = -GATHER_SCAN_RADIUS; dz <= GATHER_SCAN_RADIUS; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (cursor.equals(feetBelow)) continue;        // never mine our own footing
                    BlockState state = level.getBlockState(cursor);
                    // Hand-breakable only (dirt/sand/gravel/logs) — needs no tool,
                    // keeping recovery self-contained from the gather/craft tool system.
                    if (!BlockSourcePolicy.isHandBreakable(state)) continue;
                    if (BlockSourcePolicy.isProtectedTrackBlock(state)) continue;
                    if (mob.isBlockExplored(cursor, now)) continue;
                    if (!hasExposedFace(level, cursor)) continue;
                    double d = origin.distSqr(cursor);
                    if (d < bestSq) {
                        bestSq = d;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean hasExposedFace(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            n.set(pos.getX() + d.getStepX(), pos.getY() + d.getStepY(), pos.getZ() + d.getStepZ());
            if (!level.getBlockState(n).isCollisionShapeFullBlock(level, n)) return true;
        }
        return false;
    }

    // ---- CRAFT (logs → planks) -------------------------------------------

    private void tickCraft() {
        int logSlot = firstCraftableLogSlot();
        if (logSlot < 0) {                  // nothing to craft → bridge with whatever we have
            phase = Phase.BRIDGE;
            phaseTicks = 0;
            return;
        }
        ItemStack log = mob.getInventory().getItem(logSlot);
        ItemStack planks = BlockSourcePolicy.planksFromLog(log);
        log.shrink(1);
        if (log.isEmpty()) {
            mob.getInventory().setItem(logSlot, ItemStack.EMPTY);
        }
        ItemStack leftover = EquipmentEvaluator.addToContainer(mob.getInventory(), planks);
        if (!leftover.isEmpty()) mob.spawnAtLocation(leftover);
        mob.swing(InteractionHand.MAIN_HAND);
        phase = Phase.BRIDGE;
        phaseTicks = 0;
    }

    private int firstCraftableLogSlot() {
        for (int i = 0; i < mob.getInventory().getContainerSize(); i++) {
            if (BlockSourcePolicy.isCraftableLog(mob.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    // ---- Shared helpers ---------------------------------------------------

    private void moveTowardCarriage() {
        Vec3 p = approachPoint(target.worldBox());
        mob.getNavigation().moveTo(p.x, p.y, p.z, moveSpeed);
    }

    /** The point on the carriage's near edge, at its floor level, to head for. */
    private Vec3 approachPoint(AABB box) {
        double x = Mth.clamp(mob.getX(), box.minX + 0.5, box.maxX - 0.5);
        double z = Mth.clamp(mob.getZ(), box.minZ + 0.5, box.maxZ - 0.5);
        return new Vec3(x, box.minY, z);
    }

    /** Horizontal distance from the mob to the nearest point of {@code box}. */
    private double horizontalDistToBox(AABB box) {
        double dx = Math.max(Math.max(box.minX - mob.getX(), 0.0), mob.getX() - box.maxX);
        double dz = Math.max(Math.max(box.minZ - mob.getZ(), 0.0), mob.getZ() - box.maxZ);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean mobGriefingOn() {
        return mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }
}
