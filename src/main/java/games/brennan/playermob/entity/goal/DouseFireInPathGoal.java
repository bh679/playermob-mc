package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.PathFirePolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Stop for fire instead of walking into it. A PlayerMob heading somewhere glances a few blocks up its
 * path; if there's a fire block in the way it stops short, hesitates, and puts it out — punching it
 * out bare-handed, or emptying a water bucket on it if it carries one and scooping the water straight
 * back up.
 *
 * <p><b>Fallible on purpose.</b> The mob only spots the fire {@link PathFirePolicy#NOTICE_CHANCE} of
 * the time; a missed fire is remembered so it isn't re-rolled every tick, and the mob walks in and
 * catches alight, at which point the priority-0 {@link FireBucketGoal} takes over and it runs for
 * water. A mob that always stopped would simply never burn on a path fire, which reads as
 * omniscience. The hesitation is a long {@link PathFirePolicy#REACTION_MIN_TICKS}–
 * {@link PathFirePolicy#REACTION_MAX_TICKS} ticks for the same reason: a double-take, not a reflex. That
 * window — and every bucket swap in the routine — is skewed by the mob's
 * {@link games.brennan.playermob.entity.DispositionTraits#reactionSpeed() reaction speed}, low for a
 * quick mob and high for a slow one; at the neutral speed it is the plain uniform roll.</p>
 *
 * <p>It makes no exception for fire the mob lit itself. Its own combat fire from
 * {@link FlintAndSteelIgniteGoal} is just fire in the way like any other — though because both goals
 * sit at priority 1, this one can never interrupt that ritual midway; it only ever sees that fire once
 * the mob has finished lighting it and stowed the flint and steel.</p>
 *
 * <p>Priority 1 with MOVE+LOOK, beside {@link DoorOperationGoal}: the trigger fires mid-walk while a
 * priority-2-or-lower movement goal already holds the slot, and vanilla's GoalSelector only lets a
 * strictly higher-priority goal preempt a running one. No JUMP flag, so the priority-0
 * {@code FloatGoal} keeps the mob afloat. Gated on {@link PlayerMobConfig#douseFires()}, but not on
 * {@code mobGriefing} — putting a fire <em>out</em> is the same ordinary-use category as
 * {@link FireBucketGoal}'s bucket work, not world-griefing.</p>
 */
public final class DouseFireInPathGoal extends Goal implements DescribableGoal {

    /**
     * How long a missed fire stays ignored, so a failed notice roll isn't re-rolled every tick.
     * Deliberately <em>not</em> reaction-scaled: it's a memory window, not a reaction — the same
     * category as the mod's other recency timers.
     */
    private static final int IGNORE_TICKS = 200;
    /** How many path nodes ahead to glance at. */
    private static final int PATH_LOOKAHEAD_NODES = 3;

    private enum Phase { HESITATE, SWAP_IN, DOUSE, PICKUP, SWAP_OUT, DONE }

    private final PlayerMobEntity mob;

    private Phase phase = Phase.DONE;
    private BlockPos firePos;
    /** Where the water went, while the mob is about to scoop it back up. */
    private BlockPos waterPos;
    private int waitTicks;
    /** A fire the mob failed to notice, and the tick that lapse expires. */
    private BlockPos ignoredPos;
    private long ignoredUntil;

    public DouseFireInPathGoal(PlayerMobEntity mob) {
        this.mob = mob;
        // MOVE + LOOK only — no JUMP, so FloatGoal can still own JUMP in water.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!PlayerMobConfig.douseFires() || !mob.isAlive()) {
            return false;
        }
        // Already burning? Getting wet is the priority-0 FireBucketGoal's job, not this one's.
        if (mob.isOnFire()) {
            return false;
        }
        BlockPos fire = fireAhead();
        if (fire == null) {
            return false;
        }
        if (fire.equals(ignoredPos) && mob.level().getGameTime() < ignoredUntil) {
            return false; // already failed to notice this one
        }
        if (!PathFirePolicy.notices(mob.getRandom())) {
            ignoredPos = fire;
            ignoredUntil = mob.level().getGameTime() + IGNORE_TICKS;
            return false; // walks straight in — FireBucketGoal picks up the pieces
        }
        firePos = fire;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE || !mob.isAlive()) {
            return false;
        }
        if (phase == Phase.PICKUP || phase == Phase.SWAP_OUT) {
            return true; // finish reclaiming the water / putting the bucket away regardless
        }
        // The fire going out on its own (spreading, rain, someone else) ends the errand.
        return firePos != null && PathFirePolicy.isFire(mob.level().getBlockState(firePos));
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        waterPos = null;
        phase = Phase.HESITATE;
        waitTicks = mob.reactRoll(PathFirePolicy.REACTION_MIN_TICKS, PathFirePolicy.REACTION_MAX_TICKS);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        phase = Phase.DONE;
        firePos = null;
        waterPos = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (firePos != null && phase != Phase.PICKUP) {
            mob.getLookControl().setLookAt(firePos.getX() + 0.5, firePos.getY() + 0.5, firePos.getZ() + 0.5);
        }
        switch (phase) {
            case HESITATE -> tickHesitate();
            case SWAP_IN -> tickSwapIn();
            case DOUSE -> tickDouse();
            case PICKUP -> tickPickup();
            case SWAP_OUT -> tickSwapOut();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    /** Stand and look at it. The delayed reaction that makes this read as noticing, not sensing. */
    private void tickHesitate() {
        mob.getNavigation().stop(); // hold position — don't drift into the flames while thinking
        if (--waitTicks > 0) {
            return;
        }
        if (WaterBucketUse.hasBucketAnywhere(mob)) {
            phase = Phase.SWAP_IN;
            waitTicks = bucketSwapTicks();
        } else {
            phase = Phase.DOUSE;
        }
    }

    /** Draw the water bucket, with the same wind-up as any other tool swap. */
    private void tickSwapIn() {
        mob.getNavigation().stop();
        if (--waitTicks > 0) {
            return;
        }
        // A failed draw just falls through to punching it out.
        WaterBucketUse.drawIntoMainHand(mob);
        phase = Phase.DOUSE;
    }

    /** Put it out: water on it if a bucket ended up in hand, otherwise a bare-handed swing. */
    private void tickDouse() {
        Level level = mob.level();
        if (firePos == null || !PathFirePolicy.isFire(level.getBlockState(firePos))) {
            phase = Phase.DONE;
            return;
        }
        mob.swing(InteractionHand.MAIN_HAND);
        if (mob.getMainHandItem().is(Items.WATER_BUCKET)) {
            WaterBucketUse.empty(mob, firePos);
            waterPos = firePos.immutable();
            phase = Phase.PICKUP;
            waitTicks = bucketSwapTicks();
            return;
        }
        // Bare-handed: fire breaks in one hit and drops nothing, exactly as a player punching it out.
        level.destroyBlock(firePos, false, mob);
        level.playSound(null, firePos.getX() + 0.5, firePos.getY() + 0.5, firePos.getZ() + 0.5,
            SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
        phase = Phase.DONE;
    }

    /** Scoop the water back, so the mob doesn't leave a puddle where the fire was. */
    private void tickPickup() {
        if (--waitTicks > 0) {
            return;
        }
        if (waterPos != null && !mob.level().getFluidState(waterPos).isEmpty()) {
            WaterBucketUse.fill(mob, waterPos);
        }
        waterPos = null;
        phase = Phase.SWAP_OUT;
        waitTicks = bucketSwapTicks();
    }

    /** Put the bucket away and pick a weapon back up before handing the slot back. */
    private void tickSwapOut() {
        if (--waitTicks > 0) {
            return;
        }
        mob.equipBestMeleeInHand();
        phase = Phase.DONE;
    }

    /**
     * Wind-up for drawing, refilling or stowing the water bucket, skewed low for a quick-reacting mob
     * and high for a slow one. Every bucket pause in this goal runs through here, so this one call is
     * where reaction speed reaches all of them; at the neutral reaction speed it is the uniform
     * {@link PathFirePolicy#BUCKET_SWAP_MIN_TICKS}–{@link PathFirePolicy#BUCKET_SWAP_MAX_TICKS} roll it
     * replaces.
     */
    private int bucketSwapTicks() {
        return mob.reactRoll(PathFirePolicy.BUCKET_SWAP_MIN_TICKS, PathFirePolicy.BUCKET_SWAP_MAX_TICKS);
    }

    /**
     * The nearest fire block the mob is about to walk into, or {@code null}. Looks at the next few
     * nodes of the live navigation path, then at the blocks straight ahead along its motion (which
     * covers a mob drifting or being pushed without a path), and only accepts one inside
     * {@link PathFirePolicy#LOOKAHEAD_SQR} — close enough to put out from where it stops.
     */
    private BlockPos fireAhead() {
        Level level = mob.level();
        Path path = mob.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            int from = path.getNextNodeIndex();
            int to = Math.min(path.getNodeCount(), from + PATH_LOOKAHEAD_NODES);
            for (int i = from; i < to; i++) {
                BlockPos candidate = accept(level, path.getNodePos(i));
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        Vec3 motion = mob.getDeltaMovement();
        int steps = PathFirePolicy.stepsAhead(motion.x, motion.z);
        if (steps > 0) {
            Vec3 heading = new Vec3(motion.x, 0.0, motion.z).normalize();
            for (int step = 1; step <= steps; step++) {
                BlockPos ahead = BlockPos.containing(
                    mob.getX() + heading.x * step, mob.getY(), mob.getZ() + heading.z * step);
                BlockPos candidate = accept(level, ahead);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** {@code pos} if it holds fire within reach of the mob, else {@code null}. */
    private BlockPos accept(Level level, BlockPos pos) {
        if (!PathFirePolicy.isFire(level.getBlockState(pos))) {
            return null;
        }
        if (mob.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > PathFirePolicy.LOOKAHEAD_SQR) {
            return null;
        }
        return pos.immutable();
    }

    @Override
    public String objective() {
        return "Dousing fire";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case HESITATE -> "fire ahead";
            case SWAP_IN -> "grabbing bucket";
            case DOUSE -> "putting it out";
            case PICKUP -> "collecting water";
            case SWAP_OUT -> "stowing bucket";
            default -> null;
        };
    }
}
