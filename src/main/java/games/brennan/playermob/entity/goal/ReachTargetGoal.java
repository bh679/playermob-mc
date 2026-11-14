package games.brennan.playermob.entity.goal;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.BlockSourcePolicy;
import games.brennan.playermob.entity.EquipmentEvaluator;
import games.brennan.playermob.entity.MiningMath;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.ReachPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import java.util.EnumSet;

/**
 * Mid-fight, when the target is out of reach — up a pillar, on a roof, sealed in a hole — mine and
 * build a way to it the way a player would: pillar up, lay a staircase, tunnel through walls, dig
 * down. {@link ReachPolicy} picks each step; this goal carries it out (tool swap, a hardness-paced
 * break with the cracking overlay, placing blocks from the backpack, jumping).
 *
 * <p><b>When.</b> Only once the fight has <em>stalled</em>: the mob has a live target, isn't in melee
 * reach, and hasn't closed the distance for {@link #STALL_TICKS} — and, off a train, the pathfinder
 * confirms there is no way there. An echo that can still shoot (a ranged weapon with ammo and line
 * of sight) keeps shooting instead. Gated on {@code combatReach} and {@code mobGriefing}.</p>
 *
 * <p><b>Priority 1</b>, above the priority-2 attack goal, for the same reason
 * {@link FlintAndSteelIgniteGoal} sits there: the trigger fires mid-fight while the attack goal
 * already holds MOVE, and only a strictly higher-priority goal can preempt a running one. Its
 * {@code canUse} is narrow (stalled + unreachable), so ordinary fights never see it.</p>
 *
 * <p><b>Block frame.</b> All block reads and writes go through {@link TrainConfinement#toBlockFrame}:
 * on a Dungeon-Train carriage the blocks live in the carriage's sub-level, not at the mob's world
 * position (the same reason the train dig reflex is frame-aware). On a train it never digs down —
 * the floor holds the carriage — and only chases targets on its own carriage group.</p>
 */
public final class ReachTargetGoal extends Goal implements DescribableGoal {

    /** Ticks without getting closer (while out of reach) before the mob starts mining/building. 2 s. */
    private static final int STALL_TICKS = 40;
    /** Getting this much closer counts as progress for the stall timer. */
    private static final double PROGRESS = 0.5;
    /** Only targets this close (blocks) are worth digging toward. */
    private static final double MAX_RANGE = 16.0;
    /** Hard cap on one reach attempt (30 s); the stall timer must fire again before the next. */
    private static final int MAX_TICKS = 600;
    /** Give up after this many consecutive ticks with no possible step (no blocks / unbreakable). */
    private static final int STUCK_GIVE_UP = 40;
    /** Re-ask the pathfinder (off a train) this often while running: a path opening up ends the dig. */
    private static final int PATH_RECHECK_TICKS = 20;
    /** Added to the mob's Y before flooring to find its leg cell; covers frame jitter (see {@link #tick}). */
    private static final double FEET_PROBE = 0.2;
    /** Swing the arm this often while mining, so the break reads as deliberate. */
    private static final int SWING_INTERVAL = 5;
    /** Ticks between placing blocks, so building reads as deliberate rather than instant. */
    private static final int PLACE_INTERVAL = 6;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final PlayerMobEntity mob;
    private final double speed;

    // Stall tracking (fed from canUse while the goal is idle).
    private LivingEntity watched;
    private double bestDist = Double.MAX_VALUE;
    private int lastProgressTick;

    // Running state.
    private int runTicks;
    private int stuckTicks;
    private int placeCooldown;
    private ReachPolicy.Kind phase = ReachPolicy.Kind.WALK;
    private BlockPos breakPos;
    private int breakTicks;
    private int breakTotal;
    private BlockPos pillarColumn;

    public ReachTargetGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public String objective() {
        return "Reaching";
    }

    @Override
    public String subObjective() {
        String what = switch (phase) {
            case MINE -> "mining";
            case PILLAR -> "pillaring";
            case PLACE, STEP_UP -> "building";
            default -> "moving";
        };
        LivingEntity target = mob.getTarget();
        return target != null ? what + " → " + target.getName().getString() : what;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        if (!enabled() || target == null || !target.isAlive() || mob.isRecovering()
                || !TrainConfinement.sharesBlockFrame(mob, target)) {
            resetStall(null);
            return false;
        }
        if (target != watched) {
            resetStall(target);
        }
        Vec3 me = frame(mob.position());
        Vec3 them = frame(target.position());
        double dist = me.distanceTo(them);
        if (dist > MAX_RANGE || ReachPolicy.inReach(them.x - me.x, them.y - me.y, them.z - me.z)) {
            resetStall(target);
            return false;
        }
        if (dist < bestDist - PROGRESS) {
            bestDist = dist;
            lastProgressTick = mob.tickCount;
            return false;
        }
        if (mob.tickCount - lastProgressTick < STALL_TICKS || !mob.onGround()) {
            return false;
        }
        boolean shoot = canStillShoot(target);
        boolean path = !shoot && pathReaches(target);
        trace("stalled dist={} shoot={} pathReaches={}", String.format("%.1f", dist), shoot, path);
        if (shoot || path) {
            resetStall(target);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        if (!enabled() || target == null || !target.isAlive() || target != watched
                || runTicks >= MAX_TICKS || stuckTicks >= STUCK_GIVE_UP
                || !TrainConfinement.sharesBlockFrame(mob, target)) {
            return false;
        }
        if (runTicks > 0 && runTicks % PATH_RECHECK_TICKS == 0 && (canStillShoot(target) || pathReaches(target))) {
            return false;
        }
        return phase != ReachPolicy.Kind.IN_REACH;
    }

    @Override
    public void start() {
        runTicks = 0;
        stuckTicks = 0;
        placeCooldown = 0;
        phase = ReachPolicy.Kind.WALK;
        pillarColumn = null;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        clearBreak();
        pillarColumn = null;
        phase = ReachPolicy.Kind.WALK;
        resetStall(mob.getTarget());
        mob.equipBestMeleeInHand();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        runTicks++;
        if (placeCooldown > 0) placeCooldown--;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Mid-pillar: keep jumping and filling the column under us until it's done.
        if (pillarColumn != null) {
            tickPillar(level);
            return;
        }

        // Mid-air (dropping into the hole it just dug, or off a step): wait to land before re-planning.
        if (!mob.onGround() && !mob.isInWater()) {
            return;
        }

        Vec3 me = frame(mob.position());
        Vec3 them = frame(target.position());
        ReachPolicy.Cells cells = cells(level);
        // Leg cell: the cell just above the feet, or the one over it when the feet are inside a partial
        // floor block (a slab deck). Chosen by collision rather than by rounding the Y, because on a moving
        // carriage the frame conversion jitters the Y by more than a slab's height tolerance, which flipped
        // the cell between two rows tick to tick (placing a tread, then mining it as a wall).
        BlockPos feet = BlockPos.containing(me.x, me.y + FEET_PROBE, me.z);
        if (cells.solid(feet)) {
            feet = feet.above();
        }
        ReachPolicy.Step step = ReachPolicy.next(feet, me.x, me.y, me.z, them.x, them.y, them.z,
            cells, BlockSourcePolicy.hasPlaceableBlock(mob.getInventory()),
            /* canDigDown */ !TrainConfinement.isConfined(mob));
        if (step.kind() != phase || (step.cell() != null && !step.cell().equals(breakPos) && step.kind() == ReachPolicy.Kind.MINE)) {
            trace("step {} cell={} feet={}", step.kind(), step.cell(), feet);
        }
        phase = step.kind();
        if (step.kind() != ReachPolicy.Kind.MINE) {
            clearBreak();
        }
        stuckTicks = step.kind() == ReachPolicy.Kind.STUCK ? stuckTicks + 1 : 0;

        switch (step.kind()) {
            case MINE -> tickMine(level, step.cell());
            case PILLAR -> {
                if (mob.onGround()) {
                    pillarColumn = feet;
                    mob.getNavigation().stop();
                    mob.getJumpControl().jump();
                }
            }
            case PLACE -> {
                holdStill();
                if (placeCooldown == 0 && placeBlock(level, step.cell())) {
                    placeCooldown = PLACE_INTERVAL;
                }
            }
            case STEP_UP -> {
                moveToward(step.cell().above());
                if (mob.onGround()) mob.getJumpControl().jump();
            }
            case WALK -> moveToward(step.cell());
            case STUCK, IN_REACH -> holdStill();
        }
    }

    // ---- Steps -----------------------------------------------------------

    /** Jump-stack: at the apex of the jump, fill the cell we just left so we land on it. */
    private void tickPillar(ServerLevel level) {
        holdStill();
        Vec3 me = frame(mob.position());
        double dx = me.x - (pillarColumn.getX() + 0.5);
        double dz = me.z - (pillarColumn.getZ() + 0.5);
        if (dx * dx + dz * dz > 0.6 * 0.6 || (mob.onGround() && me.y < pillarColumn.getY() + 0.5)) {
            pillarColumn = null;                   // shoved off the column, or landed without placing
            return;
        }
        if (me.y >= pillarColumn.getY() + 1.0 && mob.getDeltaMovement().y <= 0.0) {
            placeBlock(level, pillarColumn);
            pillarColumn = null;                   // placed or not, the next tick re-plans
        }
    }

    /** Break {@code pos} over a realistic, tool-scaled time, then put its drops in the backpack. */
    private void tickMine(ServerLevel level, BlockPos pos) {
        holdStill();
        lookAtCell(pos);
        BlockState state = level.getBlockState(pos);
        if (!pos.equals(breakPos)) {
            clearBreak();
            breakPos = pos;
            mob.equipBetterToolFor(state);
            breakTotal = MiningMath.breakTicks(state, level, pos, mob.getMainHandItem());
        }
        if (breakTicks % SWING_INTERVAL == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        breakTicks++;
        level.destroyBlockProgress(mob.getId(), pos, Math.min(9, (int) (breakTicks / (double) breakTotal * 10.0)));
        if (breakTicks < breakTotal) {
            return;
        }
        if (mobGriefing(level)) {
            ItemStack tool = mob.getMainHandItem();
            for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), mob, tool)) {
                ItemStack leftover = EquipmentEvaluator.addToContainer(mob.getInventory(), drop);
                if (!leftover.isEmpty()) mob.dropAtLocation(leftover);
            }
            // Sound at the mob's world position: on a carriage the block's own effects play at its
            // far-off sub-level coordinates, where nobody hears them.
            level.playSound(null, mob.blockPosition(), state.getSoundType().getBreakSound(),
                SoundSource.BLOCKS, 1.0F, 0.85F + mob.getRandom().nextFloat() * 0.1F);
            level.destroyBlock(pos, /* dropBlock */ false, mob);
        }
        clearBreak();
    }

    /**
     * Put a backpack block at {@code pos} if the cell is free: replaceable, not the track, and with no
     * entity in it (never entomb the target, or anything else). Re-checks {@code mobGriefing}.
     */
    private boolean placeBlock(ServerLevel level, BlockPos pos) {
        if (!mobGriefing(level)) return false;
        int slot = BlockSourcePolicy.firstPlaceableSlot(mob.getInventory());
        if (slot < 0) return false;
        BlockState existing = level.getBlockState(pos);
        if (!existing.canBeReplaced() || BlockSourcePolicy.isProtectedTrackBlock(existing)) return false;
        Vec3 lo = TrainConfinement.fromBlockFrame(mob, Vec3.atLowerCornerOf(pos));
        if (!level.getEntities(mob, new AABB(lo, lo.add(1.0, 1.0, 1.0))).isEmpty()) return false;

        ItemStack stack = mob.getInventory().getItem(slot);
        BlockState state = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
        lookAtCell(pos);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        stack.shrink(1);
        if (stack.isEmpty()) mob.getInventory().setItem(slot, ItemStack.EMPTY);
        mob.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, mob.blockPosition(), state.getSoundType().getPlaceSound(),
            SoundSource.BLOCKS, 1.0F, 0.9F + mob.getRandom().nextFloat() * 0.1F);
        return true;
    }

    // ---- Helpers ---------------------------------------------------------

    private ReachPolicy.Cells cells(ServerLevel level) {
        return new ReachPolicy.Cells() {
            @Override
            public boolean solid(BlockPos pos) {
                return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            }

            @Override
            public boolean mineable(BlockPos pos) {
                BlockState state = level.getBlockState(pos);
                return ReachPolicy.isMineable(state, state.getDestroySpeed(level, pos),
                    state.hasBlockEntity(), ownsCorrectTool(state));
            }
        };
    }

    /** True if the mob holds or carries a tool that harvests {@code state}. */
    private boolean ownsCorrectTool(BlockState state) {
        if (mob.getMainHandItem().isCorrectToolForDrops(state)) return true;
        var inv = mob.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    /** An echo with a loaded ranged weapon that can see its target fights from where it stands. */
    private boolean canStillShoot(LivingEntity target) {
        ItemStack ranged = mob.bestRangedWeaponStack();
        return !ranged.isEmpty() && mob.hasRangedAmmo(ranged) && mob.hasLineOfSight(target);
    }

    /**
     * Off a train, whether the pathfinder has a route that actually arrives. On a train the carriage's
     * sub-level paths can't answer this for a riding target, so the stall alone decides.
     */
    private boolean pathReaches(LivingEntity target) {
        if (TrainConfinement.isConfined(mob)) return false;
        Path path = mob.getNavigation().createPath(target, 0);
        return path != null && path.canReach();
    }

    /** Debug trace, gated on {@code debugSpawnLog} like the other goals' traces. */
    private void trace(String fmt, Object... args) {
        if (PlayerMobConfig.debugSpawnLog()) {
            Object[] all = new Object[args.length + 1];
            all[0] = mob.getName().getString();
            System.arraycopy(args, 0, all, 1, args.length);
            LOGGER.info("[ReachTarget {}] " + fmt, all);
        }
    }

    private boolean enabled() {
        return PlayerMobConfig.combatReach()
            && mob.level() instanceof ServerLevel level && mobGriefing(level);
    }

    private static boolean mobGriefing(ServerLevel level) {
        return GameRuleCompat.mobGriefing(level);
    }

    private Vec3 frame(Vec3 world) {
        return TrainConfinement.toBlockFrame(mob, world);
    }

    private void resetStall(LivingEntity target) {
        watched = target;
        bestDist = Double.MAX_VALUE;
        lastProgressTick = mob.tickCount;
    }

    /** Walk to the centre of {@code cell} (a block-frame cell). */
    private void moveToward(BlockPos cell) {
        Vec3 w = TrainConfinement.fromBlockFrame(mob, Vec3.atBottomCenterOf(cell));
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(w.x, w.y, w.z, speed);
    }

    /**
     * Stop walking (keep falling/jumping) so it stays on the column it's building or mining from. On a
     * carriage the horizontal motion is left alone: the train carries the mob, and zeroing it would fight
     * that carry.
     */
    private void holdStill() {
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
        if (!TrainConfinement.isConfined(mob)) {
            Vec3 dm = mob.getDeltaMovement();
            mob.setDeltaMovement(0.0, dm.y, 0.0);
        }
    }

    private void lookAtCell(BlockPos cell) {
        Vec3 w = TrainConfinement.fromBlockFrame(mob, Vec3.atCenterOf(cell));
        mob.getLookControl().setLookAt(w.x, w.y, w.z);
    }

    private void clearBreak() {
        if (breakPos != null) {
            mob.level().destroyBlockProgress(mob.getId(), breakPos, -1);
        }
        breakPos = null;
        breakTicks = 0;
        breakTotal = 0;
    }
}
