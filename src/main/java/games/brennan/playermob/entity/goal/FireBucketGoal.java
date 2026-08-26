package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Optional;

/**
 * On fire? Getting to water is self-preservation and preempts everything — this goal runs at
 * priority 0 (see {@code PlayerMobEntity#registerGoals}) so nothing else can distract the mob
 * while it's burning, and it <em>never</em> times out: it keeps trying until it's wet or the
 * flames have burnt out.
 *
 * <ul>
 *   <li>Natural water within {@link #RUN_TO_WATER_MAX_DISTANCE_SQR} blocks — sprint over and jump
 *       in, bucket or not (fastest when it's close).</li>
 *   <li>No water that close, but carrying a water bucket (hand <em>or</em> backpack) — swap it into
 *       the main hand if needed, empty it <em>at its own feet</em>, then scoop the puddle back up so
 *       it doesn't litter a bucket-shaped hole. Water is always placed against a solid block (the
 *       floor it's standing on), never floating.</li>
 *   <li>Farther-off water and no bucket — still sprints to the distant water; it's the only option.</li>
 * </ul>
 * Each "does something with the bucket" step has a short random wind-up so it reads as a
 * deliberate action rather than an instant snap.
 *
 * <p>MOVE+LOOK only (no JUMP, so the priority-0 {@link net.minecraft.world.entity.ai.goal.FloatGoal}
 * still owns swimming — see the goal-JUMP/FloatGoal gotcha); the run/approach calls
 * {@code getJumpControl().jump()} directly, which doesn't require owning the flag, mirroring
 * {@link TrainRecoveryGoal}.</p>
 *
 * <p>The vanilla water bucket is emptied/filled by placing and removing the source block directly
 * (this goal only ever fires for {@link Items#WATER_BUCKET}), rather than through a fake-player
 * raytrace — that keeps the water landing exactly where we chose (at the feet, against the floor)
 * instead of wherever a raytrace happens to hit. Placing water and picking it back up is ordinary
 * bucket use, not world-griefing, so this isn't gated on {@code mobGriefing}.</p>
 */
public final class FireBucketGoal extends Goal implements DescribableGoal {

    /** Horizontal/vertical range {@link BlockPos#findClosestMatch} scans for existing water. */
    private static final int WATER_SCAN_HORIZONTAL = 8;
    private static final int WATER_SCAN_VERTICAL = 4;
    /** Natural water this close or closer always wins over using a held bucket — it's simply faster. */
    private static final double RUN_TO_WATER_MAX_DISTANCE_SQR = 16.0; // 4 blocks

    private enum Phase { RUN, SWAP, PLACE, APPROACH, PICKUP, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Phase phase = Phase.DONE;
    private BlockPos waterPos;
    private int waitTicks;

    public FireBucketGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!PlayerMobConfig.extinguishWithBucket() || !mob.isOnFire()) {
            return false;
        }
        return findNearbyWater() != null || hasBucketAnywhere();
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.DONE && mob.isAlive();
    }

    private boolean hasBucketAnywhere() {
        return mob.getMainHandItem().is(Items.WATER_BUCKET)
            || mob.getOffhandItem().is(Items.WATER_BUCKET)
            || packHasBucket();
    }

    private boolean packHasBucket() {
        Container pack = mob.getInventory();
        for (int i = 0; i < pack.getContainerSize(); i++) {
            if (pack.getItem(i).is(Items.WATER_BUCKET)) {
                return true;
            }
        }
        return false;
    }

    /** Nearest existing water block within scan range, or {@code null} if there's none loaded nearby. */
    private BlockPos findNearbyWater() {
        Level level = mob.level();
        Optional<BlockPos> found = BlockPos.findClosestMatch(mob.blockPosition(),
            WATER_SCAN_HORIZONTAL, WATER_SCAN_VERTICAL, pos -> level.getFluidState(pos).is(FluidTags.WATER));
        return found.orElse(null);
    }

    @Override
    public void start() {
        waterPos = null;
        BlockPos nearby = findNearbyWater();
        boolean hasBucket = hasBucketAnywhere();
        // Run to natural water if it's close (≤4 blocks), or if there's no bucket to fall back on.
        // Otherwise use the bucket in place rather than trek to distant water.
        if (nearby != null && (mob.distanceToSqr(nearby.getX() + 0.5, nearby.getY() + 0.5, nearby.getZ() + 0.5)
                <= RUN_TO_WATER_MAX_DISTANCE_SQR || !hasBucket)) {
            phase = Phase.RUN;
            waterPos = nearby.immutable();
        } else if (hasBucket) {
            if (mob.getMainHandItem().is(Items.WATER_BUCKET)) {
                phase = Phase.PLACE;
                waitTicks = randomTicks(5, 30);
            } else {
                phase = Phase.SWAP;
                waitTicks = randomTicks(5, 20);
            }
        } else {
            // Shouldn't happen — canUse() just confirmed one of the two options — but bail cleanly if it did.
            phase = Phase.DONE;
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.setSprinting(false);
        phase = Phase.DONE;
        waterPos = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (phase) {
            case RUN -> tickRun();
            case SWAP -> tickSwap();
            case PLACE -> tickPlace();
            case APPROACH -> tickApproach();
            case PICKUP -> tickPickup();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    /** Sprint to natural water — no bucket involved, nothing to pick back up. Never gives up while burning. */
    private void tickRun() {
        if (!mob.isOnFire()) {           // flames burnt out on their own — done
            phase = Phase.DONE;
            return;
        }
        if (mob.isInWater()) {           // made it in — done
            mob.getNavigation().stop();
            mob.setSprinting(false);
            phase = Phase.DONE;
            return;
        }
        // Keep re-targeting the nearest water so a stalled path re-routes to another pool. Never a
        // timeout: while on fire, getting wet is the only thing that matters.
        if (waterPos == null || mob.getNavigation().isDone()) {
            BlockPos nearest = findNearbyWater();
            if (nearest != null) {
                waterPos = nearest.immutable();
            }
        }
        if (waterPos != null) {
            runToward(waterPos);
        }
    }

    /** Wind-up before swapping a bucket into the main hand (from the off-hand or the backpack). */
    private void tickSwap() {
        if (!mob.isOnFire()) {           // flames burnt out before we could act — nothing placed, done
            phase = Phase.DONE;
            return;
        }
        if (--waitTicks > 0) {
            return;
        }
        if (mob.getOffhandItem().is(Items.WATER_BUCKET)) {
            ItemStack off = mob.getOffhandItem();
            ItemStack main = mob.getMainHandItem();
            mob.setItemSlot(EquipmentSlot.OFFHAND, main);
            mob.setItemSlot(EquipmentSlot.MAINHAND, off);
        } else if (!mob.equipWeapon(Items.WATER_BUCKET)) {
            // Shouldn't happen — canUse() just confirmed a bucket somewhere — but bail cleanly if it did.
            phase = Phase.DONE;
            return;
        }
        phase = Phase.PLACE;
        waitTicks = randomTicks(5, 30);
    }

    /** Wind-up before emptying the (now main-hand) bucket at the mob's own feet, against the floor. */
    private void tickPlace() {
        if (!mob.isOnFire()) {           // flames burnt out before we placed — nothing to clean up, done
            phase = Phase.DONE;
            return;
        }
        if (--waitTicks > 0) {
            return;
        }
        BlockPos pos = findPlacement();
        if (pos == null) {
            // Nowhere to place against a block right now. Prefer running to natural water if any is in
            // range; otherwise keep trying (never give up while burning).
            BlockPos water = findNearbyWater();
            if (water != null) {
                phase = Phase.RUN;
                waterPos = water.immutable();
            } else {
                waitTicks = randomTicks(5, 30);
            }
            return;
        }
        emptyBucket(pos);
        waterPos = pos.immutable();
        phase = Phase.APPROACH;
    }

    /** Get into the puddle it just placed (usually already standing in it), then go reclaim the water. */
    private void tickApproach() {
        // In the water (doused) or the fire's out — either way, go pick the water back up.
        if (mob.isInWater() || !mob.isOnFire()) {
            mob.getNavigation().stop();
            mob.setSprinting(false);
            phase = Phase.PICKUP;
            waitTicks = randomTicks(5, 30);
            return;
        }
        runToward(waterPos);
    }

    /** Wind-up before scooping the puddle back into the (now empty) bucket. */
    private void tickPickup() {
        if (--waitTicks > 0) {
            return;
        }
        if (mob.level().getFluidState(waterPos).is(FluidTags.WATER)) {
            fillBucket(waterPos);
        }
        phase = Phase.DONE;
    }

    /** Sprint toward {@code pos}, looking at it and hopping if grounded. */
    private void runToward(BlockPos pos) {
        mob.setSprinting(true);
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
        if (mob.onGround()) {
            mob.getJumpControl().jump();
        }
    }

    /** Place a water source at {@code pos} and turn the held water bucket into an empty one. */
    private void emptyBucket(BlockPos pos) {
        Level level = mob.level();
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BUCKET));
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /** Remove the water source at {@code pos} and turn the held empty bucket back into a water one. */
    private void fillBucket(BlockPos pos) {
        Level level = mob.level();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WATER_BUCKET));
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /**
     * A delay somewhere in {@code [min, max]}, skewed low for a quick-reacting mob and high for
     * a slow one. Every pause in this goal's bucket routine runs through here, so this one call
     * is where reaction speed reaches all of them.
     */
    private int randomTicks(int minInclusive, int maxInclusive) {
        return mob.reactRoll(minInclusive, maxInclusive);
    }

    /**
     * A spot to empty the bucket, always against a solid block (never floating):
     * <ol>
     *   <li>the mob's own feet, against the floor below (option #1 — the puddle forms right underfoot);</li>
     *   <li>a feet-level horizontal neighbour, against its own floor;</li>
     *   <li>head level, but only if there's a solid block beside/under it to place against.</li>
     * </ol>
     * Returns {@code null} if none qualifies.
     */
    private BlockPos findPlacement() {
        Level level = mob.level();
        BlockPos feet = mob.blockPosition();
        if (isFeetSpot(level, feet)) {
            return feet.immutable();
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (isFeetSpot(level, side)) {
                return side.immutable();
            }
        }
        BlockPos head = feet.above();
        if (level.getBlockState(head).canBeReplaced() && hasSolidNeighbour(level, head)) {
            return head.immutable();
        }
        return null;
    }

    /** Replaceable and standing on a sturdy floor — a feet-level spot whose water rests against the block below. */
    private boolean isFeetSpot(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).canBeReplaced()) {
            return false;
        }
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** True if any of {@code pos}'s six neighbours presents a sturdy face toward it — a block to place water against. */
    private boolean hasSolidNeighbour(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            if (level.getBlockState(n).isFaceSturdy(level, n, dir.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String objective() {
        return "Dousing fire";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case RUN -> "running to water";
            case SWAP -> "grabbing bucket";
            case PLACE -> "emptying bucket";
            case APPROACH -> "wading in";
            case PICKUP -> "collecting water";
            default -> null;
        };
    }
}
