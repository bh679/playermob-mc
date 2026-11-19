package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.PetSnapshots;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.EquipmentEvaluator;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import games.brennan.playermob.entity.ReactionChannel;
import games.brennan.playermob.entity.TamingPolicy;
import games.brennan.playermob.mixin.ContainerLockAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * The <b>Taming</b> hobby: walk up to an animal and feed it the food it breeds (or, for a baby,
 * grows) on — and, carrying none, fetch some from a nearby chest first. How often the mob does it
 * scales with its Taming score ({@link TamingPolicy#scanChance}); every pause scales with its
 * {@link ReactionChannel#ITEMS} reaction speed.
 *
 * <p><b>Phases:</b> scan → (fetch: walk to a container, open it, look, take at most
 * {@link TamingPolicy#FETCH_TAKE} matching items, close) → walk to the animal → feed. After feeding
 * an adult the mob prefers the same kind on its next scan, so it completes the pair.</p>
 *
 * <p><b>Feeds directly, as itself.</b> It calls {@code setInLove(null)} / {@code ageUp} on the
 * animal rather than routing through a fake player, so no player's life record, stats or
 * advancements are touched, and it never tames ({@link TamingPolicy#mayFeed}).</p>
 *
 * <p><b>Containers</b> obey exactly {@link RaidContainersGoal}'s rules — {@code mobGriefing},
 * the {@code searchContainers} scavenge mode, same-train confinement, line of sight, chests/barrels/
 * shulkers only, the explored-block cooldown — plus a vanilla container lock. No slot is read until
 * the lid is open.</p>
 *
 * <p>Registered at priority 5: combat (2) and raiding/eating (3) preempt it, and it stops the moment
 * the mob takes a target or a feared entity comes near.</p>
 */
public final class FeedAnimalsGoal extends Goal implements DescribableGoal {

    private static final int SCAN_COOLDOWN = 100;          // 5s between hobby scans
    private static final int PAIR_COOLDOWN = 20;           // 1s before going for the partner
    private static final int PAIR_WINDOW = 600;            // 30s to finish a pair
    private static final int OPEN_PAUSE_TICKS = 20;        // look into the chest
    private static final int CLOSE_PAUSE_TICKS = 10;
    private static final int FEED_WINDUP_TICKS = 10;       // hold the food out before it's taken
    private static final int PATH_TIMEOUT_TICKS = 160;     // 8s to reach a chest or an animal
    private static final int REPATH_INTERVAL = 10;         // animals wander — re-aim every 0.5s
    private static final double ANIMAL_SCAN_RADIUS = 10.0;
    private static final int CONTAINER_SCAN_RADIUS = 10;
    private static final double REACH_CONTAINER_SQR = 4.0;
    private static final double REACH_ANIMAL_SQR = 6.25;   // 2.5 blocks
    private static final double FEAR_RANGE = 16.0;

    private enum Phase { IDLE, TO_CONTAINER, OPENING, CLOSING, TO_ANIMAL, FEEDING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private Animal animal;
    private BlockPos containerPos;
    private int containerTries = 0;
    private EntityType<?> preferType;
    private int preferUntil = 0;

    public FeedAnimalsGoal(PlayerMobEntity mob, double moveSpeed) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Feeding animals";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case TO_CONTAINER -> "fetching food";
            case OPENING, CLOSING -> "searching chest";
            case TO_ANIMAL -> "approaching";
            case FEEDING -> "feeding";
            default -> null;
        };
    }

    // ---- lifecycle ----------------------------------------------------------

    @Override
    public boolean canUse() {
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        scanCooldown = mob.reactTicks(ReactionChannel.ITEMS, SCAN_COOLDOWN);
        int taming = mob.taming();
        if (taming <= 0 || !calm()) return false;
        boolean finishingPair = preferType != null && mob.tickCount < preferUntil;
        if (!finishingPair && !TamingPolicy.rollsToAct(taming, mob.getRandom().nextDouble())) {
            return false;
        }
        Animal found = findAnimal(finishingPair ? preferType : null);
        if (found == null) return false;
        animal = found;
        containerTries = 0;
        containerPos = null;
        if (findFood(animal) != null) return true;
        containerPos = findContainer();
        return containerPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.IDLE || animal == null || !mob.isAlive() || !calm()) return false;
        if (!animal.isAlive() || !TrainConfinement.allowsTarget(mob, animal)) return false;
        return containerPos == null || TrainConfinement.allowsTarget(mob, containerPos);
    }

    @Override
    public void start() {
        phaseTicks = 0;
        if (containerPos != null) {
            phase = Phase.TO_CONTAINER;
            moveToContainer();
        } else {
            phase = Phase.TO_ANIMAL;
            mob.getNavigation().moveTo(animal, moveSpeed);
        }
    }

    @Override
    public void stop() {
        mob.closeOpenedContainer();
        mob.getNavigation().stop();
        if (containerPos != null) {
            mob.markBlockExplored(containerPos);
        }
        containerPos = null;
        animal = null;
        phase = Phase.IDLE;
        phaseTicks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        switch (phase) {
            case TO_CONTAINER -> tickToContainer();
            case OPENING -> tickOpening();
            case CLOSING -> tickClosing();
            case TO_ANIMAL -> tickToAnimal();
            case FEEDING -> tickFeeding();
            default -> { /* IDLE */ }
        }
    }

    /** Never while fighting, or with something it fears nearby. */
    private boolean calm() {
        return mob.getTarget() == null && mob.nearestWhereReaction(Reaction.FLEE, FEAR_RANGE) == null;
    }

    // ---- fetch ----------------------------------------------------------------

    private void moveToContainer() {
        mob.getNavigation().moveTo(
            containerPos.getX() + 0.5, containerPos.getY(), containerPos.getZ() + 0.5, moveSpeed);
    }

    private void tickToContainer() {
        double distSq = mob.distanceToSqr(Vec3.atCenterOf(containerPos));
        if (distSq < REACH_CONTAINER_SQR) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(Vec3.atCenterOf(containerPos));
            mob.openContainer(containerPos);
            phase = Phase.OPENING;
            phaseTicks = 0;
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop();
        }
    }

    /** Pause with the lid open, then take what the animal eats. */
    private void tickOpening() {
        if (phaseTicks < mob.reactTicks(ReactionChannel.ITEMS, OPEN_PAUSE_TICKS)) return;
        takeFood(mob.level().getBlockEntity(containerPos));
        mob.closeOpenedContainer();
        phase = Phase.CLOSING;
        phaseTicks = 0;
    }

    private void tickClosing() {
        if (phaseTicks < mob.reactTicks(ReactionChannel.ITEMS, CLOSE_PAUSE_TICKS)) return;
        mob.markBlockExplored(containerPos);
        if (findFood(animal) != null) {
            containerPos = null;
            phase = Phase.TO_ANIMAL;
            phaseTicks = 0;
            mob.getNavigation().moveTo(animal, moveSpeed);
            return;
        }
        // Nothing it eats in there — try another container, a few at most.
        BlockPos next = ++containerTries < TamingPolicy.MAX_CONTAINER_TRIES ? findContainer() : null;
        if (next == null) {
            stop();
            return;
        }
        containerPos = next;
        phase = Phase.TO_CONTAINER;
        phaseTicks = 0;
        moveToContainer();
    }

    /** Move up to {@link TamingPolicy#FETCH_TAKE} items the animal eats into the backpack. */
    private void takeFood(BlockEntity be) {
        if (!(be instanceof Container container) || animal == null) return;
        int wanted = TamingPolicy.FETCH_TAKE;
        for (int slot = 0; slot < container.getContainerSize() && wanted > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !animal.isFood(stack)) continue;
            ItemStack taken = container.removeItem(slot, Math.min(wanted, stack.getCount()));
            wanted -= taken.getCount();
            ItemStack leftover = EquipmentEvaluator.addToContainer(mob.getInventory(), taken);
            if (!leftover.isEmpty()) {
                mob.dropAtLocation(leftover);
            }
        }
        container.setChanged();
    }

    // ---- feed -----------------------------------------------------------------

    private void tickToAnimal() {
        if (mob.distanceToSqr(animal) < REACH_ANIMAL_SQR) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(animal);
            phase = Phase.FEEDING;
            phaseTicks = 0;
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop();
        } else if (phaseTicks % REPATH_INTERVAL == 0) {
            mob.getNavigation().moveTo(animal, moveSpeed);
        }
    }

    private void tickFeeding() {
        mob.getLookControl().setLookAt(animal);
        if (phaseTicks == 1) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        if (phaseTicks < mob.reactTicks(ReactionChannel.ITEMS, FEED_WINDUP_TICKS)) return;
        if (feeds(animal)) {
            feed(animal);
        }
        stop();
    }

    /** Re-check at the moment of feeding: the animal may have grown, been fed, or wandered off. */
    private boolean feeds(Animal target) {
        return mob.distanceToSqr(target) < REACH_ANIMAL_SQR * 2 && mayFeed(target)
            && findFood(target) != null;
    }

    private void feed(Animal target) {
        ItemStack food = findFood(target);
        if (food == null) return;
        ItemStack shown = food.copy();
        food.shrink(1);
        if (target.isBaby()) {
            target.ageUp(AgeableMob.getSpeedUpSecondsWhenFeeding(-target.getAge()), true);
            if (mob.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX(), target.getY(0.5),
                    target.getZ(), 6, 0.3, 0.3, 0.3, 0.0);
            }
        } else {
            // A null cause: the mob is not a player, so the eventual baby credits nobody.
            target.setInLove(null);
            preferType = target.getType();
            preferUntil = mob.tickCount + PAIR_WINDOW;
        }
        mob.spawnEatingParticles(shown);
        scanCooldown = mob.reactTicks(ReactionChannel.ITEMS,
            target.isBaby() ? SCAN_COOLDOWN : PAIR_COOLDOWN);
    }

    /** A stack the animal eats in the mob's hands or backpack, or {@code null}. */
    private ItemStack findFood(Animal target) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = mob.getItemInHand(hand);
            if (!held.isEmpty() && target.isFood(held)) return held;
        }
        Container pack = mob.getInventory();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (!stack.isEmpty() && target.isFood(stack)) return stack;
        }
        return null;
    }

    // ---- scans ----------------------------------------------------------------

    /** The nearest animal the mob may feed, of {@code onlyType} if given. */
    private Animal findAnimal(EntityType<?> onlyType) {
        AABB box = mob.getBoundingBox().inflate(ANIMAL_SCAN_RADIUS);
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Animal candidate : mob.level().getEntitiesOfClass(Animal.class, box, Entity::isAlive)) {
            if (onlyType != null && candidate.getType() != onlyType) continue;
            double dist = mob.distanceToSqr(candidate);
            if (dist >= bestDist) continue;
            if (!TrainConfinement.allowsTarget(mob, candidate)) continue;
            if (!mayFeed(candidate)) continue;
            if (!mob.getSensing().hasLineOfSight(candidate)) continue;
            best = candidate;
            bestDist = dist;
        }
        return best;
    }

    private boolean mayFeed(Animal target) {
        List<Animal> kin = target.level().getEntitiesOfClass(Animal.class,
            target.getBoundingBox().inflate(TamingPolicy.HERD_RADIUS),
            a -> a.isAlive() && a.getType() == target.getType());
        boolean partner = false;
        for (Animal other : kin) {
            if (other != target && TamingPolicy.isPartner(other.isBaby(), other.getAge(),
                    other.isInLove(), other.canFallInLove(), isWildTamable(other))) {
                partner = true;
                break;
            }
        }
        return TamingPolicy.mayFeed(target.isBaby(), target.getAge(), target.isInLove(),
            target.canFallInLove(), isWildTamable(target), kin.size(), partner);
    }

    /** A tamable animal no one owns yet — the mob never feeds (and so never tames) one. */
    private static boolean isWildTamable(Animal animal) {
        return animal instanceof OwnableEntity && PetSnapshots.ownerUuid(animal) == null;
    }

    /** The nearest container the mob may take food from, or {@code null}. */
    private BlockPos findContainer() {
        Level level = mob.level();
        if (!GameRuleCompat.mobGriefing(level)) return null;
        if (!mob.allowsScavenging(PlayerMobConfig.searchContainers())) return null;
        BlockPos origin = mob.blockPosition();
        Vec3 eye = mob.getEyePosition();
        long now = mob.tickCount;
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int r = CONTAINER_SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (!isTakeableContainer(be)) continue;
                    double dist = origin.distSqr(cursor);
                    if (dist >= closestDist) continue;
                    if (mob.isBlockExplored(cursor, now)) continue;
                    if (!TrainConfinement.allowsTarget(mob, cursor)) continue;
                    BlockPos candidate = cursor.immutable();
                    if (!canSee(level, eye, candidate)) continue;
                    closest = candidate;
                    closestDist = dist;
                }
            }
        }
        return closest;
    }

    /** Chests, barrels and shulker boxes (as {@link RaidContainersGoal}), never a locked one. */
    private static boolean isTakeableContainer(BlockEntity be) {
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity)) {
            return false;
        }
        return !isLocked((BaseContainerBlockEntity) be);
    }

    private static boolean isLocked(BaseContainerBlockEntity be) {
        //? if >=26 {
        /*return be.isLocked();
        *///?} else {
        LockCode lock = ((ContainerLockAccessor) be).playermob$lockKey();
        return lock != null && !lock.equals(LockCode.NO_LOCK);
        //?}
    }

    /** Same line-of-sight test as {@link RaidContainersGoal}: no solid block before the container. */
    private boolean canSee(Level level, Vec3 eye, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = level.clip(new ClipContext(
            eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS
            || hit.getBlockPos().equals(pos)
            || hit.getLocation().distanceToSqr(eye) + 1.0e-4 >= center.distanceToSqr(eye);
    }
}
