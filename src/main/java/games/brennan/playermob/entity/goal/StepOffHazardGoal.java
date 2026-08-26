package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.HazardBlockPolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Standing on something that hurts? Step off it. A PlayerMob that finds itself on magma, in a fire,
 * on a lit campfire, in a berry bush or a wither rose or powder snow notices after a short flinch and
 * walks to the nearest block nearby that doesn't hurt.
 *
 * <p>The flinch is {@link HazardBlockPolicy#REACTION_MIN_TICKS}–
 * {@link HazardBlockPolicy#REACTION_MAX_TICKS} ticks, rolled through the mob's
 * {@link games.brennan.playermob.entity.DispositionTraits#reactionSpeed() reaction speed}, so a quick
 * mob is off it almost immediately and a sluggish one visibly cooks for a moment first. Unlike
 * {@link DouseFireInPathGoal}'s double-take it does <em>not</em> stop the navigation while it thinks:
 * a mob already taking damage is better off drifting than frozen in place.</p>
 *
 * <p>Fire-type hazards are skipped entirely for a fire-immune or fire-resistant mob — it has no reason
 * to leave, and twitching off perfectly safe magma would read as a bug. Contact hazards (cactus,
 * berries, wither rose, powder snow) always count.</p>
 *
 * <p>Priority 0 with MOVE+LOOK and registered <em>before</em> {@link FireBucketGoal}: getting off the
 * thing that is burning you outranks running for water, and vanilla's GoalSelector breaks a
 * same-priority tie by registration order. No JUMP flag, so the priority-0 {@code FloatGoal} still owns
 * swimming. It releases the slot the moment the mob is clear, so it never holds up combat for long.
 * Ungated by config, like the mod's other reflexes ({@code BlockArrowsGoal}, {@code EatFoodGoal}) —
 * self-preservation isn't a behaviour worth switching off.</p>
 */
public final class StepOffHazardGoal extends Goal implements DescribableGoal {

    private enum Phase { HESITATE, MOVE, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Phase phase = Phase.DONE;
    /** Where the mob decided to step to. */
    private BlockPos safePos;
    private int waitTicks;
    /** Ticks spent walking to {@link #safePos}, against {@link HazardBlockPolicy#MOVE_TIMEOUT_TICKS}. */
    private int moveTicks;
    /** Game time before which not to re-scan, after a scan that found nowhere safe. */
    private long recheckAfter;

    public StepOffHazardGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        // MOVE + LOOK only — no JUMP, so FloatGoal can still own JUMP in water.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive() || !standingOnHazard()) {
            return false;
        }
        if (mob.level().getGameTime() < recheckAfter) {
            return false; // last scan found nowhere to go; don't re-scan every tick
        }
        BlockPos safe = findSafeSpot();
        if (safe == null) {
            recheckAfter = mob.level().getGameTime() + HazardBlockPolicy.RECHECK_TICKS;
            return false;
        }
        safePos = safe;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE || !mob.isAlive()) {
            return false;
        }
        // Off it — whether under its own steam, knocked clear, or the block was removed.
        return standingOnHazard();
    }

    @Override
    public void start() {
        phase = Phase.HESITATE;
        moveTicks = 0;
        waitTicks = mob.reactRoll(HazardBlockPolicy.REACTION_MIN_TICKS, HazardBlockPolicy.REACTION_MAX_TICKS);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        phase = Phase.DONE;
        safePos = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (phase) {
            case HESITATE -> tickHesitate();
            case MOVE -> tickMove();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    /**
     * The flinch. Deliberately leaves the navigation alone: whatever the mob was already doing keeps it
     * moving, which is strictly better than standing still in lava while it works out it's on fire.
     */
    private void tickHesitate() {
        if (--waitTicks > 0) {
            return;
        }
        phase = Phase.MOVE;
        moveTicks = 0;
        walkToSafety();
    }

    /** Walk to the chosen spot, re-picking if the path finishes or stalls while still on the hazard. */
    private void tickMove() {
        if (safePos != null) {
            mob.getLookControl().setLookAt(safePos.getX() + 0.5, safePos.getY() + 0.5, safePos.getZ() + 0.5);
        }
        if (++moveTicks > HazardBlockPolicy.MOVE_TIMEOUT_TICKS) {
            // Couldn't get there in time — back off and let canUse() start over from scratch.
            recheckAfter = mob.level().getGameTime() + HazardBlockPolicy.RECHECK_TICKS;
            phase = Phase.DONE;
            return;
        }
        if (safePos == null || mob.getNavigation().isDone()) {
            walkToSafety();
        }
    }

    /** Re-pick a safe spot and path to it; giving up ends the goal so canUse() can retry later. */
    private void walkToSafety() {
        BlockPos safe = findSafeSpot();
        if (safe == null) {
            recheckAfter = mob.level().getGameTime() + HazardBlockPolicy.RECHECK_TICKS;
            phase = Phase.DONE;
            return;
        }
        safePos = safe;
        mob.getNavigation().moveTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, speed);
    }

    /** Whether the mob's feet, head, or the floor under it is something that hurts it right now. */
    private boolean standingOnHazard() {
        Level level = mob.level();
        BlockPos feet = mob.blockPosition();
        boolean fireImmune = ignoresFire();
        return HazardBlockPolicy.isHarmful(level.getBlockState(feet), fireImmune)
            || HazardBlockPolicy.isHarmful(level.getBlockState(feet.above()), fireImmune)
            || HazardBlockPolicy.isHarmful(level.getBlockState(feet.below()), fireImmune);
    }

    /** Fire hazards are no hazard at all to a mob that can't burn. */
    private boolean ignoresFire() {
        return mob.fireImmune() || mob.hasEffect(MobEffects.FIRE_RESISTANCE);
    }

    /**
     * The nearest standable, non-hurting block within {@link HazardBlockPolicy#SEARCH_RADIUS}, or
     * {@code null} if the mob is boxed in on hazard. Uses the same
     * {@link BlockPos#findClosestMatch} scan {@link FireBucketGoal#findNearbyWater} does.
     */
    private BlockPos findSafeSpot() {
        Level level = mob.level();
        boolean fireImmune = ignoresFire();
        Optional<BlockPos> found = BlockPos.findClosestMatch(mob.blockPosition(),
            HazardBlockPolicy.SEARCH_RADIUS, HazardBlockPolicy.SEARCH_VERTICAL,
            pos -> isSafeStand(level, pos, fireImmune));
        return found.map(BlockPos::immutable).orElse(null);
    }

    /**
     * A spot the mob could stand in without being hurt: feet and head space clear and harmless, on a
     * sturdy floor that is itself harmless. The floor check is what keeps it from "escaping" one magma
     * block onto the next one along.
     */
    private boolean isSafeStand(Level level, BlockPos pos, boolean fireImmune) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        // Room to stand — the collision-shape test the mod's other placement scans use, rather than
        // BlockState#isPathfindable, whose signature moves between the MC versions this builds for.
        if (!feet.getCollisionShape(level, pos).isEmpty()
            || !head.getCollisionShape(level, pos.above()).isEmpty()) {
            return false;
        }
        if (HazardBlockPolicy.isHarmful(feet, fireImmune) || HazardBlockPolicy.isHarmful(head, fireImmune)) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        return floor.isFaceSturdy(level, below, Direction.UP)
            && !HazardBlockPolicy.isHarmful(floor, fireImmune);
    }

    @Override
    public String objective() {
        return "Avoiding damage";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case HESITATE -> "hazard underfoot";
            case MOVE -> "stepping clear";
            default -> null;
        };
    }
}
