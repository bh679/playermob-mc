package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
 *   <li><b>Open</b> — animate the container (chest lid via {@link Level#blockEvent},
 *       barrel via {@link BarrelBlock#OPEN} state property) + play the
 *       matching open sound. Pause {@link #OPEN_PAUSE_TICKS} so the mob
 *       "looks at" the contents before grabbing anything.</li>
 *   <li><b>Loot</b> — iterate slots forward. Slots with worthless contents
 *       skip instantly. Slots with a better item add a random 1–5 second
 *       delay (per the user's spec) before the swap fires, simulating the
 *       mob considering each piece.</li>
 *   <li><b>Close</b> — animate close + matching sound. Pause briefly.</li>
 *   <li><b>Cleanup</b> — mark the container position as recently explored
 *       (60-second cooldown), brief post-visit cooldown before rescanning.</li>
 * </ol>
 *
 * <p>Honours the {@code mobGriefing} gamerule. Combat preempts raiding —
 * registered at priority 3, below {@link WeaponAwareAttackGoal} at 2. If
 * {@link #stop} fires mid-OPEN/LOOTING (e.g. combat preempts), the chest
 * is force-closed so the lid doesn't get stuck open.</p>
 */
public final class RaidContainersGoal extends Goal {

    private static final int OPEN_PAUSE_TICKS = 20;       // 1.0s after opening before first swap consideration
    private static final int CLOSE_PAUSE_TICKS = 10;      // 0.5s to "finish closing" before leaving
    private static final int MIN_SWAP_DELAY_TICKS = 20;   // 1s minimum per swap
    private static final int MAX_SWAP_DELAY_TICKS = 100;  // 5s maximum per swap
    private static final int PATH_TIMEOUT_TICKS = 100;    // 5s to reach the chest
    private static final int POST_VISIT_COOLDOWN = 20;    // 1s after finishing before rescanning
    private static final int EMPTY_SCAN_COOLDOWN = 40;    // 2s between scans when nothing found
    private static final double REACH_DISTANCE_SQR = 4.0; // 2 blocks

    /** Chest blockEvent ID for "viewer count changed", per {@link ChestBlockEntity#triggerEvent}. */
    private static final int CHEST_VIEWERS_EVENT = 1;

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

    public RaidContainersGoal(PlayerMobEntity mob, double moveSpeed, int scanRadius) {
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
                && mob.getTarget() == null;
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
        // If we were mid-open or mid-loot, the container's lid is currently
        // animated open — force-close it so it doesn't get stuck visually.
        if (phase == Phase.OPENING || phase == Phase.LOOTING) {
            setContainerOpen(false);
        }
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

        switch (phase) {
            case PATHING -> tickPathing();
            case OPENING -> tickOpening();
            case LOOTING -> tickLooting();
            case CLOSING -> tickClosing();
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
            // Transition: open container + start the look-pause.
            setContainerOpen(true);
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
     * Iterate forward through the container. For each slot:
     * <ul>
     *   <li>If the candidate item isn't an upgrade → skip instantly (cursor
     *       advances same tick). Worthless slots cost no time.</li>
     *   <li>If it IS an upgrade → schedule the swap for {@link #MIN_SWAP_DELAY_TICKS}
     *       –{@link #MAX_SWAP_DELAY_TICKS} ticks in the future. When the
     *       deadline arrives, execute the swap, advance the cursor, reset
     *       the schedule.</li>
     * </ul>
     */
    private void tickLooting() {
        BlockEntity be = mob.level().getBlockEntity(targetPos);
        if (!(be instanceof Container container)) {
            // Container was destroyed mid-raid? Bail.
            phase = Phase.CLOSING;
            phaseTicks = 0;
            return;
        }

        if (nextSwapAt == -1) {
            // Advance cursor to the next slot the mob actually wants.
            while (currentSlot < container.getContainerSize()
                    && !mob.wouldReplaceFromContainer(container, currentSlot)) {
                currentSlot++;
            }
            if (currentSlot >= container.getContainerSize()) {
                // Nothing more to take.
                phase = Phase.CLOSING;
                phaseTicks = 0;
                setContainerOpen(false);
                return;
            }
            // Schedule the swap with random 1–5s delay.
            int delay = MIN_SWAP_DELAY_TICKS
                + mob.getRandom().nextInt(MAX_SWAP_DELAY_TICKS - MIN_SWAP_DELAY_TICKS + 1);
            nextSwapAt = mob.tickCount + delay;
            return;
        }

        if (mob.tickCount >= nextSwapAt) {
            mob.tryReplaceFromContainer(container, currentSlot);
            currentSlot++;
            nextSwapAt = -1; // schedule next interesting slot
        }
        // Otherwise: wait for nextSwapAt to arrive (mob just stands at the container).
    }

    private void tickClosing() {
        if (phaseTicks >= CLOSE_PAUSE_TICKS) {
            stop();
        }
    }

    /**
     * Drive the container's visual + audio open/close. Handles both chest
     * (uses {@link Level#blockEvent} with the chest viewer-count event) and
     * barrel (uses the {@link BarrelBlock#OPEN} block-state property
     * directly — barrels don't honour the blockEvent path).
     */
    private void setContainerOpen(boolean open) {
        Level level = mob.level();
        BlockEntity be = level.getBlockEntity(targetPos);
        BlockState state = level.getBlockState(targetPos);
        if (be == null) return;

        if (be instanceof ChestBlockEntity) {
            level.blockEvent(targetPos, state.getBlock(), CHEST_VIEWERS_EVENT, open ? 1 : 0);
            SoundEvent sound = open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE;
            playContainerSound(sound);
        } else if (be instanceof BarrelBlockEntity) {
            if (state.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(targetPos, state.setValue(BarrelBlock.OPEN, open), 3);
            }
            SoundEvent sound = open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE;
            playContainerSound(sound);
        }
    }

    private void playContainerSound(SoundEvent sound) {
        mob.level().playSound(
            /* exclude */ null,
            targetPos,
            sound,
            SoundSource.BLOCKS,
            /* volume */ 0.5F,
            /* pitch */ 0.9F + mob.getRandom().nextFloat() * 0.1F);
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

    private static boolean isLootableContainer(BlockEntity be) {
        // ChestBlockEntity covers regular + trapped chests (TrappedChestBlockEntity extends ChestBlockEntity).
        // Whitelisting only chest + barrel keeps the mob from raiding hoppers,
        // dispensers, droppers, brewing stands, shulker boxes — items that
        // usually have specific purposes and shouldn't be touched.
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity;
    }
}
