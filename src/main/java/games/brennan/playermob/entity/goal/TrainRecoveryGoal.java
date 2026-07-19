package games.brennan.playermob.entity.goal;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.compat.TrainEnvironment;
import games.brennan.playermob.entity.BlockSourcePolicy;
import games.brennan.playermob.entity.EquipmentEvaluator;
import games.brennan.playermob.entity.ItemPickupPolicy;
import games.brennan.playermob.entity.MiningMath;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

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
     * Tower until the mob's block Y reaches {@code floor(carriage.minY) + this}, i.e. TWO blocks
     * above the deck's walking surface, before boarding. Deck-level footing leaves a walled
     * carriage's wall in the way; two clear blocks let the mob drop in through an opening
     * (flatbed / hole) from above.
     *
     * <p>Calibrated from measurement, not arithmetic: a carriage logs {@code minY = 77.99} while
     * its boarding foot Y is 79, so the walking surface is 79 and {@code floor(77.99) = 77}. The
     * previous value of 3 stopped the mob at 80 — only ONE block up — which is why it kept
     * clipping walls on the way in.</p>
     */
    private static final int TOWER_ABOVE_DECK = 4;
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
    /**
     * Mercy grant for the one softlock the mob can't dig its way out of: wedged over a drop at the
     * bed edge with a genuinely empty backpack and nothing reachable to gather. After being stuck
     * there this long (~10s), hand it {@link #RECOVERY_GRANT_PLANKS} planks — once per recovery —
     * so it can lay the single step-off block and leave the rails. Not a rescue: it still has to
     * climb back aboard on its own, before {@link #STALL_ABANDON_TICKS} (30s) abandons recovery.
     */
    private static final int NO_BLOCK_GRANT_TICKS = 200;
    /** Planks granted by the wedged-over-a-drop mercy grant — enough to bridge the step off the bed. */
    private static final int RECOVERY_GRANT_PLANKS = 5;
    /**
     * Grant delay when the mob is genuinely TRAPPED on the bed — neither side offers a safe
     * step-off, so it can neither walk away nor gather anything. Waiting the full
     * {@link #NO_BLOCK_GRANT_TICKS} achieves nothing there, so it gets its planks promptly (2s)
     * and bridges out one block.
     */
    private static final int NO_SAFE_EXIT_GRANT_TICKS = 40;
    /**
     * How long to hold beside the deck hoping an opening slides into reach before towering higher
     * (15s). Generous on purpose: a mob at deck level waiting for a gap in a long carriage is doing
     * exactly the right thing, and a short bound kicked it into a pointless GATHER mid-wait. The
     * infinite ride this originally guarded against is now prevented upstream by
     * {@link #DECK_HEIGHT_TOLERANCE} (a mob BELOW the deck climbs instead of waiting), with
     * {@link #GLOBAL_ABANDON_TICKS} as the hard backstop.
     */
    private static final int WAIT_FOR_OPENING_TICKS = 300;
    /**
     * How far below the deck floor still counts as "already up at deck height, just wait".
     * Deliberately tight: the old 1.0 tolerance mis-classified a mob a FULL block below the deck
     * as level with it, so it waited for an opening it could never reach instead of climbing.
     */
    private static final double DECK_HEIGHT_TOLERANCE = 0.25;
    /**
     * Lead the step-off block this many ticks down the line. The train carries the mob forward
     * while it steps sideways, so a block laid at the mob's CURRENT x is already behind it by the
     * time it crosses — it drifts past its own bridge and never gets off. Placing ahead by roughly
     * the travel during the step puts the block where the mob will actually be.
     */
    private static final int STEP_LEAD_TICKS = 12;
    /** Cap the lead so a speed spike can't fling the step block far down the line. */
    private static final int MAX_STEP_LEAD = 4;
    /**
     * Largest per-tick x change treated as genuine train carry. A real train moves ~0.1 blocks/tick;
     * anything beyond this is a carriage re-anchor / sub-level coordinate switch, not travel, and
     * must not poison the drift average used to lead block placement.
     */
    private static final double MAX_PLAUSIBLE_DRIFT = 1.0;
    /**
     * Ticks in BRIDGE with no placement and no height gained before concluding we are not
     * actually building and re-planning. The moving carriage can body-check the mob off its own
     * tower; the captured pillar column then never matches and bridging silently does nothing.
     */
    private static final int BRIDGE_STALL_TICKS = 60;
    /**
     * Squared horizontal distance from the captured pillar column beyond which that capture is
     * considered stale (1.5 blocks) — the mob was pushed off and must re-capture, not keep
     * waiting to land on a column it is no longer above.
     */
    private static final double PILLAR_DISPLACE_SQR = 2.25;
    /**
     * Tolerance when testing whether a cell is "inside the carriage". The carriage box carries
     * floating-point slop — its minZ sits at about -2e-7 and its floor 0.014 into the block below
     * — so a cell laid legitimately BESIDE and BELOW the deck overlapped it by a sliver and every
     * step-off placement was rejected as inside the train, trapping the mob on the bed. Anything
     * smaller than this is slop, not a real overlap.
     */
    private static final double CARRIAGE_OVERLAP_EPSILON = 0.05;
    /**
     * Horizontal distance to the carriage within which the mob is considered "beside the line" and
     * should pillar straight up in place rather than travelling any further toward it.
     */
    private static final double BESIDE_CARRIAGE_DIST = 2.0;
    /**
     * How far in from the far edge of its column the mob holds while pillaring. A 0.6-wide hitbox
     * centred at this inset sits entirely in the half of the block away from the track, clear of
     * the passing carriage that would otherwise body-check it off its own pillar.
     */
    private static final double PILLAR_BACK_INSET = 0.25;
    /**
     * Lay the step-off as a short STRIP of this many blocks along the direction of travel, all in
     * one go, rather than a single block. A 1-wide target is easy to miss: the train carries the
     * mob ~0.1 blocks/tick while it steps diagonally across, so a small timing error slid it right
     * past its own landing and it never got off. A strip gives it a landing zone it can't overshoot.
     */
    private static final int STEP_STRIP_WIDTH = 3;
    /**
     * Mercy grants allowed per recovery. Two, because the job has two distinct stages that each
     * need material and the mob can obtain none itself out here: bridging OUT off the bed, then
     * building UP to deck height. Still bounded — it is not an unlimited block supply, and
     * {@link #MAX_PLACEMENTS} caps total placement regardless.
     */
    private static final int MAX_RECOVERY_GRANTS = 2;
    /** Gather scan cube half-extent and reach. */
    private static final int GATHER_SCAN_RADIUS = 8;
    private static final double GATHER_REACH_SQR = 9.0;     // 3 blocks
    private static final int GATHER_PATH_TIMEOUT = 80;       // 4s to reach a gather block
    /** "Reach for the tool" pause after an auto tool-swap: 0.1–1.0s. */
    private static final int TOOL_SWAP_MIN_TICKS = 2;
    private static final int TOOL_SWAP_MAX_TICKS = 20;
    /** Rescan delay after the goal stops (success or abandon). */
    private static final int POST_COOLDOWN_TICKS = 40;

    private enum Phase { IDLE, APPROACH, BRIDGE, GATHER, CRAFT }

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Throttle for the per-tick off-tracks trace (ticks) — one-shot events log immediately. */
    private static final int TRACE_INTERVAL_TICKS = 10;

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
    private boolean wasOnTracks = false;           // last tick's onTracks(); resets approach trackers on transition
    private int recoveryGrantsUsed = 0;            // mercy plank grants spent this recovery (max MAX_RECOVERY_GRANTS)
    private int wedgedOnBedTicks = 0;              // ticks continuously stuck on the bed; resets only on leaving it / stepping off
    private int waitingTicks = 0;                  // consecutive ticks held beside the deck waiting for an opening
    private int bridgeStallTicks = 0;              // ticks in BRIDGE with no placement and no height gained
    private double lastBridgeY = -1.0e9;           // mob y at the last BRIDGE progress check
    private int lastBridgePlacements = -1;         // placementsUsed at the last BRIDGE progress check
    private double lastCarriageX = Double.NaN;      // previous tick's carriage minX, for measuring train speed
    private double driftPerTickX = 0.0;            // smoothed forward drift (blocks/tick) imparted by the moving train

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

    // ---- Diagnostics (gated behind PlayerMobConfig.debugSpawnLog) ----------
    // TEMPORARY: instrumentation for the "PlayerMob wedged on the track edge, never steps one
    // block off" bug. Enable with debugSpawnLog=true in playermob config. Remove once diagnosed.

    private boolean traceOn() {
        return PlayerMobConfig.debugSpawnLog();
    }

    /** Immediate diagnostic line for one-shot events (side chosen, placement, grant, phase flip). */
    private void trace(String fmt, Object... args) {
        if (!traceOn()) return;
        Object[] all = new Object[args.length + 1];
        all[0] = mob.getId();
        System.arraycopy(args, 0, all, 1, args.length);
        LOGGER.info("[TrainRecovery #{}] " + fmt, all);
    }

    /** Throttled per-tick diagnostic line (every {@link #TRACE_INTERVAL_TICKS} ticks). */
    private void traceTick(String fmt, Object... args) {
        if (!traceOn() || totalTicks % TRACE_INTERVAL_TICKS != 0) return;
        trace(fmt, args);
    }

    private static String f(double d) {
        return String.format("%.2f", d);
    }

    @Override
    public String subObjective() {
        if (phase == Phase.APPROACH && onTracks()) {
            return "leaving tracks";   // on the rails/bed → top priority is getting off the side
        }
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
        wasOnTracks = false;
        recoveryGrantsUsed = 0;
        wedgedOnBedTicks = 0;
        waitingTicks = 0;
        bridgeStallTicks = 0;
        lastBridgeY = -1.0e9;
        lastBridgePlacements = -1;
        lastCarriageX = Double.NaN;
        driftPerTickX = 0.0;
        gatherTargetPos = null;
        pillarColumn = null;
        mob.setRecovering(true);   // off the train, re-boarding is the mob's sole focus (no combat)
        moveTowardCarriage();
        trace("START pos=({},{},{}) onTracks={}", mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(), onTracks());
    }

    @Override
    public void stop() {
        trace("STOP after {} ticks (phase={} placed={} stuck={} sinceProgress={})",
                totalTicks, phase, placementsUsed, approachStuckTicks, totalTicks - lastProgressTick);
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
        AABB tbox = target.worldBox();
        // Track the TRAIN's speed down the line, from the carriage box itself rather than the mob:
        // once the mob is standing on its own static tower its personal delta is zero, but the
        // carriage is still moving and both block placement and the boarding leap must aim where
        // it WILL be. Smoothed; a re-resolve to a different carriage shows up as an implausible
        // jump and resyncs instead of poisoning the average.
        double curX = tbox.minX;
        if (!Double.isNaN(lastCarriageX)) {
            double dx = curX - lastCarriageX;
            if (Math.abs(dx) <= MAX_PLAUSIBLE_DRIFT) {
                driftPerTickX = driftPerTickX * 0.8 + dx * 0.2;
            } else {
                driftPerTickX = 0.0;   // resync rather than trust a re-anchor/target switch
            }
        }
        lastCarriageX = curX;
        traceTick("tick phase={} onTracks={} isAboard={} pos=({},{},{}) zSpan=[{}..{}] stuck={} wedged={} waiting={} placed={} granted={}",
                phase, onTracks(), isAboard(tbox),
                mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                f(tbox.minZ), f(tbox.maxZ), approachStuckTicks, wedgedOnBedTicks, waitingTicks,
                placementsUsed, recoveryGrantsUsed);
        // Top priority once OFF the tracks: if we can already board from here (high enough + an
        // opening in reach), just do it — don't tower or gather when a hop will do. Never board
        // while standing on the rails/bed: the bed is static and the carriage is moving, so a leap
        // from the bed can't land the mob aboard — it stages one block off the line first (see
        // tickApproach / placeOffBedStep). Gating here also skips the per-tick carriage scan while
        // on the bed, and lets the onTracks() handoff below run instead of being pre-empted.
        if (!onTracks() && tryBoardNow(target.worldBox())) {
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
        boolean on = onTracks();
        if (on != wasOnTracks) {       // crossed the bed boundary → reset the approach progress trackers
            lastApproachDist = Double.MAX_VALUE;
            lastApproachY = -1.0e9;
            approachStuckTicks = 0;
            if (!on) wedgedOnBedTicks = 0;   // genuinely left the bed → clear the wedged-dwell clock
            wasOnTracks = on;
        }
        // #1 PRIORITY while on the rails/bed: get off the SIDE of the tracks. Nothing else — tower,
        // gather, wait, board — runs until the mob is beside the line, because it can't board the
        // moving carriage from the static bed. Only once off does the normal approach/tower resume.
        if (on) {
            tickGetOffTracks(box);
            return;
        }
        clearClimbObstruction(box);   // clear foliage in the way as it walks up to the spot
        // Already up at deck height and right beside the carriage (fell onto a tree / high ground)?
        // Don't climb back DOWN to the ground launch spot to gather — hold here and let an opening
        // slide into reach; tryBoardNow (top of tick) leaps the moment one does. (≤2.5 keeps us
        // inside boarding reach so a hop can land.)
        // Beside the carriage AND high enough that a leap is a drop-in → hold and let an opening
        // slide into reach (tryBoardNow leaps the moment one does). Bounded: if none comes within
        // WAIT_FOR_OPENING_TICKS, tower higher instead. Gated on readyToBoard, NOT merely on being
        // at deck level: waiting level with the deck just parks the mob beside a wall it can never
        // get over, which is how it ended up hopping into the carriage flank forever.
        boolean adjacent = horizontalDistToBox(box) <= 2.5;
        if (adjacent && readyToBoard(box)) {
            waitingTicks++;
            traceTick("approach WAIT-FOR-OPENING mobY={} deckMinY={} horizDist={} waiting={}/{}",
                    f(mob.getY()), f(box.minY), f(horizontalDistToBox(box)),
                    waitingTicks, WAIT_FOR_OPENING_TICKS);
            if (waitingTicks <= WAIT_FOR_OPENING_TICKS) {
                waitForOpening(box);
                return;
            }
            // Still no opening: tower a little higher (maybe a wall is in the way) rather than
            // going off to gather — we're in position, we just need to clear the lip.
            trace("approach WAIT timed out after {} ticks → tower higher", waitingTicks);
            waitingTicks = 0;
            commitToBuildUp();
            return;
        }
        waitingTicks = 0;   // not holding station → the wait clock only counts consecutive ticks
        // Hard up against the carriage but not yet high enough to drop in — either still below the
        // deck (standing on the step block we bridged out onto) or level with it but short of the
        // planned tower height. Either way there is no better ground to walk to (approachPoint
        // would send us off toward the canyon floor), so BUILD UP from here.
        if (adjacent) {
            traceTick("approach adjacent-not-high-enough → BUILD UP (mobY={} deckMinY={} horizDist={} blocks={})",
                    f(mob.getY()), f(box.minY), f(horizontalDistToBox(box)),
                    BlockSourcePolicy.bridgeBlockCount(mob.getInventory()));
            commitToBuildUp();
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
        traceTick("approach pos=({},{},{}) spot=({},{},{}) distToSpot={} lastDist={} mobY={} lastY={} deckMinY={} horizDist={} stuck={}/{} -> commit={}",
                mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                f(spot.x), f(spot.y), f(spot.z), f(distToSpot), f(lastApproachDist),
                f(mob.getY()), f(lastApproachY), f(box.minY), f(horizontalDistToBox(box)),
                approachStuckTicks, APPROACH_STUCK_LIMIT,
                (distToSpot <= 1.6 || approachStuckTicks > APPROACH_STUCK_LIMIT));
        lastApproachDist = distToSpot;
        lastApproachY = mob.getY();
        // At the (re-assessed) best spot, or can't get closer → commit (we're already off the
        // line; on-tracks is handled by the early tickGetOffTracks return above). Gather first if short.
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
    /**
     * Commit to towering from where we stand (beside the deck, over a drop). Unlike
     * {@link #commitFromApproach} this does NOT demand {@link #blocksNeeded()} up front: out here
     * there is nothing to gather, so insisting on a full climb-plus-buffer just sent a mob that
     * already held usable blocks off on a futile GATHER wander while the train left. Tower with
     * whatever we have; {@link #tickBridge} falls back to CRAFT/GATHER (and the mercy grant) if it
     * genuinely runs dry partway up.
     */
    /**
     * True when the mob is already in position to board: at/above deck level and hard against the
     * carriage. In that state it needs an OPENING, not more blocks — anything that sends it off to
     * gather from here is wasted time it does not have while the train keeps moving.
     */
    /**
     * True if {@code pos} genuinely lies within the carriage, ignoring the sub-centimetre slop in
     * the carriage box's bounds (see {@link #CARRIAGE_OVERLAP_EPSILON}). Used to keep the mob from
     * building into the train without rejecting the legitimate cell just beside/below the deck.
     */
    private boolean overlapsCarriage(BlockPos pos) {
        return target != null
            && target.worldBox().deflate(CARRIAGE_OVERLAP_EPSILON).intersects(new AABB(pos));
    }

    /**
     * True only when the mob is high enough that a leap is a DROP-IN rather than a sideways shove
     * at the carriage's flank. Keyed on height, not phase: a mob standing at deck level finds a
     * "reachable" column one step inside the footprint every single tick, leaps at it, bounces off
     * the wall and lands back where it started — a loop that never boards and, because the attempt
     * short-circuits the tick, never lets it tower any higher either.
     *
     * <p>So: board once the planned tower height is reached ({@link #TOWER_ABOVE_DECK}, two blocks
     * over the deck), or — if we are out of material and cannot climb further — from whatever
     * height we did reach, provided it is at least deck level.</p>
     */
    private boolean readyToBoard(AABB box) {
        if (mob.blockPosition().getY() >= Mth.floor(box.minY) + TOWER_ABOVE_DECK) {
            return true;
        }
        return BlockSourcePolicy.bridgeBlockCount(mob.getInventory()) <= 0
            && mob.getY() >= box.minY - DECK_HEIGHT_TOLERANCE;
    }

    private boolean atBoardingHeight(AABB box) {
        return mob.getY() >= box.minY - DECK_HEIGHT_TOLERANCE && horizontalDistToBox(box) <= 2.5;
    }

    private void commitToBuildUp() {
        phaseTicks = 0;
        approachStuckTicks = 0;
        int have = BlockSourcePolicy.bridgeBlockCount(mob.getInventory());
        if (have <= 0) {
            grantRecoveryPlanks("build up beside deck with empty backpack");
            have = BlockSourcePolicy.bridgeBlockCount(mob.getInventory());
        }
        if (have > 0) {
            phase = Phase.BRIDGE;
        } else {
            phase = Phase.GATHER;
            gatherTargetPos = null;
        }
    }

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

    /**
     * #1 priority while standing on the rails/bed: leave the tracks by the nearest workable SIDE.
     * The mob can't board the moving carriage from the static bed, so before any tower/gather/board
     * it beelines straight off the side of the line. The train runs along world-X, so "off the
     * side" is a step in Z; the bed spans the carriage width, so the first off-bed column on a side
     * is just past that carriage face.
     *
     * <p>It evaluates both sides and prefers the one it can simply step onto (footing within a
     * 1-up/3-down step) — so a track cut along a cliff exits onto the ground, not into the drop.
     * If only one side is walkable it takes that; if both (flat terrain) or neither (elevated on
     * both sides) it takes the nearer. On a ground-level line it just walks off; wedged at the edge
     * over a drop, {@link #placeOffBedStep} lays one step block to cross. Stepping off flips
     * {@link #onTracks()} false, and the normal off-track APPROACH→tower→board resumes next tick.</p>
     */
    private void tickGetOffTracks(AABB box) {
        wedgedOnBedTicks++;   // reliable "stuck on the bed" clock — reset only on leaving it (tickApproach) or stepping off
        int mx = mob.blockPosition().getX();
        int mz = mob.blockPosition().getZ();
        int footY = mob.blockPosition().getY();
        // Off-bed columns sit just past the carriage's Z faces — the bed spans the carriage width,
        // so this is robust no matter how wide the bed is or where on it the mob stands. (Stepping
        // out from the mob with a small cap missed the far edge of a full-width bed: a mob near the
        // centre never reached a genuinely off-bed column and parked on the rails — issue #49.)
        int northOff = offBedColumn(box, -1);                // column just past the −Z face (off the footprint)
        int southOff = offBedColumn(box, +1);                // …and just past the +Z face
        boolean nWalk = stepFooting(mx, box.minY, northOff, footY);
        boolean sWalk = stepFooting(mx, box.minY, southOff, footY);
        int offZ;
        if (nWalk != sWalk) {
            offZ = nWalk ? northOff : southOff;               // exactly one side is a clean step-off → take it
        } else {
            boolean nearNorth = Math.abs(mob.getZ() - box.minZ) <= Math.abs(mob.getZ() - box.maxZ);
            offZ = nearNorth ? northOff : southOff;           // both or neither → nearer side
        }
        int stepZ = offZ <= mz ? -1 : 1;
        boolean noSafeExit = !nWalk && !sWalk;
        // When neither side is walkable we are BRIDGING out, not stepping down: the step block is
        // laid level with the bed, so aim at our own foot height. Aiming at groundSurfaceY there
        // pointed navigation at the canyon floor ~20 blocks below — an unreachable target, so the
        // mob never walked onto the block it had just placed and re-placed forever.
        double targetY = noSafeExit ? footY : groundSurfaceY(mx, box.minY, offZ);
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(mx + 0.5, targetY, offZ + 0.5, moveSpeed);
            mob.getLookControl().setLookAt(mx + 0.5, targetY, offZ + 0.5);
        }
        // Progress = closing the perpendicular gap to the off-side column.
        double dist = Math.abs(mob.getZ() - (offZ + 0.5));
        if (dist < lastApproachDist - 0.05) {
            approachStuckTicks = 0;
            markProgress();
        } else {
            approachStuckTicks++;
        }
        lastApproachDist = dist;
        traceTick("getOff pos=({},{},{}) zSpan=[{}..{}] northOff={}(walk={}) southOff={}(walk={}) -> offZ={} stepZ={} targetY={} dist={} stuck={}/{} navDone={}",
                mx, footY, mz, f(box.minZ), f(box.maxZ),
                northOff, nWalk, southOff, sWalk, offZ, stepZ, f(targetY),
                f(dist), approachStuckTicks, APPROACH_STUCK_LIMIT, mob.getNavigation().isDone());
        // No safe step-off on EITHER side (elevated line, drop both ways) → don't flail waiting to
        // get stuck first; bridge straight out one block. Otherwise only bridge once genuinely
        // wedged at the edge.
        if (noSafeExit || approachStuckTicks > APPROACH_STUCK_LIMIT) {
            placeOffBedStep(box, stepZ, noSafeExit);
        }
    }

    /**
     * Lay a single step-off block so the mob can leave the bed when the chosen side is a drop/void
     * (an elevated track has air beside it, which vanilla nav won't path into). Places one block in
     * the off-bed cell one step in {@code stepZ}, level with the bed surface, so the mob steps
     * across onto it. Acts only when that next cell is actually off the bed — if it's still bed (the
     * mob hasn't reached the edge) it keeps walking. Reuses the {@code mobGriefing}-gated,
     * carriage-/track-protected {@link #tryPlaceBridgeBlock}; with no placeable block it can't bridge
     * a void, so it leaves it to the stall backstop (best-effort).
     */
    private void placeOffBedStep(AABB box, int stepZ, boolean noSafeExit) {
        BlockPos foot = mob.blockPosition();
        int placeZ = foot.getZ() + stepZ;
        double placeCenterZ = placeZ + 0.5;
        if (placeCenterZ >= box.minZ && placeCenterZ <= box.maxZ) {
            traceTick("offBedStep SKIP nextCell-still-in-footprint foot=({},{},{}) stepZ={} placeZ={} placeCenterZ={} zSpan=[{}..{}]",
                    foot.getX(), foot.getY(), foot.getZ(), stepZ, placeZ, f(placeCenterZ), f(box.minZ), f(box.maxZ));
            return;                              // next cell still inside the track footprint — keep walking
        }
        int slot = bridgeBlockSlot();
        int bridgeBlocks = BlockSourcePolicy.bridgeBlockCount(mob.getInventory());
        traceTick("offBedStep foot=({},{},{}) stepZ={} placeZ={} slot={} bridgeBlocks={} placementsUsed={} phaseTicks%{}={}",
                foot.getX(), foot.getY(), foot.getZ(), stepZ, placeZ, slot, bridgeBlocks,
                placementsUsed, PLACE_INTERVAL_TICKS, phaseTicks % PLACE_INTERVAL_TICKS);
        if (slot < 0) {
            // Nothing placeable. If the mob has been wedged here ~10s with a genuinely empty
            // backpack (no blocks AND no logs to craft), hand it planks once so it can bridge the
            // single step off the bed — the one softlock it can't dig its way out of. Otherwise
            // leave it to the stall backstop (best-effort). See NO_BLOCK_GRANT_TICKS.
            // Trapped (no safe exit either side) → grant promptly; it can neither walk away nor
            // gather on the bed, so a longer wait accomplishes nothing.
            int grantAfter = noSafeExit ? NO_SAFE_EXIT_GRANT_TICKS : NO_BLOCK_GRANT_TICKS;
            if (wedgedOnBedTicks > grantAfter) {
                grantRecoveryPlanks("wedged on bed " + wedgedOnBedTicks + " ticks, noSafeExit=" + noSafeExit);
            }
            return;
        }
        if (phaseTicks % PLACE_INTERVAL_TICKS != 0) {
            return;                              // pacing placement
        }
        // One below the mob's feet (so the top is level with the bed for a flat step), and led
        // forward by the train's carry so the mob doesn't drift past its own bridge mid-step.
        int lead = Mth.clamp((int) Math.round(driftPerTickX * STEP_LEAD_TICKS), -MAX_STEP_LEAD, MAX_STEP_LEAD);
        BlockPos step = new BlockPos(foot.getX() + lead, foot.getY() - 1, placeZ);
        lookAt(step);
        // Lay the whole strip THIS tick (not one per PLACE_INTERVAL): laying them ten ticks apart
        // just trails blocks behind the drifting mob, which is what it kept missing.
        int laid = 0;
        for (int i = 0; i < STEP_STRIP_WIDTH; i++) {
            if (placementsUsed >= MAX_PLACEMENTS) break;
            int s = bridgeBlockSlot();
            if (s < 0) break;                                  // out of material — strip is as wide as it gets
            BlockPos p = new BlockPos(step.getX() + i, step.getY(), placeZ);
            if (tryPlaceBridgeBlock(p, s)) {
                placementsUsed++;
                laid++;
            }
        }
        trace("offBedStep STRIP laid={} from {} (width={} drift={}/tick lead={})",
                laid, step, STEP_STRIP_WIDTH, f(driftPerTickX), lead);
        if (laid > 0) {
            approachStuckTicks = 0;
            wedgedOnBedTicks = 0;            // stepped off — clear the wedged-dwell clock
            markProgress();
            // Aim at the near end of the strip; the mob drifts along it rather than past it.
            mob.getNavigation().moveTo(step.getX() + 0.5, step.getY() + 1.0, step.getZ() + 0.5, moveSpeed);
        }
    }

    /**
     * The column just outside the carriage's Z footprint on the near ({@code dir<0}) or far
     * ({@code dir>0}) side. Position-based to match {@link #onTracks()}: a mob standing here has its
     * Z outside the track span, i.e. off the tracks. The track is the carriage width, so one cell
     * past the face is off it regardless of what the bed is built from.
     */
    private int offBedColumn(AABB box, int dir) {
        // Round to the nearest integer face, NOT floor/ceil: a carriage box can carry a sub-block
        // epsilon (minZ logs as -0.00), and floor(-0.0001)-1 = -2 aims the mob two cells into the
        // void instead of the one cell just past the face. Math.round is robust to ±epsilon on
        // either face — north = first cell below minZ, south = first cell at/above maxZ.
        return dir < 0 ? (int) Math.round(box.minZ) - 1 : (int) Math.round(box.maxZ);
    }

    /** True if off-bed column {@code (x,z)} has footing the mob can step to without bridging (≤1 up, ≤3 down). */
    private boolean stepFooting(int x, double deckMinY, int z, int footY) {
        double y = offBedFootingY(x, deckMinY, z);
        return y != Double.NEGATIVE_INFINITY && y <= footY + 1.0 && y >= footY - 3.0;
    }

    // ---- BRIDGE -----------------------------------------------------------

    private void tickBridge() {
        AABB box = target.worldBox();
        // Displacement guard. The moving carriage can body-check the mob off its own tower; the
        // captured pillar column then never matches and bridging does nothing at all, silently,
        // until the 30s backstop. If we're neither gaining height nor placing, re-plan.
        if (mob.getY() > lastBridgeY + 0.05 || placementsUsed != lastBridgePlacements) {
            bridgeStallTicks = 0;
            lastBridgeY = mob.getY();
            lastBridgePlacements = placementsUsed;
        } else if (++bridgeStallTicks > BRIDGE_STALL_TICKS) {
            trace("bridge STALLED {} ticks (pushed off tower?) → re-approach", bridgeStallTicks);
            bridgeStallTicks = 0;
            pillarColumn = null;
            phase = Phase.APPROACH;
            phaseTicks = 0;
            lastApproachDist = Double.MAX_VALUE;
            lastApproachY = -1.0e9;
            approachStuckTicks = 0;
            return;
        }
        clearClimbObstruction(box);   // punch through any leaves/plants blocking the way up
        // No top-level navigation here: stairs steer onto each placed step, and jump-stack
        // pillars straight up with nav OFF (otherwise the mob walks off its own 1-wide tower).
        int slot = bridgeBlockSlot();              // non-log placeable; non-gravity preferred
        if (slot < 0) {
            // Out of blocks, but the tower already reached boarding height → we don't need more
            // material, we need an opening. Hand back to APPROACH so it holds position and takes
            // the first opening that slides past, instead of wandering off to gather.
            if (atBoardingHeight(box)) {
                trace("bridge out-of-blocks but AT boarding height → hold for opening");
                phase = Phase.APPROACH;
                phaseTicks = 0;
                return;
            }
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
        // Already standing beside the line (we bridged out one block): STOP MOVING and stack
        // straight up under ourselves — jump, place into the vacated foot space, repeat. Building
        // a staircase from here would walk the mob back and forth beside a moving train instead of
        // gaining height. Stairs remain for the approach from open ground further out.
        if (atBuildUpColumn(box) || BlockSourcePolicy.isGravityBlock(block)) {
            tickJumpStack(slot, box);
        } else {
            tickStairs(slot, box);
        }
    }

    /**
     * True once the mob is on its step block immediately beside the carriage — the point where it
     * should stop travelling and pillar straight up in place.
     */
    /**
     * Z to hold while pillaring: inset from the far edge of the mob's CURRENT column, on the side
     * away from the carriage. Same block, back of it — so the pillar keeps rising in one place
     * while the mob's hitbox sits clear of the passing train instead of being shoved off.
     */
    private double pillarHoldZ(AABB box) {
        int bz = mob.blockPosition().getZ();
        boolean farSide = mob.getZ() > (box.minZ + box.maxZ) * 0.5;
        return farSide ? bz + 1.0 - PILLAR_BACK_INSET : bz + PILLAR_BACK_INSET;
    }

    private boolean atBuildUpColumn(AABB box) {
        return horizontalDistToBox(box) <= BESIDE_CARRIAGE_DIST;
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
        if (overlapsCarriage(pos)) return false;
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
            // Tower is tall enough: hold station and take the first clear opening.
            traceTick("jumpStack TOWER TOP mobBlockY={} deckMinY={} threshold={} blocks={} → wait for opening",
                    mob.blockPosition().getY(), f(box.minY),
                    Mth.floor(box.minY) + TOWER_ABOVE_DECK,
                    BlockSourcePolicy.bridgeBlockCount(mob.getInventory()));
            waitForOpening(box);                       // tryBoardNow leaps when an opening is in reach
            return;
        }
        // Pillar straight up: nav OFF so no horizontal path-following walks the mob off its
        // own 1-wide tower, and no drift along the line EVERY tick so it rises and lands back
        // on the same column (no leftover approach momentum carries it off).
        //
        // Hold station at the BACK of that column rather than its centre. Standing centred keeps
        // the mob inside the passing carriage's swept volume, and the train body-checks it clean
        // off its own pillar part-way up. Easing toward the far edge keeps the same XZ column
        // (the pillar is still built here) while putting the hitbox out of the train's path.
        mob.getNavigation().stop();
        Vec3 dm = mob.getDeltaMovement();
        double holdZ = pillarHoldZ(box);
        double vz = Mth.clamp((holdZ - mob.getZ()) * 0.3, -0.08, 0.08);
        mob.setDeltaMovement(0.0, dm.y, vz);
        // Drop a captured column we've been shoved away from: while airborne the apex test below
        // can never match a column we're no longer above, and the onGround re-capture never runs,
        // so jump-stacking would deadlock doing nothing until the stall backstop.
        if (pillarColumn != null) {
            double px = mob.getX() - (pillarColumn.getX() + 0.5);
            double pz = mob.getZ() - (pillarColumn.getZ() + 0.5);
            if (px * px + pz * pz > PILLAR_DISPLACE_SQR) {
                trace("jumpStack pillar {} stale (displaced) → recapture", pillarColumn);
                pillarColumn = null;
            }
        }
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
        if (!readyToBoard(box)) {
            return false;
        }
        Vec3 spot = TrainConfinement.boardingSpot(mob, box);
        if (spot == null) {
            return false;
        }
        // Aim at the spot EXACTLY as validated. Do not lead it: boardingSpot checked that a
        // specific carriage column has a floor and clear headroom at its position right now, and
        // that check is only meaningful for that position. Offsetting the aim by a predicted
        // travel distance points the mob at a column nothing has verified — which lands it in a
        // carriage gap or off an open edge and it falls straight through. The spot is re-resolved
        // every tick, so the target tracks the moving carriage without any prediction.
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(spot.x, spot.y, spot.z);
        mob.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, moveSpeed);
        if (mob.onGround()) mob.getJumpControl().jump();
        trace("BOARD leap spot=({},{},{}) mobY={} drift={}/tick phase={}",
                f(spot.x), f(spot.y), f(spot.z), f(mob.getY()), f(driftPerTickX), phase);
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
        // Waiting HERE counts as progress: entry is gated on being genuinely at deck level and
        // against the carriage (DECK_HEIGHT_TOLERANCE), so the mob is correctly positioned and
        // boarding the moment an opening passes — the 30s stall-abandon must not cut that short.
        // The original infinite-ride bug was a mob a full block BELOW the deck reaching this
        // branch; that can no longer happen. GLOBAL_ABANDON_TICKS (5 min) remains the hard cap,
        // and it is NOT reset by markProgress.
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
        if (!mobGriefingOn()) { trace("place REJECT mobGriefing-off @{}", pos); return false; }
        ItemStack stack = mob.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) { trace("place REJECT slot-not-block @{} slot={}", pos, slot); return false; }

        BlockState existing = level.getBlockState(pos);
        if (!existing.canBeReplaced()) { trace("place REJECT not-replaceable @{} existing={}", pos, existing.getBlock()); return false; }
        if (BlockSourcePolicy.isProtectedTrackBlock(existing)) { trace("place REJECT protected-track @{} existing={}", pos, existing.getBlock()); return false; }
        AABB cell = new AABB(pos);
        if (overlapsCarriage(pos)) { trace("place REJECT intersects-carriage @{} carriage={}", pos, target.worldBox()); return false; }   // never the carriage box
        if (!level.getEntities(mob, cell).isEmpty()) { trace("place REJECT cell-occupied @{}", pos); return false; }                 // don't suffocate anything

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
                // Nothing more reachable — bridge with whatever we've collected. With an empty
                // backpack and nothing gatherable (beside a moving deck, over a drop) the mob
                // can't climb the last block aboard, so grant it planks once rather than abandon.
                if (BlockSourcePolicy.bridgeBlockCount(mob.getInventory()) > 0
                        || grantRecoveryPlanks("nothing gatherable, empty backpack")) {
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
                if (!leftover.isEmpty()) mob.dropAtLocation(leftover);
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
        return MiningMath.breakTicks(state, mob.level(), gatherTargetPos, mob.getMainHandItem());
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
        if (!leftover.isEmpty()) mob.dropAtLocation(leftover);
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
            // First block with real footing — full blocks, slabs and stairs all count. Rails / air
            // / plants have no collision, so they're skipped down to the surface beneath them
            // (isCollisionShapeFullBlock would wrongly skip a slab/stairs track piece too).
            if (!s.getCollisionShape(level, c).isEmpty()) {
                return BlockSourcePolicy.isProtectedTrackBlock(s);
            }
        }
        return false;
    }

    /**
     * True if the mob is over the track. <b>Position-based, not block-type:</b> the track is
     * X-axis aligned and exactly as wide in Z as the carriage that rides it (and runs the whole
     * line along X), so a mob whose feet sit within that Z-span is on the bed regardless of what
     * block it's painted with — plain stone brick, a stone-brick variant, cobblestone slab, etc.
     * (A block-type check missed those and left the mob "approaching" on the rails — #49.)
     * "Getting off the side" then simply means moving the mob's Z outside the carriage's Z-span.
     */
    private boolean onTracks() {
        if (target != null) {
            AABB box = target.worldBox();
            return mob.getZ() >= box.minZ && mob.getZ() <= box.maxZ;
        }
        // Fallback only when no carriage is resolved (goal inactive): literal track block underfoot.
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
        double y = offBedFootingY(Mth.floor(x), deckMinY, Mth.floor(z));
        return y == Double.NEGATIVE_INFINITY ? mob.getY() : y;
    }

    /**
     * Standable surface Y (top of the first block with a non-empty collision shape — full block,
     * slab or stairs; rails/air/plants are skipped) at column {@code (x, z)}, scanning down from the
     * deck floor, or {@link Double#NEGATIVE_INFINITY} if none within {@link #GROUND_SCAN_DEPTH}
     * (a void / long drop). The explicit void sentinel lets callers tell "no footing" from a real
     * surface that happens to be at the mob's Y.
     */
    private double offBedFootingY(int x, double deckMinY, int z) {
        if (!(mob.level() instanceof ServerLevel level)) return Double.NEGATIVE_INFINITY;
        int top = Mth.floor(deckMinY);
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int y = top; y >= top - GROUND_SCAN_DEPTH; y--) {
            c.set(x, y, z);
            if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) {
                return y + 1.0;                 // stand on top of the first standable block
            }
        }
        return Double.NEGATIVE_INFINITY;
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

    /**
     * Mercy grant: hand the mob {@link #RECOVERY_GRANT_PLANKS} planks, once per recovery, when it
     * is provably unable to obtain a block itself — trapped on the bed with no safe step-off, or
     * beside the deck with nothing reachable to gather. Without a block it can neither bridge out
     * nor climb the last block aboard, and would ride the line forever. Returns true if granted.
     */
    private boolean grantRecoveryPlanks(String why) {
        if (recoveryGrantsUsed >= MAX_RECOVERY_GRANTS) return false;
        if (BlockSourcePolicy.bridgeBlockCount(mob.getInventory()) > 0) return false;
        EquipmentEvaluator.addToContainer(
                mob.getInventory(), new ItemStack(Items.OAK_PLANKS, RECOVERY_GRANT_PLANKS));
        recoveryGrantsUsed++;
        markProgress();          // fresh work window; the planks are placed from next tick
        trace("GRANT {} planks #{}/{} ({})", RECOVERY_GRANT_PLANKS, recoveryGrantsUsed, MAX_RECOVERY_GRANTS, why);
        return true;
    }

    /** Note forward progress so the stall-abandon timer ({@link #STALL_ABANDON_TICKS}) resets. */
    private void markProgress() {
        lastProgressTick = totalTicks;
    }

    private boolean mobGriefingOn() {
        return GameRuleCompat.mobGriefing(mob.level());
    }
}
