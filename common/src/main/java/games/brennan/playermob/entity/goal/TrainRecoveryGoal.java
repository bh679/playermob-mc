package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.compat.TrainEnvironment;
import games.brennan.playermob.entity.BlockSourcePolicy;
import games.brennan.playermob.entity.EquipmentEvaluator;
import games.brennan.playermob.entity.ItemPickupPolicy;
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
public final class TrainRecoveryGoal extends Goal implements DescribableGoal {

    /** How far to look for a carriage to re-board. A fall keeps the mob near its train. */
    private static final double SCAN_RADIUS = 28.0;
    /** Once the nearest carriage is farther than this (it outran us), give up. */
    private static final double ABANDON_DISTANCE = 24.0;
    /**
     * Anti-softlock backstop on a whole recovery attempt (5 min). Recovery is the mob's
     * sole focus until it's aboard — this only frees a mob that is truly wedged next to a
     * stationary, un-boardable train, so it doesn't stay in recovery literally forever.
     */
    private static final int GLOBAL_ABANDON_TICKS = 6000;
    /**
     * Give up only after this long with <em>zero</em> forward progress (30s). Placing,
     * gathering, climbing and waiting-for-an-opening all count as progress and reset it, so
     * in practice this never fires while the mob is actively working its way back — it's a
     * stuck-detector, not a patience limit.
     */
    private static final int STALL_ABANDON_TICKS = 600;
    /** Tolerate the carriage briefly going out of range / unresolved before abandoning (2s). */
    private static final int TARGET_GRACE_TICKS = 40;
    /** APPROACH walks back first; it only hands off to BRIDGE after being stuck below the deck this long. */
    private static final int APPROACH_STUCK_LIMIT = 15;
    /** How far below the deck to scan for standable ground when picking an approach spot. */
    private static final int GROUND_SCAN_DEPTH = 24;
    /** Max blocks to step out (perpendicular to the track) looking for off-track ground to launch from. */
    private static final int OFF_TRACK_MAX_STEPS = 4;
    /** Re-issue navigation toward the moving carriage at this cadence. */
    private static final int PATH_REISSUE_TICKS = 5;
    /**
     * Max bridge blocks placed per recovery — bounded so a failing mob doesn't pave the
     * tracks, but high enough to tower from terrain well below an elevated deck (the old 16
     * capped tall climbs, so a mob that gathered enough still gave up at the BRIDGE gate).
     */
    private static final int MAX_PLACEMENTS = 32;
    /**
     * Tower this many blocks ABOVE the deck/track level before boarding. Deck-level
     * footing leaves a walled carriage's wall in the way; +3 clears typical walls so the
     * mob can drop in through an opening (flatbed / hole) from above.
     */
    private static final int TOWER_ABOVE_DECK = 3;
    /** Extra bridge blocks to gather beyond the bare climb — a cushion so a tower never runs short. */
    private static final int BLOCK_BUFFER = 5;
    /**
     * Never gather blocks more than this far below the deck. Stops the "ratchet": chasing
     * the nearest block trip after trip walked the mob ever downward, away from the moving
     * train, until it gave up — bound the descent so it gathers near the deck and climbs back.
     */
    private static final int MAX_GATHER_BELOW_DECK = 10;
    /** Ticks between bridge placements, leaving time to climb the previous step. */
    private static final int PLACE_INTERVAL_TICKS = 10;
    /** Gather scan cube half-extent and reach. */
    private static final int GATHER_SCAN_RADIUS = 8;
    private static final double GATHER_REACH_SQR = 9.0;     // 3 blocks
    private static final int GATHER_PATH_TIMEOUT = 80;       // 4s to reach a gather block
    /** Realistic break-time bounds; the actual duration scales with hardness ÷ tool speed. */
    private static final int MIN_BREAK_TICKS = 5;
    private static final int MAX_BREAK_TICKS = 120;
    /** "Reach for the tool" pause after an auto tool-swap: 0.1–1.0s. */
    private static final int TOOL_SWAP_MIN_TICKS = 2;
    private static final int TOOL_SWAP_MAX_TICKS = 20;
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
    private int lastProgressTick = 0;            // totalTicks at the last gather / place / step-closer
    private double lastApproachDist = Double.MAX_VALUE;  // mob→approach-spot dist last APPROACH tick
    private double lastApproachY = -1.0e9;        // mob Y last APPROACH tick (height-gain = progress)
    private int approachStuckTicks = 0;
    private int missTicks = 0;                     // consecutive ticks the carriage was out of range

    /** Re-resolved every evaluation/tick — the carriage moves. */
    private TrainEnvironment.ReboardTarget target;
    private BlockPos gatherTargetPos;
    private BlockPos pillarColumn;     // foot column captured at jump-stack launch
    private int gatherBreakTicks = 0;
    private int breakTicksTotal = 0;   // 0 = not yet sized for the current gather target
    private int toolReadyTick = 0;     // tick the post-tool-swap pause ends

    public TrainRecoveryGoal(PlayerMobEntity mob, double moveSpeed) {
        this.mob = mob;
        this.moveSpeed = moveSpeed;
        // MOVE + LOOK only — deliberately NOT JUMP. FloatGoal (priority 0) holds JUMP,
        // and a higher-priority goal that needs a flag a lower one holds STOPS the lower
        // goal. If recovery held JUMP, the instant the mob touched water FloatGoal would
        // steal it and abort recovery (mob then wanders/drowns). Without JUMP, recovery
        // keeps running while FloatGoal keeps it afloat — the mob swims toward the train.
        // Recovery still jumps when it needs to (jump-stacking, hopping aboard) via the
        // JumpControl directly, which doesn't require owning the flag.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Returning to train";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case APPROACH -> "approaching";
            case BRIDGE -> "bridging";
            case GATHER -> "gathering blocks";
            case CRAFT -> "crafting";
            default -> null;
        };
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
        // Don't start mid-air / mid-fall; wait until we have footing on something.
        if (!mob.onGround()) return false;
        TrainEnvironment.ReboardTarget t = TrainConfinement.nearestCarriage(mob, SCAN_RADIUS);
        if (t == null) {
            return false;
        }
        // Already genuinely on the deck → nothing to recover. Uses the actual footprint, not
        // the loose ride margin, so a mob that fell off and is *beside* the carriage (which
        // the margin counts as "on") still recovers.
        if (isAboard(t.worldBox())) return false;
        this.target = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.IDLE || !mob.isAlive() || mob.isDeadOrDying()) return false;
        if (!mobGriefingOn()) return false;
        if (totalTicks - lastProgressTick >= STALL_ABANDON_TICKS) return false; // wedged → give up
        if (totalTicks >= GLOBAL_ABANDON_TICKS) return false;                   // anti-softlock backstop
        TrainEnvironment.ReboardTarget t = TrainConfinement.nearestCarriage(mob, SCAN_RADIUS);
        boolean reachable = t != null && horizontalDistToBox(t.worldBox()) <= ABANDON_DISTANCE;
        if (!reachable) {
            // Don't drop control the instant a carriage blinks out of range / resolution —
            // a momentary miss would hand the mob to a wander goal ("distraction"). Hold on
            // through a short grace window; only truly outrun → abandon.
            return ++missTicks < TARGET_GRACE_TICKS;
        }
        // Success ONLY when genuinely on the deck (inside the footprint) — not merely within
        // the ride margin beside it, which used to end recovery at track height before the
        // mob could finish towering up and drop in.
        if (isAboard(t.worldBox())) return false;
        missTicks = 0;
        this.target = t;
        return true;
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        phaseTicks = 0;
        totalTicks = 0;
        placementsUsed = 0;
        lastProgressTick = 0;
        lastApproachDist = Double.MAX_VALUE;
        lastApproachY = -1.0e9;
        approachStuckTicks = 0;
        missTicks = 0;
        gatherTargetPos = null;
        pillarColumn = null;
        mob.setRecovering(true);   // off the train, re-boarding is the mob's sole focus (no combat)
        moveTowardCarriage();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (gatherTargetPos != null) {
            mob.level().destroyBlockProgress(mob.getId(), gatherTargetPos, -1);  // clear cracking overlay
            mob.markBlockExplored(gatherTargetPos);
            gatherTargetPos = null;
        }
        pillarColumn = null;
        phase = Phase.IDLE;
        phaseTicks = 0;
        cooldown = POST_COOLDOWN_TICKS;
        mob.setRecovering(false);   // recovery ended (aboard or abandoned) — combat may resume
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
        // Top priority in EVERY phase: if we can already board from here (high enough + an
        // opening in reach), just do it — don't tower or gather when a hop will do.
        if (tryBoardNow(target.worldBox())) {
            return;
        }
        // Never settle on the rails/bed — the mob can't board the moving carriage from the
        // static track, so if it's on the tracks, get off them first (re-approach an off-track
        // launch spot) before towering/gathering/waiting.
        if (onTracks() && phase != Phase.APPROACH) {
            phase = Phase.APPROACH;
            phaseTicks = 0;
            lastApproachDist = Double.MAX_VALUE;
            lastApproachY = -1.0e9;
            approachStuckTicks = 0;
        }
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
        clearClimbObstruction(box);   // clear foliage in the way as it walks up to the spot
        // Already up at deck height and right beside the carriage (fell onto a tree / high
        // ground), and NOT on the tracks? Don't climb back DOWN to the ground launch spot to
        // gather — hold here and let an opening slide into reach; tryBoardNow (top of tick)
        // leaps the moment one does. (≤2.5 keeps us inside boarding reach so a hop can land.)
        if (!onTracks() && mob.getY() >= box.minY - 1.0 && horizontalDistToBox(box) <= 2.5) {
            waitForOpening(box);
            return;
        }
        // Walk to the HIGHEST ground beside the track (least climb), and only start the tower
        // once we're actually there — so the mob doesn't build from a low spot right where it
        // fell when better footing is a few blocks along the rail.
        // approachPoint is recomputed EVERY tick, so the target is continuously re-assessed:
        // if a better (higher / less-climb) spot comes into reach as the mob moves or the train
        // slides, the chosen spot shifts and the mob heads to the new one — it only commits to
        // bridging once it's actually standing at the current best spot.
        Vec3 spot = approachPoint(box);
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(spot.x, spot.y, spot.z, moveSpeed);
            mob.getLookControl().setLookAt(spot.x, spot.y, spot.z);
        }
        double distToSpot = Math.hypot(mob.getX() - spot.x, mob.getZ() - spot.z);
        // Progress = closing on the chosen spot OR gaining height (walking up terrain counts).
        if (distToSpot < lastApproachDist - 0.05 || mob.getY() > lastApproachY + 0.05) {
            approachStuckTicks = 0;
            markProgress();
        } else {
            approachStuckTicks++;
        }
        lastApproachDist = distToSpot;
        lastApproachY = mob.getY();
        // At the (re-assessed) best spot, or can't get closer → commit. Gather first if short.
        if (distToSpot <= 1.6 || approachStuckTicks > APPROACH_STUCK_LIMIT) {
            commitFromApproach();
        }
        // Dropping onto the carriage flips isAboard → canContinueToUse ends us (success).
    }

    /**
     * Leave APPROACH: tower from here, but only once we hold enough blocks for the whole climb
     * plus the buffer ({@link #blocksNeeded()} = climb + {@link #BLOCK_BUFFER}). If short, go
     * GATHER until we have enough first, rather than starting a tower that runs out partway up.
     */
    private void commitFromApproach() {
        phaseTicks = 0;
        approachStuckTicks = 0;
        if (BlockSourcePolicy.bridgeBlockCount(mob.getInventory()) >= blocksNeeded()) {
            phase = Phase.BRIDGE;
        } else {
            phase = Phase.GATHER;
            gatherTargetPos = null;
        }
    }

    // ---- BRIDGE -----------------------------------------------------------

    private void tickBridge() {
        AABB box = target.worldBox();
        clearClimbObstruction(box);   // punch through any leaves/plants blocking the way up
        // No top-level navigation here: stairs steer onto each placed step, and jump-stack
        // pillars straight up with nav OFF (otherwise the mob walks off its own 1-wide tower).
        int slot = bridgeBlockSlot();              // non-log placeable; non-gravity preferred
        if (slot < 0) {
            // No directly-placeable block — craft logs into planks if we have any,
            // otherwise go gather one.
            if (firstCraftableLogSlot() >= 0) {
                phase = Phase.CRAFT;
                phaseTicks = 0;
            } else {
                phase = Phase.GATHER;
                phaseTicks = 0;
                gatherTargetPos = null;
            }
            return;
        }
        if (placementsUsed >= MAX_PLACEMENTS) {     // bridge isn't working → abandon
            stop();
            return;
        }
        // Stairs by default; jump-stack a pillar for gravity blocks (a staircase of
        // sand/gravel would just fall).
        BlockState block = ((BlockItem) mob.getInventory().getItem(slot).getItem())
            .getBlock().defaultBlockState();
        if (BlockSourcePolicy.isGravityBlock(block)) {
            tickJumpStack(slot, box);
        } else {
            tickStairs(slot, box);
        }
    }

    /**
     * Staircase bridging: step out-and-up toward the carriage's near edge. Stops
     * short of building <em>under</em> the deck — once the next step would go
     * beneath it, switch to pillaring up beside it (jump-stack), then hop across.
     */
    private void tickStairs(int slot, AABB box) {
        if (mob.blockPosition().getY() >= Mth.floor(box.minY) + TOWER_ABOVE_DECK) {
            waitForOpening(box);                       // towered clear; tryBoardNow leaps when an opening is in reach
            return;
        }
        BlockPos place = BlockSourcePolicy.nextBridgePos(mob.blockPosition(), box);
        if (place == null || isUnderBox(place, box)) {
            tickJumpStack(slot, box);                  // at the edge — pillar up beside, don't build under
            return;
        }
        // Steer onto the next step (toward the carriage), rising with the mob — never back
        // toward the ground approach point.
        mob.getNavigation().moveTo(place.getX() + 0.5, place.getY() + 1.0, place.getZ() + 0.5, moveSpeed);
        // Already a usable step here (existing stairs/terrain — possibly one this or another
        // mob built)? Climb it instead of rebuilding; just walk on, no placement.
        if (isStandableStep(place)) {
            markProgress();
            return;
        }
        if (phaseTicks % PLACE_INTERVAL_TICKS != 0) return;   // let it climb the previous step
        lookAt(place);
        if (tryPlaceBridgeBlock(place, slot)) {
            placementsUsed++;
            markProgress();
        }
    }

    /**
     * True if {@code pos} is already a solid block the mob can stand on (full collision, two
     * passable cells above for headroom) and is neither the carriage nor protected track — an
     * existing step/pillar to climb rather than rebuild. Lets recovery reuse a staircase that's
     * already there instead of paving a redundant one beside it.
     */
    private boolean isStandableStep(BlockPos pos) {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        BlockState s = level.getBlockState(pos);
        if (!s.isCollisionShapeFullBlock(level, pos)) return false;
        if (BlockSourcePolicy.isProtectedTrackBlock(s)) return false;
        if (target != null && target.worldBox().intersects(new AABB(pos))) return false;
        return level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
            && level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty();
    }

    /**
     * Jump-stack a pillar beside the mob: capture the foot column on launch, then
     * at the apex place a block in that just-vacated space so the mob lands one
     * block higher. Repeats until level with the deck, then hops across. The jump
     * cycle paces placement (one per launch). Works for gravity blocks (sand/gravel),
     * which a staircase out into the air can't support.
     */
    private void tickJumpStack(int slot, AABB box) {
        if (mob.blockPosition().getY() >= Mth.floor(box.minY) + TOWER_ABOVE_DECK) {
            waitForOpening(box);                       // towered 3 above; tryBoardNow leaps when an opening is in reach
            return;
        }
        // Pillar straight up: nav OFF so no horizontal path-following walks the mob off its
        // own 1-wide tower, and zero horizontal drift EVERY tick so it rises and lands back
        // on the same column (no leftover approach momentum carries it off).
        mob.getNavigation().stop();
        Vec3 dm = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, dm.y, 0.0);
        if (mob.onGround()) {
            pillarColumn = mob.blockPosition();        // the space we're about to vacate
            mob.getJumpControl().jump();
            return;
        }
        // At/after the apex: fill the captured foot space so we land on it.
        if (pillarColumn != null
                && mob.getY() >= pillarColumn.getY() + 1.0
                && dm.y <= 0.0) {
            lookAt(pillarColumn);
            if (tryPlaceBridgeBlock(pillarColumn, slot)) {
                placementsUsed++;
                pillarColumn = null;
                markProgress();
            }
        }
    }

    /**
     * If the mob can board RIGHT NOW from wherever it is — the train seam finds an opening
     * within jump reach (drop in, or hop up ≤1) — leap straight in and return true, skipping
     * any further tower/gather. Checked every tick in every phase, so a mob that fell onto a
     * tree or onto already-high ground at deck level just hops back on instead of pointlessly
     * collecting blocks. Returns false when it isn't yet high enough / no opening is in reach.
     */
    private boolean tryBoardNow(AABB box) {
        Vec3 spot = TrainConfinement.boardingSpot(mob, box);
        if (spot == null) {
            return false;
        }
        // Leap toward the opening. Vanilla nav can't path onto the moving carriage, so steer
        // the body straight at the spot with the MoveControl (works off the nav graph) and hop.
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(spot.x, spot.y, spot.z);
        mob.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, moveSpeed);
        if (mob.onGround()) mob.getJumpControl().jump();
        markProgress();
        return true;
    }

    /**
     * Towered as high as we can but no opening is in reach (carriage walled/roofed at our
     * column): HOLD station and wait for an open carriage to slide alongside, rather than
     * bonking the wall. Zero horizontal drift so the mob doesn't wander off its tower top.
     * Waiting counts as progress, bounded only by the global backstop. {@link #tryBoardNow}
     * catches the moment an opening arrives.
     */
    private void waitForOpening(AABB box) {
        mob.getNavigation().stop();
        Vec3 dm = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, dm.y, 0.0);
        mob.getLookControl().setLookAt(
            (box.minX + box.maxX) / 2.0, box.minY + 1.0, (box.minZ + box.maxZ) / 2.0);
        markProgress();
    }

    /** True if {@code pos}'s column lies within the carriage footprint (i.e. under the deck). */
    private static boolean isUnderBox(BlockPos pos, AABB box) {
        double x = pos.getX() + 0.5, z = pos.getZ() + 0.5;
        return x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ;
    }

    private void lookAt(BlockPos pos) {
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * Punch through one soft block (leaves, plants, hand-breakable) blocking the way up —
     * the head-room the mob jumps into and the step toward the carriage — so climbing into
     * a tree doesn't stall recovery (it would otherwise walk up to a stair it built and not
     * use it because foliage was in the way). One block per call, gated on mobGriefing; never
     * the carriage or protected track. Returns true if it cleared one.
     */
    private boolean clearClimbObstruction(AABB box) {
        if (!(mob.level() instanceof ServerLevel level) || !mobGriefingOn()) return false;
        BlockPos foot = mob.blockPosition();
        net.minecraft.core.Direction dir = BlockSourcePolicy.horizontalDirToward(foot, box);
        BlockPos[] cells = {
            foot.above(2),                  // head-room to jump into
            foot.relative(dir).above(),     // step toward carriage, head height
            foot.relative(dir),             // step toward carriage, foot height
        };
        for (BlockPos p : cells) {
            BlockState s = level.getBlockState(p);
            if (s.isAir() || BlockSourcePolicy.isProtectedTrackBlock(s)) continue;
            if (box.intersects(new AABB(p))) continue;                  // never the carriage itself
            if (!BlockSourcePolicy.isClearableObstruction(s)) continue;
            level.destroyBlock(p, /* dropBlock */ true, mob);           // clear the path (drops + FX)
            return true;
        }
        return false;
    }

    /**
     * A directly-placeable non-log block slot, preferring non-gravity blocks (for
     * staircase bridging) over gravity blocks (jump-stack), or {@code -1} if none.
     * Logs are excluded — they're crafted into planks first.
     */
    private int bridgeBlockSlot() {
        var inv = mob.getInventory();
        int gravitySlot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!ItemPickupPolicy.isBuildingBlock(s) || BlockSourcePolicy.isCraftableLog(s)) continue;
            BlockState bs = ((BlockItem) s.getItem()).getBlock().defaultBlockState();
            if (BlockSourcePolicy.isGravityBlock(bs)) {
                if (gravitySlot < 0) gravitySlot = i;
                continue;
            }
            return i;
        }
        return gravitySlot;
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
        if (target != null && target.worldBox().intersects(cell)) return false;   // never the carriage box
        if (!level.getEntities(mob, cell).isEmpty()) return false;                 // don't suffocate anything

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
            if (gatherTargetPos == null) {
                // Nothing more reachable — bridge with whatever we've collected, or give up.
                if (BlockSourcePolicy.bridgeBlockCount(mob.getInventory()) > 0) {
                    phase = Phase.BRIDGE;
                    phaseTicks = 0;
                } else {
                    stop();
                }
                return;
            }
            gatherBreakTicks = 0;
            breakTicksTotal = 0;
            toolReadyTick = 0;
            phaseTicks = 0;
            mob.getNavigation().moveTo(
                gatherTargetPos.getX() + 0.5, gatherTargetPos.getY(), gatherTargetPos.getZ() + 0.5, moveSpeed);
            markProgress();    // committing to a fresh target is forward motion, not a stall
            return;
        }
        // Block changed/destroyed since we picked it.
        BlockState state = mob.level().getBlockState(gatherTargetPos);
        if (!BlockSourcePolicy.isHandBreakable(state) && !BlockSourcePolicy.isStoneClass(state)) {
            mob.level().destroyBlockProgress(mob.getId(), gatherTargetPos, -1);
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
        // In reach: auto-select the best tool (a brief pause to "switch"), then
        // break over a realistic, hardness-and-tool-scaled duration. Tool-swapping and
        // breaking are active work — keep the stall-abandon timer from firing mid-break.
        markProgress();
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(
            gatherTargetPos.getX() + 0.5, gatherTargetPos.getY() + 0.5, gatherTargetPos.getZ() + 0.5);
        if (breakTicksTotal == 0) {
            // First tick in reach: pick the right tool and size the break.
            if (mob.equipBetterToolFor(state)) {
                toolReadyTick = mob.tickCount + TOOL_SWAP_MIN_TICKS
                    + mob.getRandom().nextInt(TOOL_SWAP_MAX_TICKS - TOOL_SWAP_MIN_TICKS + 1);
            }
            breakTicksTotal = breakTicksFor(state);
            gatherBreakTicks = 0;
        }
        if (mob.tickCount < toolReadyTick) return;      // reach-for-the-tool pause
        if (gatherBreakTicks % 4 == 0) mob.swing(InteractionHand.MAIN_HAND);
        gatherBreakTicks++;
        // Cracking overlay scaled to break progress, so it reads as real mining.
        mob.level().destroyBlockProgress(mob.getId(), gatherTargetPos,
            Math.min(9, (int) (gatherBreakTicks / (double) breakTicksTotal * 10.0)));
        if (gatherBreakTicks >= breakTicksTotal) {
            harvestGatherTarget(state);
            markProgress();
            // Got enough for the climb + buffer → go back to APPROACH to RE-ASSESS the best
            // launch spot (we may have wandered while gathering, and the train has moved), then
            // tower from there. Not enough yet → stay in GATHER; next tick picks the next block.
            if (BlockSourcePolicy.bridgeBlockCount(mob.getInventory()) >= blocksNeeded()) {
                phase = Phase.APPROACH;
                phaseTicks = 0;
                lastApproachDist = Double.MAX_VALUE;
                lastApproachY = -1.0e9;
                approachStuckTicks = 0;
            }
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
        mob.level().destroyBlockProgress(mob.getId(), gatherTargetPos, -1);  // clear cracking overlay
        mob.markBlockExplored(gatherTargetPos);
        gatherTargetPos = null;
    }

    /** True if the mob owns a tool that can harvest {@code state} (or it needs none). */
    private boolean canHarvest(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        if (mob.getMainHandItem().isCorrectToolForDrops(state)) return true;
        var inv = mob.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    /** Realistic break duration for {@code state} with the mob's current main-hand tool. */
    private int breakTicksFor(BlockState state) {
        float hardness = state.getDestroySpeed(mob.level(), gatherTargetPos);
        if (hardness <= 0.0f) return MIN_BREAK_TICKS;   // instabreak-ish
        float toolSpeed = Math.max(1.0f, mob.getMainHandItem().getDestroySpeed(state));
        // Vanilla mining time ≈ hardness × 30 ÷ tool speed ticks for a harvestable block.
        int ticks = Math.round(hardness * 30.0f / toolSpeed);
        return Mth.clamp(ticks, MIN_BREAK_TICKS, MAX_BREAK_TICKS);
    }

    /**
     * Roughly how many bridge blocks the mob needs to climb back onto the carriage
     * from where it is now — the vertical gap up to the carriage floor (one block
     * per staircase step), clamped to {@link #MAX_PLACEMENTS}. Recovery gathers up
     * to this many before it stops collecting and starts bridging.
     */
    private int blocksNeeded() {
        // Climb all the way to TOWER_ABOVE_DECK above the deck (clearance over the wall),
        // plus a cushion so the tower never runs out of blocks partway up.
        int climb = Mth.floor(target.worldBox().minY) + TOWER_ABOVE_DECK - mob.blockPosition().getY();
        return Mth.clamp(climb + BLOCK_BUFFER, 1, MAX_PLACEMENTS);
    }

    /** Nearest cheap, hand-breakable, non-track block worth breaking for a bridge block. */
    private BlockPos findGatherTarget() {
        BlockPos origin = mob.blockPosition();
        BlockPos feetBelow = origin.below();
        Level level = mob.level();
        long now = mob.tickCount;
        // Floor on how low we'll dig: never chase blocks far below the deck, or the mob
        // ratchets downward away from the (moving) train and never climbs back.
        int minGatherY = Mth.floor(target.worldBox().minY) - MAX_GATHER_BELOW_DECK;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -GATHER_SCAN_RADIUS; dx <= GATHER_SCAN_RADIUS; dx++) {
            for (int dy = -GATHER_SCAN_RADIUS; dy <= GATHER_SCAN_RADIUS; dy++) {
                for (int dz = -GATHER_SCAN_RADIUS; dz <= GATHER_SCAN_RADIUS; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (cursor.equals(feetBelow)) continue;        // never mine our own footing
                    if (cursor.getY() < minGatherY) continue;      // don't dig down away from the deck
                    BlockState state = level.getBlockState(cursor);
                    // Hand-breakable (dirt/sand/gravel/logs), or stone-class when the mob
                    // holds a correct tool — both yield placeable bridge blocks.
                    boolean gatherable = BlockSourcePolicy.isHandBreakable(state)
                        || (BlockSourcePolicy.isStoneClass(state) && canHarvest(state));
                    if (!gatherable) continue;
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

    /**
     * Where APPROACH walks the mob: a real, standable spot just OUTSIDE the carriage's
     * near side, at the actual ground surface — so vanilla nav gets a reachable node
     * (the space beside a floating/cut-in train is usually air at deck level, which is
     * why a naive deck-Y target left the mob standing still, "not navigating").
     *
     * <p>The train runs along world-X (long axis) and is narrow in Z, so the mob
     * re-boards from a Z side. We sample several columns <em>along the track</em> and
     * pick the one whose ground is highest — the shortest climb, i.e. the "easiest way
     * back" — preferring the mob's own column unless another is meaningfully higher.
     * Only the final ascent from there is bridged.</p>
     */
    private Vec3 approachPoint(AABB box) {
        boolean nearNorth = Math.abs(mob.getZ() - box.minZ) <= Math.abs(mob.getZ() - box.maxZ);
        double faceZ = nearNorth ? box.minZ : box.maxZ;
        int step = nearNorth ? -1 : 1;
        double centerX = Mth.clamp(mob.getX(), box.minX + 0.5, box.maxX - 0.5);
        // Step OUT perpendicular to the track until the ground is off the rails/bed. The mob
        // can't board while standing on the tracks, so it launches and towers from beside them.
        double tz = faceZ + step;
        for (int i = 1; i <= OFF_TRACK_MAX_STEPS; i++) {
            tz = faceZ + step * i;
            if (!surfaceIsTrack(centerX, box.minY, tz)) {
                break;                          // first off-track column out — launch from here
            }
        }
        // Scan along the carriage length (at this off-track offset) for higher ground.
        double bestX = centerX;
        double bestGroundY = groundSurfaceY(bestX, box.minY, tz);
        int samples = 10;
        for (int i = 0; i <= samples; i++) {
            double x = box.minX + (box.maxX - box.minX) * (i / (double) samples);
            double g = groundSurfaceY(x, box.minY, tz);
            if (g > bestGroundY + 0.5) {        // meaningfully higher → worth walking to
                bestGroundY = g;
                bestX = x;
            }
        }
        return new Vec3(bestX, bestGroundY, tz);
    }

    /**
     * True if the standable surface at column {@code (x, z)} (scanning down from the deck) is a
     * protected track block — the rails/bed the train runs on, which the mob must not launch
     * from (it can't board the moving carriage while standing on the static track).
     */
    private boolean surfaceIsTrack(double x, double deckMinY, double z) {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        int ix = Mth.floor(x), iz = Mth.floor(z);
        int top = Mth.floor(deckMinY);
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int y = top; y >= top - GROUND_SCAN_DEPTH; y--) {
            c.set(ix, y, iz);
            BlockState s = level.getBlockState(c);
            if (s.isCollisionShapeFullBlock(level, c)) {
                return BlockSourcePolicy.isProtectedTrackBlock(s);
            }
        }
        return false;
    }

    /** True if the mob is currently standing on (or in) the track rails/bed — see {@link #onTracks}. */
    private boolean onTracks() {
        Level level = mob.level();
        BlockPos feet = mob.blockPosition();
        return BlockSourcePolicy.isProtectedTrackBlock(level.getBlockState(feet.below()))
            || BlockSourcePolicy.isProtectedTrackBlock(level.getBlockState(feet));
    }

    /**
     * Highest standable surface Y at world column {@code (x, z)}, scanning down from
     * the deck floor — i.e. the block the mob would stand on beside the track. Returns
     * the mob's current Y if no solid footing is found within {@link #GROUND_SCAN_DEPTH}
     * (a void / long drop), so nav just steers horizontally rather than into the abyss.
     */
    private double groundSurfaceY(double x, double deckMinY, double z) {
        if (!(mob.level() instanceof ServerLevel level)) return deckMinY - 1.0;
        int ix = Mth.floor(x), iz = Mth.floor(z);
        int top = Mth.floor(deckMinY);
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int y = top; y >= top - GROUND_SCAN_DEPTH; y--) {
            c.set(ix, y, iz);
            if (level.getBlockState(c).isCollisionShapeFullBlock(level, c)) {
                return y + 1.0;                 // stand on top of the first solid block
            }
        }
        return mob.getY();
    }

    /** Horizontal distance from the mob to the nearest point of {@code box}. */
    private double horizontalDistToBox(AABB box) {
        double dx = Math.max(Math.max(box.minX - mob.getX(), 0.0), mob.getX() - box.maxX);
        double dz = Math.max(Math.max(box.minZ - mob.getZ(), 0.0), mob.getZ() - box.maxZ);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * True once the mob is genuinely standing on the carriage deck: inside the carriage's
     * actual horizontal footprint, at/above floor level. This is recovery's success test —
     * deliberately stricter than {@link TrainConfinement#isConfined}, which inflates the box
     * by a 1-block ride margin and so falsely reports a mob towering up <em>beside</em> the
     * deck as "aboard" (which used to end recovery at track height, before it could finish
     * climbing and drop in).
     */
    private boolean isAboard(AABB box) {
        return mob.getX() >= box.minX && mob.getX() <= box.maxX
            && mob.getZ() >= box.minZ && mob.getZ() <= box.maxZ
            && mob.getY() >= box.minY - 0.5;
    }

    /** Note forward progress so the stall-abandon timer ({@link #STALL_ABANDON_TICKS}) resets. */
    private void markProgress() {
        lastProgressTick = totalTicks;
    }

    private boolean mobGriefingOn() {
        return mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }
}
