package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Walk to nearby chests/barrels, OPEN them visibly, and slowly swap in
 * better gear one item at a time.
 *
 * <p><b>Phase machine:</b></p>
 * <ol>
 *   <li><b>Scan</b> — every time the goal selector evaluates {@link #canUse},
 *       iterate a cube of {@code scanRadius} around the mob, find the nearest
 *       lootable container not on cooldown, target it.</li>
 *   <li><b>Path</b> — navigate to the container. 5-second timeout for unreachable.</li>
 *   <li><b>Open</b> — calls {@link PlayerMobEntity#openContainer} which drives
 *       the visual + audio (chest lid via blockEvent, barrel via OPEN state)
 *       AND records the position on the entity so {@link PlayerMobEntity#die}
 *       can force-close it if the mob is killed mid-raid. Pause
 *       {@link #OPEN_PAUSE_TICKS} so the mob visibly "considers" the
 *       contents before grabbing.</li>
 *   <li><b>Loot</b> — iterate slots forward. Worthless contents skip
 *       instantly; an upgrade adds a random 0.3–2 second delay before the
 *       swap fires, simulating consideration.</li>
 *   <li><b>Close</b> — calls {@link PlayerMobEntity#closeOpenedContainer}
 *       which reverses the open visual + sound. Brief pause.</li>
 *   <li><b>Cleanup</b> — mark the position recently-explored, brief
 *       post-visit cooldown before rescanning.</li>
 * </ol>
 *
 * <p>Honours the {@code mobGriefing} gamerule. Combat preempts raiding —
 * registered at priority 3, below {@link WeaponAwareAttackGoal} at 2. If
 * {@link #stop} fires mid-open/loot (e.g. combat preempts) the close
 * happens cleanly. If the mob is <em>killed</em> mid-raid, {@link #stop}
 * may not fire — the safety net is {@link PlayerMobEntity#die} which
 * always closes the open container.</p>
 */
public final class RaidContainersGoal extends Goal implements DescribableGoal {

    private static final int OPEN_PAUSE_TICKS = 20;       // 1.0s after opening before first swap consideration
    private static final int CLOSE_PAUSE_TICKS = 10;      // 0.5s to "finish closing" before leaving
    private static final int MIN_SWAP_DELAY_TICKS = 6;    // 0.3s minimum per swap (was 1s — user feedback)
    private static final int MAX_SWAP_DELAY_TICKS = 40;   // 2s maximum per swap (was 5s — user feedback)
    private static final int PATH_TIMEOUT_TICKS = 100;    // 5s to reach the chest
    private static final int POST_VISIT_COOLDOWN = 20;    // 1s after finishing before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 40;    // 2s between scans when nothing found
    private static final double REACH_DISTANCE_SQR = 4.0; // 2 blocks
    private static final double SNEAK_WATCH_RANGE = 16.0; // crouch while raiding if a feared entity is this close
    private static final int SNEAK_CHECK_INTERVAL = 10;   // re-evaluate the sneak state every 0.5s

    private enum Phase { IDLE, PATHING, OPENING, LOOTING, CLOSING }

    private final PlayerMobEntity mob;
    private final double moveSpeed;
    private final int scanRadius;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int scanCooldown = 0;
    private BlockPos targetPos;

    /** Slot cursor during LOOTING phase. */
    private int currentSlot = 0;
    /**
     * Tick the next swap should fire on. -1 means "no swap scheduled yet —
     * advance currentSlot to next interesting one and schedule a new delay".
     */
    private int nextSwapAt = -1;

    /** True while sneaking a raid because a feared (Shy-toward) entity is nearby. */
    private boolean sneaking = false;
    private int sneakCheckTicks = 0;

    public RaidContainersGoal(PlayerMobEntity mob, double moveSpeed, int scanRadius) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        this.scanRadius = scanRadius;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Raiding chest";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case PATHING -> "approaching";
            case OPENING -> "opening";
            case LOOTING -> "looting";
            case CLOSING -> "closing";
            default -> null;
        };
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
                && mob.isAlive()
                && !mob.isDeadOrDying()
                && mob.getTarget() == null
                && TrainConfinement.allowsTarget(mob, targetPos);
    }

    @Override
    public void start() {
        phase = Phase.PATHING;
        phaseTicks = 0;
        currentSlot = 0;
        nextSwapAt = -1;
        mob.getNavigation().moveTo(
            targetPos.getX() + 0.5,
            targetPos.getY(),
            targetPos.getZ() + 0.5,
            moveSpeed);
    }

    @Override
    public void stop() {
        // Always close — entity's tracker is idempotent if nothing's open.
        mob.closeOpenedContainer();
        mob.setCrouching(false);
        sneaking = false;
        mob.getNavigation().stop();
        if (targetPos != null) {
            mob.markBlockExplored(targetPos);
            targetPos = null;
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        currentSlot = 0;
        nextSwapAt = -1;
        scanCooldown = POST_VISIT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Phase timing relies on per-tick increments.
        return true;
    }

    @Override
    public void tick() {
        if (targetPos == null) return;
        phaseTicks++;
        updateSneak();

        switch (phase) {
            case PATHING -> tickPathing();
            case OPENING -> tickOpening();
            case LOOTING -> tickLooting();
            case CLOSING -> tickClosing();
            default -> { /* IDLE */ }
        }
    }

    /**
     * Crouch for the whole raid when an entity this mob would flee is nearby,
     * so a timid mob sneaks to the chest and back. Throttled — the scan isn't free.
     */
    private void updateSneak() {
        if (--sneakCheckTicks <= 0) {
            sneakCheckTicks = SNEAK_CHECK_INTERVAL;
            sneaking = mob.nearestWhereReaction(Reaction.FLEE, SNEAK_WATCH_RANGE) != null;
        }
        mob.setCrouching(sneaking);
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
            // Transition: open container + start the look-pause.
            mob.openContainer(targetPos);
            phase = Phase.OPENING;
            phaseTicks = 0;
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop();
        }
    }

    /** Hold for {@link #OPEN_PAUSE_TICKS} so the mob visibly "considers" the contents before grabbing. */
    private void tickOpening() {
        if (phaseTicks >= OPEN_PAUSE_TICKS) {
            phase = Phase.LOOTING;
            phaseTicks = 0;
            currentSlot = 0;
            nextSwapAt = -1;
        }
    }

    /**
     * Iterate forward through the container. Worthless slots skip instantly;
     * each "interesting" slot adds a random 1–5 second delay before the swap.
     */
    private void tickLooting() {
        BlockEntity be = mob.level().getBlockEntity(targetPos);
        if (!(be instanceof Container container)) {
            // Container was destroyed mid-raid? Bail (CLOSING will still close
            // the visual if anything was opened — closeOpenedContainer is idempotent).
            mob.closeOpenedContainer();
            phase = Phase.CLOSING;
            phaseTicks = 0;
            return;
        }

        if (nextSwapAt == -1) {
            while (currentSlot < container.getContainerSize()
                    && !mob.wouldTakeFromContainer(container, currentSlot)) {
                currentSlot++;
            }
            if (currentSlot >= container.getContainerSize()) {
                mob.closeOpenedContainer();
                phase = Phase.CLOSING;
                phaseTicks = 0;
                return;
            }
            int delay = MIN_SWAP_DELAY_TICKS
                + mob.getRandom().nextInt(MAX_SWAP_DELAY_TICKS - MIN_SWAP_DELAY_TICKS + 1);
            nextSwapAt = mob.tickCount + delay;
            return;
        }

        if (mob.tickCount >= nextSwapAt) {
            // tryTake handles both equipment swap and food collect-into-inventory.
            // A single tryCollectFood call already moves as much as the mob's
            // inventory can hold (addToContainer fills mergeable + empty slots
            // in one pass), so always advancing is correct — re-examining the
            // same slot would just return false on the next wouldTake.
            mob.tryTakeFromContainer(container, currentSlot);
            currentSlot++;
            nextSwapAt = -1; // schedule next interesting slot
        }
    }

    private void tickClosing() {
        if (phaseTicks >= CLOSE_PAUSE_TICKS) {
            stop();
        }
    }

    /**
     * Brute-force scan of {@code (2*scanRadius+1)³} positions around the mob.
     * For a 12-block radius that's ~14k positions per scan — only invoked
     * every {@link #EMPTY_SCAN_COOLDOWN} ticks when no target. {@code getBlockEntity}
     * is O(1) on the loaded chunk's BE map so this is acceptable for a single
     * mob.
     */
    private BlockPos findClosestContainer() {
        BlockPos mobPos = mob.blockPosition();
        Level level = mob.level();
        Vec3 eye = mob.getEyePosition();
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
                    if (!TrainConfinement.allowsTarget(mob, cursor)) continue;
                    double distSq = mobPos.distSqr(cursor);
                    if (distSq >= closestDistSq) continue;
                    BlockPos candidate = cursor.immutable();
                    // Only raid chests the mob can actually see — a solid block between
                    // the mob's eyes and the chest hides it. LOS is the most expensive
                    // filter, so it runs last and only for a new nearest candidate.
                    if (!hasClearLineOfSight(level, eye, candidate)) continue;
                    closestDistSq = distSq;
                    closest = candidate;
                }
            }
        }
        return closest;
    }

    /**
     * True when a straight line from {@code eye} to the centre of {@code pos} reaches
     * it without passing through another block's collision shape — i.e. the mob can see
     * the chest rather than sensing it through a wall. Mirrors the line-of-sight test in
     * {@code DungeonTrainEnvironment#hasLineOfSight}: a solid block strictly in front of
     * the chest blocks it, while the chest's own block (or a hit at/beyond the chest
     * centre, e.g. the ray grazing its near face) counts as visible. Uses
     * {@code COLLIDER}, so glass/ice occlude — matching that precedent and vanilla mobs,
     * which can't see through glass either.
     */
    private boolean hasClearLineOfSight(Level level, Vec3 eye, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = level.clip(new ClipContext(
            eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS
            || hit.getBlockPos().equals(pos)
            || hit.getLocation().distanceToSqr(eye) + 1.0e-4 >= center.distanceToSqr(eye);
    }

    private static boolean isLootableContainer(BlockEntity be) {
        // ChestBlockEntity covers regular + trapped chests (TrappedChestBlockEntity extends ChestBlockEntity).
        // ShulkerBoxBlockEntity covers all 16 dyed variants (they all extend the base class).
        // Hoppers / dispensers / droppers / brewing stands are intentionally
        // excluded so the mob can't break redstone or brewing setups.
        return be instanceof ChestBlockEntity
            || be instanceof BarrelBlockEntity
            || be instanceof ShulkerBoxBlockEntity;
    }
}
