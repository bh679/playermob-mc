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
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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
 * SWIM_TO_SHORE → APPROACH → CLIMB / BRIDGE → GATHER → CRAFT. Registered at goalSelector
 * priority 1 so it preempts combat (@2): falling off is existential. It still yields to
 * {@link net.minecraft.world.entity.ai.goal.FloatGoal} (@0) so a mob bridging
 * over water swims instead of drowning. All world mutation (place + gather) is
 * gated on {@code mobGriefing}, matching the raid/harvest goals.</p>
 *
 * <p>A mob that falls in the drink starts in SWIM_TO_SHORE, which makes for the nearest
 * dry bank (preferring banks beside the track) and hands off to APPROACH once it has
 * climbed out — every later phase assumes footing, so none of them work from the water.
 * A mob with no shore in range is abandoned by the stall backstop like any other
 * hopeless case.</p>
 *
 * <p>APPROACH prefers CLIMB over BRIDGE whenever an existing way up is in reach: DT stamps
 * spiral staircases (slabs + stairs) and ladder columns beside elevated track, topping out at
 * carriage-floor level, and walking one is both faster and cheaper than paving a new tower.
 * Free route beats building; building beats gathering.</p>
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
     * Tower until the mob's block Y reaches {@code floor(carriage.minY) + this} — TWO blocks above
     * the TRACK BED, level with the deck's walking surface. That is enough to get aboard; building
     * any higher just burns blocks and time while the train pulls away.
     *
     * <p>Mind the reference point: this counts blocks above the RAILS, not above the deck. A
     * carriage logs {@code minY = 77.99}, so {@code floor(...) = 77} — the height a mob stands at
     * when it lands on the track bed — while the deck walking surface is 79. A value of 4 therefore
     * towered the mob to 81, four blocks above the tracks and needlessly tall.</p>
     */
    private static final int TOWER_ABOVE_DECK = 2;
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
     * How long to hold hoping an opening slides into reach before raising the tower target (5s).
     * Deliberately short now that timing out CLIMBS rather than wandering off to gather: when the
     * mob is too low to see any landing at all, every tick spent waiting is ground lost to a train
     * that never stops. Long enough that a genuine gap in a passing carriage still gets taken.
     * The infinite ride this originally guarded against is prevented upstream by
     * {@link #DECK_HEIGHT_TOLERANCE}, with {@link #GLOBAL_ABANDON_TICKS} as the hard backstop.
     */
    private static final int WAIT_FOR_OPENING_TICKS = 100;
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
     * Once a boarding leap starts, keep steering at the opening for this many ticks even though the
     * mob is airborne (and so no longer "ready" by the standing-height test). Without it the mob
     * abandons its own jump the tick it leaves the ground.
     */
    private static final int BOARD_COMMIT_TICKS = 12;
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
    /**
     * Half-extent of the dry-land search around a swimming mob. 12 is comfortably wider than the
     * lakes and rivers the track cuts through, while keeping the scan (a column sweep over this
     * disc) cheap enough to run off the tick loop at {@link #SHORE_RESCAN_TICKS}.
     */
    /**
     * Width of each ring in the expanding shore search, and how far out it will go before giving up.
     *
     * <p>The search used to be a single 12-block disc, and any lake or river wider
     * than about 24 blocks — which is most of the water the line crosses — returned nothing at all.
     * The mob then fell through to the no-shore fallback and just swam <em>a direction</em>, with no
     * destination, which is exactly what it looked like.</p>
     *
     * <p>Scanning ring by ring and stopping at the first one containing land is both the literal
     * definition of "nearest shore" and CHEAPER than the old disc in the common case: land 5 blocks
     * away is found in the first ring (~200 columns) instead of always sweeping 452.</p>
     */
    private static final int SHORE_RING_STEP = 8;
    private static final int SHORE_RING_MAX = 48;
    /**
     * Vertical band of the shore search, measured from the <b>water surface</b> — not the mob. A
     * bank you can climb out onto sits at the waterline by definition, so that is the only sensible
     * anchor.
     *
     * <p>This used to be relative to the mob's feet, which broke precisely when the mob was deepest
     * in trouble: swimming down in deep water put the entire band underwater, every column hit fluid
     * before any footing, and the search returned NOTHING — so the mob fell back to paddling toward
     * the track line and never got out at all. In mid-depth water the band straddled the surface, so
     * the only columns that qualified were ones whose land reached into its upper part: the search
     * literally could not see a low, close bank and picked a tall distant one instead.</p>
     */
    private static final int SHORE_SCAN_UP = 4;
    private static final int SHORE_SCAN_DOWN = 3;
    /** How far up to look for the water surface above a submerged mob before giving up. */
    private static final int SHORE_SURFACE_SCAN = 32;
    /**
     * Cost per block a bank rises above the waterline. Climbing out of water onto a 1-block step is
     * easy; hauling up a 4-block cliff face is slow and often impossible, so a low bank is worth
     * real extra swimming. This is what stops it making for a "too tall" shoreline.
     */
    private static final double SHORE_RISE_BIAS = 1.5;
    /**
     * Consecutive ticks of footing before the swim is considered finished. Hysteresis, because
     * {@code onGround()} flickers as a swimmer scrapes a submerged ledge — but see isAshore: the
     * test can NOT also demand being out of the water, or a mob wading the shallows at the shore
     * edge (on ground AND in water) matches neither afloat nor ashore and swims on the spot forever.
     */
    private static final int SHORE_GROUNDED_TICKS = 5;
    /**
     * A recovery that ended less than this ago counts as the same attempt for the purposes of
     * {@link #MAX_SWIM_REENTRIES}. Without it the re-entry bound is trivially defeated: the goal
     * stops, waits out POST_COOLDOWN_TICKS, starts again with the counter reset, and the mob resumes
     * exactly the same in-and-out loop indefinitely.
     */
    private static final int SWIM_ATTEMPT_LINK_TICKS = 200;
    /**
     * Rescan cadence (1s). {@link #requiresUpdateEveryTick()} is true and the shore sweep is
     * hundreds of block lookups, so it must NOT run per tick — but the train slides on and the mob
     * drifts, so a one-shot scan at phase entry goes stale. Once a second is imperceptible at swim
     * speed and keeps the goal off the hot path.
     */
    private static final int SHORE_RESCAN_TICKS = 20;
    /**
     * How hard to pull the shore choice toward the track line, in blocks-of-equivalent-distance.
     * <b>Deliberately small: distance dominates, track proximity only breaks near-ties.</b>
     *
     * <p>Getting out of the water is the urgent part; being near the rails is a convenience for the
     * phase that follows. At 1.0 the two were co-equal, and that loses badly in the exact place this
     * matters — where the line CROSSES water. There the nearest banks are perpendicular to the rails
     * (large gap) while the lake runs long in the track direction (gap ~0), so an equal-weight score
     * paid the mob to swim 15 blocks along the track instead of 5 blocks to the obvious bank beside
     * it. At 0.25 a bank has to be roughly four times further away before the track term can
     * outvote it.</p>
     */
    private static final double SHORE_TRACK_BIAS = 0.25;
    /** Cells of clear, water-free air a standing spot needs above its footing (the mob is 2 tall). */
    private static final int SHORE_HEADROOM = 2;
    /**
     * Navigation speed multiplier while swimming. <b>This is the other half of "swims too slowly",
     * and it is the bigger half</b> — sprinting alone only doubled 0.03 to 0.06 blocks/tick when a
     * player does 0.20.
     *
     * <p>In water {@code LivingEntity.travel} accelerates by a FLAT {@code 0.02 * inputVector} — the
     * movement-speed attribute is bypassed entirely (it only re-enters via WATER_MOVEMENT_EFFICIENCY,
     * which is 0 here). What differs is the input magnitude: a player holds full stick, so its
     * {@code zza} is <b>1.0</b>, while {@code Mob.setSpeed} sets {@code zza} to
     * {@code speedModifier x MOVEMENT_SPEED} = <b>0.30</b>. Same drag, same accel constant, but the
     * mob feeds a third of the input — hence roughly a third of the speed.
     *
     * <p>4.0 x 0.30 = 1.2, and {@code Entity.getInputVector} normalises anything over length 1, so
     * this clamps to exactly the player's 1.0 rather than overshooting. It is applied ONLY to the
     * swim navigation calls: on land the same multiplier would be a real 4x sprint, since the ground
     * branch of {@code travel} does scale by the speed attribute. {@link #enterApproach()} stops
     * navigation on the way out so the boosted modifier can't outlive the water.</p>
     */
    private static final double SWIM_NAV_BOOST = 4.0;
    /**
     * Small surcharge for a bank whose Z sits INSIDE the carriage's Z-span — climbing out there puts
     * the mob on the track bed, and {@code tickGetOffTracks} then has to sidestep it off before it can
     * do anything. 4.0 is roughly what that detour is worth.
     *
     * <p>This was 64, which — being larger than the whole scan radius — acted as a veto
     * rather than a nudge: a bank 2 blocks away beside the rails scored 66 against 17 for one 11
     * blocks away, so the mob swam right past the obvious shore. That over-correction was aimed at an
     * oscillation whose actual cause (stepping off the bed straight back into water) is now fixed at
     * source in {@code tickGetOffTracks}, so the surcharge only has to price the sidestep.</p>
     */
    private static final double ON_TRACK_SHORE_PENALTY = 2.0;
    /**
     * How many of the best-scoring shore candidates to check for reachability before settling.
     * A bank the mob cannot actually path to — the far side of a ravine, the top of a cliff face —
     * is worse than useless: it swims all the way over and then can't get out. Bounded because each
     * check is a full A*.
     */
    private static final int SHORE_REACH_CHECKS = 4;
    /**
     * How many times one recovery may fall back in the water before we give up. The oscillation this
     * bounds (climb out → APPROACH walks back in → swim out again) refreshed the stall timer on every
     * leg, so it ran the full 5-minute GLOBAL_ABANDON_TICKS in front of the player. Three attempts is
     * generous for a genuine slip off a wet bank and decisive for a real loop; abandoning is the
     * documented best-effort contract, not a failure.
     */
    private static final int MAX_SWIM_REENTRIES = 3;
    /**
     * Max vertical rise, in blocks, from one tread to the next for a series to count as climbable.
     * 1.0 matches what vanilla nav will actually do: {@code WalkNodeEvaluator} caps the neighbour
     * delta at {@code floor(max(1.0, maxUpStep))} = 1 and jumps up to {@code max(1.125, maxUpStep)},
     * so a full-block rise is fine and a slab spiral (0.5 per tread) is trivial. Deliberately NOT
     * the mob's 0.6 step height — that would reject the full-block treads nav handles happily.
     */
    private static final double CLIMB_MAX_RISE = 1.0;
    /** Minimum consecutive climbable cells for a ladder column to count as a route. */
    private static final int CLIMB_MIN_LADDER = 3;
    /**
     * How far along the track (±X) to look for an existing staircase/ladder. DT stamps its
     * `pillars/adjunct_stairs` modules at most once per ~100 blocks and only on elevated track, so
     * "no route" is the common answer — this bound keeps the miss cheap rather than chasing a
     * structure that is usually not there.
     */
    private static final int CLIMB_SCAN_X = 16;
    /**
     * How far out from the carriage face to sweep for a route. DT's staircase footprint is 3 wide
     * starting one block off the track corridor, so 1..3 covers it exactly.
     */
    private static final int CLIMB_SCAN_OUT = 3;
    /** Rescan cadence for the (world-static) climb route — see findClimbRoute. */
    private static final int CLIMB_RESCAN_TICKS = 20;
    /**
     * How close to the top of the flight counts as having arrived. Paired with the height test in
     * {@link #tickClimb()}: on a spiral the mob crosses deck height while still most of a turn short
     * of the landing, so height alone would stop it on the stairs rather than at the top where it can
     * board. 1.5 is "standing on the landing", not merely near it.
     */
    private static final double CLIMB_ARRIVE_DIST = 1.5;
    /**
     * Hard cap on full-A* {@code createPath} probes per route scan. Each one is orders of magnitude
     * dearer than a block lookup, and the scan band is ~100 columns, so probing them all would cost
     * more than the tower it saves. Missing a staircase further along the rail just falls through to
     * BRIDGE — the behaviour before this phase existed.
     */
    private static final int CLIMB_MAX_PATH_PROBES = 6;

    private enum Phase { IDLE, SWIM_TO_SHORE, APPROACH, CLIMB, BRIDGE, GATHER, CRAFT }

    /**
     * An existing way up that the mob can use instead of building one: either a ladder column or a
     * series of treads (DT's slab/stairs spiral). {@code base} is where the mob walks to, {@code top}
     * is the surface it ends on, {@code ladder} picks which tick handler drives the ascent — vanilla
     * nav climbs treads unaided but cannot path a ladder at all.
     *
     * <p>World-space and <b>static</b>: these are worldgen structures beside the track, so unlike
     * {@code target.worldBox()} a found route never needs re-resolving. Only its <em>relevance</em>
     * expires, as the carriage slides past it.</p>
     *
     * <p>{@code base} is meaningful for the ladder case only — it's the rung to walk to. For treads
     * the pathfinder owns the whole approach, so nothing reads it; it is captured (as the mob's
     * position at scan time, hence stale immediately) purely to keep one record shape. Don't build
     * on it for treads.</p>
     */
    private record ClimbRoute(BlockPos base, BlockPos top, boolean ladder) { }

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
    // Watermarks — BEST seen this phase, not last tick. markProgress() must mean "measurably better
    // than anything achieved so far"; the last-tick form below is only for the stuck detectors, which
    // legitimately want per-tick deltas. See the markProgress javadoc.
    private double bestApproachDist = Double.MAX_VALUE;
    private double bestApproachY = -1.0e9;
    private double bestStairsY = -1.0e9;          // highest foot Y reached while building/climbing stairs
    private int stairsStuckTicks = 0;             // consecutive stairs ticks with no height gain
    private int approachStuckTicks = 0;
    private int missTicks = 0;                     // consecutive ticks the carriage was out of range
    private boolean wasOnTracks = false;           // last tick's onTracks(); resets approach trackers on transition
    private int recoveryGrantsUsed = 0;            // mercy plank grants spent this recovery (max MAX_RECOVERY_GRANTS)
    private int wedgedOnBedTicks = 0;              // ticks continuously stuck on the bed; resets only on leaving it / stepping off
    private int waitingTicks = 0;                  // consecutive ticks held beside the deck waiting for an opening
    private int boardCommitTicks = 0;              // ticks remaining in a committed boarding leap
    private int towerPlacements = 0;               // blocks placed while TOWERING (excludes the step off the bed)
    private int bridgeStallTicks = 0;              // ticks in BRIDGE with no placement and no height gained
    private double lastBridgeY = -1.0e9;           // mob y at the last BRIDGE progress check
    private int lastBridgePlacements = -1;         // placementsUsed at the last BRIDGE progress check
    private double lastCarriageX = Double.NaN;      // previous tick's carriage minX, for measuring train speed
    private double driftPerTickX = 0.0;            // smoothed forward drift (blocks/tick) imparted by the moving train

    /** Re-resolved every evaluation/tick — the carriage moves. */
    private TrainEnvironment.ReboardTarget target;
    private BlockPos gatherTargetPos;
    private BlockPos pillarColumn;     // foot column captured at jump-stack launch
    private BlockPos shorePoint;       // dry land we're swimming to; null = none found in range
    private int shoreScanTick = 0;     // mob.tickCount of the last shore scan (rescan cadence)
    private double lastShoreDist = Double.MAX_VALUE;  // BEST mob→shore dist so far (watermark, not last tick)
    private int swimReentries = 0;     // times this ATTEMPT has fallen back in the water (loop bound)
    private int swimStopTick = -100000; // mob.tickCount when the last recovery ended (attempt linking)
    private int groundedTicks = 0;     // consecutive ticks with footing while swimming
    private ClimbRoute climbRoute;     // cached existing way up; world-static, see ClimbRoute
    private int climbScanTick = 0;     // mob.tickCount of the last route scan (rescan cadence)
    private double bestClimbY = -1.0e9;   // highest Y reached on the route (watermark)
    private double bestClimbDist = Double.MAX_VALUE;  // closest approach to the route top (watermark)
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
            case SWIM_TO_SHORE -> "swimming to shore";
            case APPROACH -> "approaching";
            case CLIMB -> climbRoute != null && climbRoute.ladder() ? "climbing a ladder" : "taking the stairs";
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
        // Don't start mid-air / mid-fall; wait until we have footing on something — or until we're
        // afloat, which is the OTHER stable state. A swimming mob is never onGround(), so the bare
        // ground check meant a mob that fell in the drink never started recovering AT ALL: it bobbed
        // where it landed until the train outran it. Water still satisfies the "not mid-fall" intent
        // the ground check was there for — FloatGoal (@0) pins it at the surface, it isn't going
        // anywhere. isInWater() is water-tag-only, so lava never qualifies (and FireBucketGoal @0
        // preempts us there anyway).
        if (!mob.onGround() && !mob.isInWater()) return false;
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
        boardCommitTicks = 0;
        towerPlacements = 0;
        bridgeStallTicks = 0;
        lastBridgeY = -1.0e9;
        lastBridgePlacements = -1;
        lastCarriageX = Double.NaN;
        driftPerTickX = 0.0;
        gatherTargetPos = null;
        pillarColumn = null;
        shorePoint = null;
        shoreScanTick = 0;
        lastShoreDist = Double.MAX_VALUE;
        // Only a genuinely FRESH attempt clears the re-entry budget. A recovery that ended moments ago
        // and restarted after the cooldown is the same mob in the same predicament, and resetting here
        // is what let it defeat MAX_SWIM_REENTRIES and loop in and out of the water indefinitely.
        if (mob.tickCount - swimStopTick > SWIM_ATTEMPT_LINK_TICKS) {
            swimReentries = 0;
        }
        groundedTicks = 0;
        climbRoute = null;
        climbScanTick = 0;
        bestClimbY = -1.0e9;
        bestClimbDist = Double.MAX_VALUE;
        bestApproachDist = Double.MAX_VALUE;
        bestApproachY = -1.0e9;
        bestStairsY = -1.0e9;
        stairsStuckTicks = 0;
        mob.setRecovering(true);   // off the train, re-boarding is the mob's sole focus (no combat)
        if (isAfloat()) {
            enterSwimToShore();    // fell in the drink — get out of the water before anything else
        } else {
            moveTowardCarriage();
        }
        trace("START pos=({},{},{}) onTracks={} afloat={}",
                mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(), onTracks(), isAfloat());
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
        shorePoint = null;
        climbRoute = null;
        clearSwimPosture();   // vanilla never clears these for a Mob — see applySwimPosture
        swimStopTick = mob.tickCount;   // links a quick restart to this attempt's re-entry budget
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
        // Being afloat trumps every other early-out below — but NOT the carriage-drift bookkeeping
        // above, which wants unbroken tick-to-tick continuity and is pure observation. Nothing else
        // recovery does works from the water: it can't board (no jump height treading water, and no
        // footing to launch from), can't tower, can't gather. Worse, onTracks() is a pure Z-SPAN
        // test — a mob swimming in a lake the line crosses reads as "on the tracks", so without this
        // gate the handoff below would drop it into tickGetOffTracks and it would paddle sideways
        // forever instead of making for the bank.
        if (isAfloat() && phase != Phase.SWIM_TO_SHORE) {
            // Bounded: a mob that keeps ending up back in the water is in the climb-out/walk-back-in
            // loop, not making progress.
            if (++swimReentries > MAX_SWIM_REENTRIES) {
                trace("SWIM ABANDON reentries={}", swimReentries);
                stop();
                return;
            }
            enterSwimToShore();
        }
        if (phase == Phase.SWIM_TO_SHORE) {
            if (!isAshore()) {
                tickSwimToShore();
                return;
            }
            // Climbed out — hand straight back to the UNCHANGED approach machinery, this same tick,
            // so the normal board / off-tracks early-outs below get their first look at dry-land
            // state (a mob that surfaces beside a carriage should get tryBoardNow's look at once).
            enterApproach();
        }
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
            enterApproach();
        }
        switch (phase) {
            case APPROACH -> tickApproach();
            case CLIMB -> tickClimb();
            case BRIDGE -> tickBridge();
            case GATHER -> tickGather();
            case CRAFT -> tickCraft();
            default -> { /* IDLE, SWIM_TO_SHORE (handled above) */ }
        }
    }

    // ---- SWIM_TO_SHORE ----------------------------------------------------

    /**
     * Enter SWIM_TO_SHORE: drop whatever the previous phase was doing (a nav path to a launch spot
     * the mob can't reach from the water, a half-built tower) and reset the swim trackers so the
     * stall detector starts clean.
     */
    private void enterSwimToShore() {
        phase = Phase.SWIM_TO_SHORE;
        phaseTicks = 0;
        shorePoint = null;
        shoreScanTick = 0;
        lastShoreDist = Double.MAX_VALUE;
        mob.getNavigation().stop();
        // Deliberately NO markProgress(): falling in the water is a state CHANGE, not an achievement.
        // Marking here is precisely what let the climb-out/fall-back-in loop reset the stall timer on
        // every lap. The swim's own distance watermark marks once the mob actually closes on a bank.
    }

    /** Enter (or re-enter) APPROACH, resetting the approach progress trackers. */
    private void enterApproach() {
        clearSwimPosture();        // out of the water — stop sprinting / swimming-posed on land
        // Drop the swim path too: MoveControl keeps the last speedModifier until the next moveTo, and
        // SWIM_NAV_BOOST is only harmless underwater — on land the ground branch of travel() DOES
        // scale by it, so a leftover boosted path would sprint the mob across the bank at 4x.
        mob.getNavigation().stop();
        phase = Phase.APPROACH;
        phaseTicks = 0;
        lastApproachDist = Double.MAX_VALUE;
        bestApproachDist = Double.MAX_VALUE;
        lastApproachY = -1.0e9;
        bestApproachY = -1.0e9;
        approachStuckTicks = 0;
        shorePoint = null;
    }

    /**
     * Swim for the nearest dry bank, biased toward the track line, then hand off to APPROACH.
     * Movement only — no placing, no gathering, no boarding: this phase exists purely to convert
     * "afloat" into "standing on something", which is the precondition every other phase assumes.
     *
     * <p><b>Depth is not our problem.</b> Vanilla ground nav with {@code setCanFloat(true)} (set in
     * {@code PlayerMobEntity}) will happily route a swim path along the seabed; what keeps the mob's
     * head up is {@link net.minecraft.world.entity.ai.goal.FloatGoal} @0, which is still running
     * because recovery never claims {@link Flag#JUMP} (see the constructor). That flag rule is
     * exactly why it matters here — this phase would drown the mob the moment it took JUMP.</p>
     */
    private void tickSwimToShore() {
        AABB box = target.worldBox();
        applySwimPosture();
        trackFooting();
        // Cadence, not per-tick: this is a few hundred block lookups on a requiresUpdateEveryTick
        // goal. Rescan on entry, then once a second as the mob drifts and the train slides on.
        if (shorePoint == null || mob.tickCount - shoreScanTick >= SHORE_RESCAN_TICKS) {
            shoreScanTick = mob.tickCount;
            BlockPos found = findShorePoint(box);
            if (found != null && !found.equals(shorePoint)) {
                shorePoint = found;
                lastShoreDist = Double.MAX_VALUE;   // new target → restart the progress watermark
                // No markProgress(): PICKING a target isn't reaching one. A mob that re-targets every
                // rescan while getting nowhere should still time out.
            }
        }
        if (shorePoint == null) {
            // No dry land anywhere out to SHORE_RING_MAX — genuinely mid-ocean now, not merely
            // "wider than one small disc". Head for the track structure itself: the bed and its
            // support pillars ARE land, they're the one thing guaranteed to exist in the middle of
            // the water the line crosses, and they're where the mob wants to end up regardless.
            //
            // This used to clamp only Z and keep X fixed, which swam the mob sideways with no
            // destination at all — the "just a random direction" of the report. Aiming at the
            // nearest point of the carriage footprint gives it somewhere real to go.
            double towardX = Mth.clamp(mob.getX(), box.minX, box.maxX);
            double towardZ = Mth.clamp(mob.getZ(), box.minZ, box.maxZ);
            if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(towardX, mob.getY(), towardZ, moveSpeed * SWIM_NAV_BOOST);
            }
            mob.getLookControl().setLookAt(towardX, mob.getY(), towardZ);
            // Still no markProgress(): with nothing dry in 48 blocks this is the hopeless case
            // STALL_ABANDON_TICKS exists to end (the class javadoc's "the mob is lost").
            return;
        }
        double tx = shorePoint.getX() + 0.5, ty = shorePoint.getY() + 1.0, tz = shorePoint.getZ() + 0.5;
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(tx, ty, tz, moveSpeed * SWIM_NAV_BOOST);
        }
        mob.getLookControl().setLookAt(tx, ty, tz);
        // Progress = a new BEST distance to the bank, not "closer than last tick". Swimming is slow
        // (well under 0.05 blocks/tick horizontally for a land mob), so the tick-over-tick test the
        // APPROACH phases use would report a stall on every single tick of a perfectly good swim and
        // STALL_ABANDON_TICKS would kill it 30s in. A monotonic watermark trips as soon as the mob
        // has genuinely gained 0.05 blocks, however many ticks that took.
        double dist = Math.hypot(mob.getX() - tx, mob.getZ() - tz);
        if (dist < lastShoreDist - 0.05) {
            lastShoreDist = dist;
            markProgress();
        }
    }

    /**
     * Make the mob swim like a <em>player</em> rather than paddling upright at a walk. Two separate
     * vanilla mechanisms, both of which a {@link net.minecraft.world.entity.Mob} misses entirely:
     *
     * <p><b>Speed.</b> {@code LivingEntity.travel} picks its horizontal water drag as
     * {@code isSprinting() ? 0.9F : getWaterSlowDown()} (0.8F), against a flat 0.02 accel — so
     * terminal speed is {@code 0.02/(1-drag)}: <b>0.10 blocks/tick not sprinting, 0.20 sprinting,
     * exactly double</b>. Nothing ever sets sprinting on a swimming mob, which is the whole of the
     * "swims SUPER slowly" report.</p>
     *
     * <p><b>Animation.</b> {@code isVisuallySwimming()} is {@code hasPose(Pose.SWIMMING)}, and that
     * pose is assigned in exactly ONE place in the entity hierarchy — {@code Player.updatePlayerPose}.
     * A Mob can never reach it on its own, so {@code swimAmount} decays to 0 and the model never
     * leans into the stroke. Setting the pose directly is what drives the vanilla {@code PlayerModel}
     * the renderer already uses, so the animation comes for free.</p>
     *
     * <p>Deliberately NOT chasing {@code Entity.updateSwimming}'s swim <em>flag</em>: entering it
     * requires {@code isUnderWater()} (eyes submerged), which a surface swimmer held up by
     * {@code FloatGoal} never is. The pose is the lever that works at the surface. Equally
     * deliberately not touching {@code WATER_MOVEMENT_EFFICIENCY} — at 1.0 it yields ~0.58
     * blocks/tick (~11.6 b/s), which is absurd.</p>
     *
     * <p>{@link #clearSwimPosture()} MUST undo both on the way out — nothing else resets sprinting
     * on a Mob, so a mob that reached the bank would otherwise sprint around on land forever.</p>
     */
    private void applySwimPosture() {
        mob.setSprinting(true);
        if (!mob.hasPose(Pose.SWIMMING)) {
            mob.setPose(Pose.SWIMMING);
        }
    }

    /**
     * Undo {@link #applySwimPosture()}. Called on every exit from the swim — reaching the bank
     * ({@link #enterApproach()}) and the goal ending ({@link #stop()}) — because vanilla will not
     * clear either flag for a Mob. The entity declares no per-pose dimensions (see
     * {@code PlayerMobEntity}'s crouch handling), so the pose swap never resizes the hitbox.
     */
    private void clearSwimPosture() {
        mob.setSprinting(false);
        if (mob.hasPose(Pose.SWIMMING)) {
            mob.setPose(Pose.STANDING);
        }
    }

    /**
     * Nearest dry, standable block a swimming mob can climb out onto, or {@code null} if there's
     * none in range (mid-ocean → the caller lets the stall backstop end it).
     *
     * <p>A column sweep, not a cube sweep: for each column in the search ring we
     * take the FIRST dry footing scanning down from {@link #SHORE_SCAN_UP} above the mob's feet —
     * i.e. the shallowest way out of that column — then score it with {@link #shoreCost}.</p>
     */
    private BlockPos findShorePoint(AABB box) {
        if (!(mob.level() instanceof ServerLevel level)) return null;
        BlockPos feet = mob.blockPosition();
        // Anchor on the WATERLINE, not the mob — a shore is at the surface by definition, and the mob
        // may be many blocks under it. See SHORE_SCAN_UP for what anchoring on the mob broke.
        int surfaceY = waterSurfaceY();
        int topY = surfaceY + SHORE_SCAN_UP;
        int bottomY = surfaceY - SHORE_SCAN_DOWN;
        List<BlockPos> candidates = new java.util.ArrayList<>();
        List<Double> costs = new java.util.ArrayList<>();
        // Ring by ring, nearest first, stopping as soon as a ring yields anything. "Nearest shore"
        // falls straight out of the search order rather than having to be recovered from the score,
        // and a distant bank can no longer outbid a close one however the weights are tuned.
        for (int outer = SHORE_RING_STEP; outer <= SHORE_RING_MAX && candidates.isEmpty();
                outer += SHORE_RING_STEP) {
            int inner = outer - SHORE_RING_STEP;
            for (int dx = -outer; dx <= outer; dx++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > outer * outer || d2 <= inner * inner) continue;   // this ring only
                    int cx = feet.getX() + dx, cz = feet.getZ() + dz;
                    BlockPos footing = dryFootingCell(cx, topY, bottomY, cz);
                    if (footing == null) continue;                    // water column / no footing
                    if (!hasDryHeadroom(level, footing)) continue;
                    if (box.intersects(new AABB(footing))) continue;  // never the carriage itself
                    candidates.add(footing);
                    // Rise above the waterline is part of the cost: a 1-block step out is easy, a
                    // 4-block cliff face is a slog the mob may not manage at all.
                    double rise = Math.max(0.0, (footing.getY() + 1) - surfaceY);
                    costs.add(shoreCost(box, mob.getX(), mob.getZ(), cx + 0.5, cz + 0.5, rise));
                }
            }
        }
        if (candidates.isEmpty()) return null;
        // Cheapest first, then take the best one the mob can actually REACH. A bank it can't path to
        // — across a ravine, up a cliff face, behind the carriage — is worse than useless: it swims
        // the whole way and then can't climb out, which reads as picking an absurd destination. The
        // check is bounded (each is a full A*), and if none of the top few are reachable we fall back
        // to the cheapest and let the stall backstop deal with it.
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(costs::get));
        for (int i = 0; i < Math.min(SHORE_REACH_CHECKS, order.length); i++) {
            BlockPos c = candidates.get(order[i]);
            Path p = mob.getNavigation().createPath(c, 0);
            if (p != null && p.canReach()) return c;
        }
        return candidates.get(order[0]);
    }

    /**
     * Cost of a shore candidate: how far the mob has to swim, plus a weighted penalty for how far
     * the bank sits from the track line. Pure geometry, no world access — kept package-private and
     * static so it can be unit-tested the way the policy classes are.
     */
    static double shoreCost(AABB box, double mobX, double mobZ, double x, double z, double rise) {
        double gap = distToTrackLine(box, z);
        // A bank INSIDE the track Z-span is a trap, not a prize. onTracks() is a pure Z-span test, so
        // a mob that climbs out there immediately reads as "standing on the tracks" and APPROACH hands
        // it to tickGetOffTracks — which, where the line crosses water, walks it straight back into the
        // lake. It then swims to the same bank and loops. Scoring the span at zero made that trap the
        // scorer's OPTIMUM; ON_TRACK_SHORE_PENALTY makes an off-span bank win whenever one exists,
        // while still leaving an on-span bank reachable (finite cost) if it is genuinely all there is.
        double trap = gap <= 0.0 ? ON_TRACK_SHORE_PENALTY : 0.0;
        return Math.hypot(x - mobX, z - mobZ)
            + SHORE_TRACK_BIAS * gap
            + SHORE_RISE_BIAS * Math.max(0.0, rise)
            + trap;
    }

    /**
     * Y of the water surface directly above the mob — the first cell with no fluid, scanning up from
     * its feet. This is the anchor the shore search needs: the mob may be well below the surface
     * (nothing stops it swimming down while pathing), but a bank it can climb out onto is at the
     * waterline regardless of how deep the mob currently is.
     *
     * <p>Falls back to the mob's own Y if it isn't in fluid at all, and gives up after
     * {@link #SHORE_SURFACE_SCAN} so a mob under an overhang or in a flooded cave can't spin.</p>
     */
    private int waterSurfaceY() {
        BlockPos feet = mob.blockPosition();
        if (!(mob.level() instanceof ServerLevel level)) return feet.getY();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= SHORE_SURFACE_SCAN; i++) {
            c.set(feet.getX(), feet.getY() + i, feet.getZ());
            if (level.getBlockState(c).getFluidState().isEmpty()) {
                return feet.getY() + i;
            }
        }
        return feet.getY() + SHORE_SURFACE_SCAN;
    }

    /**
     * Perpendicular distance from world Z {@code z} to the track line. The train runs along world-X
     * and the track is exactly as wide in Z as the carriage that rides it (see {@link #onTracks()}),
     * so the carriage box's Z-span IS the line and the gap to it is a pure Z term.
     */
    static double distToTrackLine(AABB box, double z) {
        return Math.max(Math.max(box.minZ - z, 0.0), z - box.maxZ);
    }

    /**
     * Afloat: in water with nothing underfoot — the swimming state. Deliberately NOT just
     * {@code isInWater()}: a mob wading a shallow shoreline is in water AND on ground, and it should
     * keep walking (normal APPROACH), not start a shore search from a spot it's already standing on.
     */
    private boolean isAfloat() {
        return mob.isInWater() && !mob.onGround();
    }

    /**
     * Ashore: out of the water entirely, with footing. Deliberately stricter than
     * {@code !isAfloat()} — {@code onGround()} flickers true for a tick as a swimming mob scrapes a
     * submerged ledge, and a symmetric test would flip the phase (and re-issue nav) every other
     * tick. Requiring the mob be genuinely DRY means the handoff to APPROACH happens once, when it
     * has actually climbed out, and it wades the last metre rather than stopping ankle-deep.
     */
    private boolean isAshore() {
        return groundedTicks >= SHORE_GROUNDED_TICKS;
    }

    /**
     * Track how long the mob has had footing, for {@link #isAshore()}. Called every swim tick.
     *
     * <p>The test used to be {@code onGround() && !isInWater()}, which left a hole the mob fell into
     * constantly: wading the shallows at the very edge of a bank, it is on the ground AND in the
     * water, so it counted as neither afloat nor ashore. The swim phase then ran forever against a
     * shore point it had effectively already reached — the distance watermark stopped improving, the
     * stall detector fired, recovery abandoned and restarted, and the whole thing repeated. That is
     * the in-and-out-of-the-water looping. Footing alone ends the swim; a few ticks of it filters the
     * flicker as a swimmer scrapes a submerged ledge, and APPROACH is perfectly capable of walking
     * the last metre out of ankle-deep water.</p>
     */
    private void trackFooting() {
        groundedTicks = mob.onGround() ? groundedTicks + 1 : 0;
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
            // Still no opening. The tower height is a HARD cap (two blocks over the rails), so we
            // do not climb any higher — keep holding and take the first opening that comes. If none
            // ever does, the stall/global backstops release the mob rather than it building a spire.
            trace("approach WAIT timed out after {} ticks — holding at capped tower height", waitingTicks);
            waitingTicks = 0;
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
        if (spot == null) {
            // No dry launch spot anywhere beside the carriage (the line is crossing open water).
            // There is nothing to walk to — committing here lets CLIMB/BRIDGE/GATHER have a go from
            // where we stand, rather than navigating into the lake and starting the swim loop again.
            commitFromApproach();
            return;
        }
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(spot.x, spot.y, spot.z, moveSpeed);
            mob.getLookControl().setLookAt(spot.x, spot.y, spot.z);
        }
        double distToSpot = Math.hypot(mob.getX() - spot.x, mob.getZ() - spot.z);
        // Stuck detection is per-tick (did we move at all since last tick?)...
        if (distToSpot < lastApproachDist - 0.05 || mob.getY() > lastApproachY + 0.05) {
            approachStuckTicks = 0;
        } else {
            approachStuckTicks++;
        }
        // ...but PROGRESS is a watermark: measurably closer or higher than the best achieved so far.
        // The per-tick form used to double as the progress test, and enterApproach resets
        // lastApproachDist to MAX_VALUE — so the first tick after every re-entry marked progress
        // unconditionally, which is what let the climb-out/walk-back-in loop hold the stall detector
        // open indefinitely.
        if (distToSpot < bestApproachDist - 0.05 || mob.getY() > bestApproachY + 0.05) {
            bestApproachDist = Math.min(bestApproachDist, distToSpot);
            bestApproachY = Math.max(bestApproachY, mob.getY());
            markProgress();
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
     * <p>Ready when: the capped tower height is reached ({@link #TOWER_ABOVE_DECK}, two blocks over
     * the rails — never higher); OR the mob is already a block up and has not laid a single tower
     * block, in which case it is high enough as it stands and building would be pure waste; OR it
     * is out of material and at least at deck level, so this is as high as it will ever get.</p>
     */
    private boolean readyToBoard(AABB box) {
        if (!mob.onGround()) {
            return false;   // standing height only — an airborne sample reads a block high
        }
        int trackY = Mth.floor(box.minY);
        int y = mob.blockPosition().getY();
        if (y >= trackY + TOWER_ABOVE_DECK) {
            return true;                       // reached the cap
        }
        if (towerPlacements == 0 && y >= trackY + 1) {
            return true;                       // already a block up and never towered — don't start
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
        // An existing way up beats both alternatives outright: it costs no blocks, needs no gathering
        // trip, and DT's staircases top out exactly at carriage-floor level beside the track — which
        // is the boarding position. Only build when there's nothing to walk up.
        ClimbRoute route = resolveClimbRoute();
        if (route != null) {
            climbRoute = route;
            phase = Phase.CLIMB;
            bestClimbY = -1.0e9;
            bestClimbDist = Double.MAX_VALUE;
            return;
        }
        if (BlockSourcePolicy.bridgeBlockCount(mob.getInventory()) >= blocksNeeded()) {
            phase = Phase.BRIDGE;
            bestStairsY = -1.0e9;
            stairsStuckTicks = 0;
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
            // Both sides unwalkable: a DROP is fine (placeOffBedStep below bridges it), but WATER is
            // not — stepping off into a lake starts the swim-out/walk-back-in loop all over again.
            // Prefer a dry side if either is dry; if the line is crossing open water on both sides,
            // hold on the bed and let the stall backstop end it rather than wading in.
            boolean nDry = offBedFootingY(mx, box.minY, northOff) != Double.NEGATIVE_INFINITY;
            boolean sDry = offBedFootingY(mx, box.minY, southOff) != Double.NEGATIVE_INFINITY;
            if (!nWalk && !nDry && !sDry) {
                mob.getNavigation().stop();
                return;                                       // open water both sides — don't step in
            }
            if (nDry != sDry) {
                offZ = nDry ? northOff : southOff;            // exactly one dry side → take it
            } else {
                offZ = nearNorth ? northOff : southOff;       // both or neither → nearer side
            }
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
        // Stuck detection is per-tick; PROGRESS is the watermark (closer to the off-side column than
        // we have ever been). enterApproach resets lastApproachDist to MAX_VALUE, so the per-tick form
        // marked unconditionally on the first tick after every re-entry.
        double dist = Math.abs(mob.getZ() - (offZ + 0.5));
        if (dist < lastApproachDist - 0.05) {
            approachStuckTicks = 0;
        } else {
            approachStuckTicks++;
        }
        if (dist < bestApproachDist - 0.05) {
            bestApproachDist = dist;
            markProgress();
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

    // ---- CLIMB ------------------------------------------------------------

    /**
     * Walk up an existing route instead of building one. Two mechanically DIFFERENT ascents behind
     * one phase, because vanilla treats them nothing alike:
     *
     * <p><b>Treads (DT's slab/stairs spiral) need no help at all.</b> {@code WalkNodeEvaluator} caps
     * a neighbour's vertical delta at 1 and jumps up to 1.125, so a half-block-per-tread spiral is
     * ordinary walking to the pathfinder. All this phase does is point navigation at the top and stop
     * BRIDGE from hijacking the mob into paving a redundant tower beside a perfectly good staircase —
     * which is exactly what it used to do.</p>
     *
     * <p><b>Ladders are invisible to the pathfinder.</b> {@code WalkNodeEvaluator} never looks at
     * {@code BlockTags.CLIMBABLE}, {@code PathType} has no climbable value, and {@code getNeighbors}
     * only iterates horizontals — there are no vertical edges in the path graph at all, so a ladder
     * column is just "open air" and {@code moveTo(top)} silently fails. Climbing is a physics
     * behaviour, not a pathfinding one: {@code LivingEntity} clamps Y velocity to +0.2/tick when
     * {@code onClimbable() && (horizontalCollision || jumping)}. So we walk to the base with nav, then
     * drive the ascent off the nav graph with the jump flag — the same MoveControl-not-nav trick
     * {@link #tryBoardNow} already uses.</p>
     */
    private void tickClimb() {
        AABB box = target.worldBox();
        if (climbRoute == null) {                 // route expired/cleared → re-assess from scratch
            enterApproach();
            return;
        }
        clearClimbObstruction(box);               // foliage over a staircase stalls this like it does BRIDGE
        BlockPos top = climbRoute.top();
        // Finished only when the mob is BOTH high enough AND actually at the top of the flight.
        //
        // Height alone is not enough, and on DT's spiral staircases it is actively wrong: the flight
        // wraps around a central core, so the mob passes deck height while it is still most of a turn
        // away from the top landing. Stopping there parked it on a tread partway round — and because
        // waitForOpening halts navigation and zeroes horizontal velocity, it just stood on the stairs
        // instead of walking the last few blocks up to where it could actually board.
        double topDist = Math.hypot(mob.getX() - (top.getX() + 0.5), mob.getZ() - (top.getZ() + 0.5));
        if (mob.getY() >= box.minY - 0.1 && topDist <= CLIMB_ARRIVE_DIST) {
            waitForOpening(box);
            return;
        }
        // The route is worldgen and does NOT move, but the CARRIAGE does — once the train has slid
        // beyond boarding reach of the stair top, this route is no longer a way back on.
        if (Math.abs(top.getX() + 0.5 - (box.minX + box.maxX) / 2.0) > ABANDON_DISTANCE) {
            climbRoute = null;
            enterApproach();
            return;
        }
        if (climbRoute.ladder()) {
            tickLadder();
        } else {
            tickTreads();
        }
        // Progress = a new HIGHEST point on the route, OR a new CLOSEST approach to the top (both
        // watermarks). Climbing is slow and a ladder ticks at a steady +0.2, so a tick-over-tick test
        // would read as a stall on any hitch. The horizontal half matters now that the phase runs on
        // past deck height: the final walk around to the landing gains no height at all, and without
        // it that stretch would sit there burning down the stall timer.
        if (mob.getY() > bestClimbY + 0.05) {
            bestClimbY = mob.getY();
            markProgress();
        }
        if (topDist < bestClimbDist - 0.05) {
            bestClimbDist = topDist;
            markProgress();
        }
    }

    /** Nav-driven ascent: vanilla pathfinding climbs treads unaided, so just aim it at the top. */
    private void tickTreads() {
        BlockPos top = climbRoute.top();
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, moveSpeed);
        }
        mob.getLookControl().setLookAt(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
    }

    /**
     * Physics-driven ascent: walk into the ladder column and hold the jump flag. {@code jumping} is
     * one of the two ways to satisfy vanilla's climb gate (the other is pressing into the wall), and
     * it's the reliable one for a mob that nav can't hold flush against the backing block.
     *
     * <p>Note this sets the jump FLAG on the entity — it does not claim {@link Flag#JUMP} on the
     * goal, which would stop FloatGoal (see the constructor). Different mechanisms entirely.</p>
     */
    private void tickLadder() {
        BlockPos base = climbRoute.base();
        double cx = base.getX() + 0.5, cz = base.getZ() + 0.5;
        mob.getLookControl().setLookAt(cx, mob.getEyeY(), cz);
        if (mob.onClimbable()) {
            // On the ladder: stop navigating (the path graph has no rung to aim at) and let the
            // climb clamp carry us up, nudging inward so we stay in the column.
            //
            // It MUST be getJumpControl().jump(), not setJumping(true). Mob.serverAiStep runs
            // goalSelector.tick() (us) and THEN jumpControl.tick(), which unconditionally does
            // `mob.setJumping(this.jump)` — so a flag we set directly is clobbered back to false in
            // the same tick, before travel() ever reads it. Going through the control is what makes
            // it survive to the climb clamp; it's also why the flag can never get stuck on.
            mob.getNavigation().stop();
            mob.getJumpControl().jump();
            mob.getMoveControl().setWantedPosition(cx, mob.getY() + 1.0, cz, moveSpeed);
            return;
        }
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(cx, base.getY(), cz, moveSpeed);   // ordinary walk to the foot
        }
    }

    /** Cached route lookup on a cadence — the structure is world-static, so this is pure rescan cost. */
    private ClimbRoute resolveClimbRoute() {
        if (climbRoute == null || mob.tickCount - climbScanTick >= CLIMB_RESCAN_TICKS) {
            climbScanTick = mob.tickCount;
            climbRoute = findClimbRoute(target.worldBox());
        }
        return climbRoute;
    }

    /**
     * Nearest existing way up to deck level beside the track, or {@code null} if there is none —
     * which is the COMMON answer: DT stamps these roughly once per 100 blocks and only where the
     * track runs on pillars, so this must stay cheap on the miss.
     *
     * <p>Sweeps the band just outside the carriage's near Z face ({@link #CLIMB_SCAN_OUT} deep,
     * matching DT's 3-wide staircase footprint) over a bounded X window, and prefers whichever
     * candidate is nearest. Ladder columns are checked first — they're an unambiguous single-block
     * test, where a tread series needs the whole column walked.</p>
     */
    private ClimbRoute findClimbRoute(AABB box) {
        if (!(mob.level() instanceof ServerLevel level)) return null;
        boolean nearNorth = Math.abs(mob.getZ() - box.minZ) <= Math.abs(mob.getZ() - box.maxZ);
        double faceZ = nearNorth ? box.minZ : box.maxZ;
        int step = nearNorth ? -1 : 1;
        int deckY = Mth.floor(box.minY);
        int mx = mob.blockPosition().getX();
        // Pass 1 — ladders, everywhere in the band. Pure block lookups, so it's cheap to sweep the
        // whole window. Ladders MUST be found this way: the pathfinder is blind to them (see
        // tickClimb), so the path probe in pass 2 can never turn one up.
        ClimbRoute ladder = null;
        double ladderDist = Double.MAX_VALUE;
        for (int out = 1; out <= CLIMB_SCAN_OUT; out++) {
            int cz = Mth.floor(faceZ) + step * out;
            for (int dx = -CLIMB_SCAN_X; dx <= CLIMB_SCAN_X; dx++) {
                int cx = mx + dx;
                double d = Math.hypot(cx + 0.5 - mob.getX(), cz + 0.5 - mob.getZ());
                if (d >= ladderDist) continue;
                ClimbRoute r = ladderAt(level, cx, cz, deckY);
                if (r == null) continue;
                ladderDist = d;
                ladder = r;
            }
        }
        if (ladder != null) return ladder;
        // Pass 2 — treads, via the pathfinder, STRICTLY BOUNDED. createPath runs a full A* and is far
        // too expensive to fire per candidate column (the band is ~100 of them), so probe only the
        // few deck-level spots nearest the mob and accept that a staircase further along the rail is
        // missed. A miss just falls through to BRIDGE, which is the old behaviour — no regression.
        int probes = 0;
        for (int dx : nearestFirstOffsets()) {
            for (int out = 1; out <= CLIMB_SCAN_OUT && probes < CLIMB_MAX_PATH_PROBES; out++) {
                int cz = Mth.floor(faceZ) + step * out;
                BlockPos top = new BlockPos(mx + dx, deckY, cz);
                // There must be something to STAND on up there. hasDryHeadroom only proves the cells
                // above are clear, which thin air satisfies perfectly — so without this a point ten
                // blocks up in the air beside an elevated track counted as a landing.
                if (level.getBlockState(top.below()).getCollisionShape(level, top.below()).isEmpty()) continue;
                if (!hasDryHeadroom(level, top.below())) continue;   // and room to stand in
                probes++;
                Path path = mob.getNavigation().createPath(top, 0);
                if (path == null || !path.canReach()) continue;
                // And the path must actually END up there. Pathing to a target it can't stand at gets
                // SNAPPED DOWN to the nearest reachable node — so canReach() alone happily reported
                // success for the ground underneath the track. The mob then walked to a patch of dirt
                // under the rails, gained no height, and sat there labelled "taking the stairs"
                // nowhere near a staircase. Compare the end node's height to what we asked for.
                Node end = path.getEndNode();
                if (end == null || end.y < top.getY() - 1) continue;
                return new ClimbRoute(mob.blockPosition(), top, false);
            }
            if (probes >= CLIMB_MAX_PATH_PROBES) break;
        }
        return null;
    }

    /**
     * X offsets to probe, nearest the mob first, so the bounded probe budget is spent on the columns
     * most likely to be the staircase the mob is standing at the foot of.
     */
    private static int[] nearestFirstOffsets() {
        return new int[] { 0, 1, -1, 2, -2, 3, -3 };
    }

    /**
     * A ladder route in column {@code (x, z)} reaching {@code deckY}, or {@code null} — an unbroken
     * {@link BlockTags#CLIMBABLE} run of at least {@link #CLIMB_MIN_LADDER} that tops out at the deck.
     */
    private ClimbRoute ladderAt(ServerLevel level, int x, int z, int deckY) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        int runStart = -1, run = 0, bestStart = -1, bestTop = Integer.MIN_VALUE;
        // Scan past the deck: DT's ladder columns top out AT carriage-floor level, and we want the
        // real top of the run. Returning on the first qualifying y instead (the obvious way to write
        // this) always reported deckY-1 for any ladder taller than CLIMB_MIN_LADDER, because the
        // run-length test is already satisfied long before the top — which made tickClimb hand off to
        // waitForOpening a block and a half BELOW the deck, too low to board from.
        for (int y = mob.blockPosition().getY() - 2; y <= deckY + 1; y++) {
            c.set(x, y, z);
            if (level.getBlockState(c).is(BlockTags.CLIMBABLE)) {
                if (run == 0) runStart = y;
                run++;
                if (run >= CLIMB_MIN_LADDER && y >= deckY - 1) {
                    bestStart = runStart;
                    bestTop = y;                    // keep going — take the HIGHEST rung, not the first
                }
            } else {
                run = 0;
            }
        }
        return bestTop == Integer.MIN_VALUE
            ? null
            : new ClimbRoute(new BlockPos(x, bestStart, z), new BlockPos(x, bestTop, z), true);
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
        // Judged on the ground only — see tickJumpStack: an airborne sample reads a block high and
        // aborts the climb at the apex of every hop.
        // readyToBoard, not a raw height test: it also covers "already a block up and never
        // towered", so a mob that starts high enough never lays a block at all.
        if (readyToBoard(box)) {
            waitForOpening(box);                       // high enough; tryBoardNow leaps when an opening is in reach
            return;
        }
        BlockPos place = BlockSourcePolicy.nextBridgePos(mob.blockPosition(), box);
        if (place == null || isUnderBox(place, box)) {
            tickJumpStack(slot, box);                  // at the edge — pillar up beside, don't build under
            return;
        }
        // Steer onto the next step (toward the carriage), rising with the mob — never back
        // toward the ground approach point. Gated to the same cadence every other phase uses:
        // re-pathing every tick to a cell one block away, whose direction flips as the train slides,
        // reads in-game as the mob standing still twitching.
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(place.getX() + 0.5, place.getY() + 1.0, place.getZ() + 0.5, moveSpeed);
        }
        // Height watermark. This phase was the ONLY one without a stuck detector: the standable-step
        // branch below used to markProgress() unconditionally every tick, with no evidence the mob had
        // actually climbed anything — which both looked like idling and held the 30s stall detector
        // open forever, leaving the 5-minute global timeout as the only way out.
        if (mob.getY() > bestStairsY + 0.05) {
            bestStairsY = mob.getY();
            stairsStuckTicks = 0;
            markProgress();
        } else {
            stairsStuckTicks++;
        }
        if (stairsStuckTicks > APPROACH_STUCK_LIMIT) {
            // Not gaining height on this staircase — pillar up beside it instead of retrying forever.
            stairsStuckTicks = 0;
            tickJumpStack(slot, box);
            return;
        }
        // Already a usable step here (existing stairs/terrain — possibly one this or another
        // mob built)? Climb it instead of rebuilding; just walk on, no placement.
        if (isStandableStep(place)) {
            return;
        }
        // A solid-but-unreplaceable block (vanilla stairs, a slab, a fence) can never be built on:
        // tryPlaceBridgeBlock would fail its canBeReplaced() check every attempt and the mob would
        // stand there doing nothing. Pillar up beside it instead.
        if (!mob.level().getBlockState(place).canBeReplaced()) {
            tickJumpStack(slot, box);
            return;
        }
        if (phaseTicks % PLACE_INTERVAL_TICKS != 0) return;   // let it climb the previous step
        lookAt(place);
        if (tryPlaceBridgeBlock(place, slot)) {
            placementsUsed++;
            towerPlacements++;
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
        // Step-up feasibility is a HEIGHT question, not a block-type one. The old test here was
        // isCollisionShapeFullBlock, which is true only of a 1x1x1 cube — so every slab and every
        // stairs block failed it, and DT's slab/stairs spiral staircases were invisible as steps.
        // The mob stood on a perfectly good staircase and tried to pave a new one. Measure the
        // shape's actual top instead (a bottom slab reads 0.5), the same way vanilla's
        // WalkNodeEvaluator.getFloorLevel does.
        VoxelShape shape = s.getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        if (pos.getY() + shape.max(Direction.Axis.Y) - mob.getY() > CLIMB_MAX_RISE) return false;
        // Submerged terrain is not a step. A stone block at the bottom of a lake has a perfectly
        // good collision shape, and water clears a collision-only headroom test, so without this the
        // mob mistakes the lakebed for a finished staircase and stalls against it.
        if (!s.getFluidState().isEmpty()) return false;
        if (BlockSourcePolicy.isProtectedTrackBlock(s)) return false;
        if (overlapsCarriage(pos)) return false;
        // hasDryHeadroom, not the bare collision check: it is the same two cells plus a fluid test,
        // which is what stops submerged terrain reading as a finished step.
        return hasDryHeadroom(level, pos);
    }

    /**
     * Jump-stack a pillar beside the mob: capture the foot column on launch, then
     * at the apex place a block in that just-vacated space so the mob lands one
     * block higher. Repeats until level with the deck, then hops across. The jump
     * cycle paces placement (one per launch). Works for gravity blocks (sand/gravel),
     * which a staircase out into the air can't support.
     */
    private void tickJumpStack(int slot, AABB box) {
        // Tower height is judged by where the mob is STANDING, never mid-jump. Testing the
        // instantaneous Y made the apex of every hop read as "tall enough", returning before the
        // placement below could fill the vacated foot space — so the mob bounced between N and N+1
        // forever, holding blocks it could never spend and permanently one block short.
        // readyToBoard, not a raw height test: it also covers "already a block up and never
        // towered", so a mob that starts high enough never lays a block at all.
        if (readyToBoard(box)) {
            // High enough (capped): hold station and take the first clear opening.
            traceTick("jumpStack TOWER TOP mobBlockY={} deckMinY={} cap={} towerPlaced={} blocks={} → wait for opening",
                    mob.blockPosition().getY(), f(box.minY),
                    Mth.floor(box.minY) + TOWER_ABOVE_DECK, towerPlacements,
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
                towerPlacements++;
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
        // A leap is COMMITTED once started: readyToBoard only passes with both feet on the tower,
        // so without a latch the mob would stop steering the instant it left the ground — halfway
        // through its own jump. The latch keeps it tracking the (moving) opening through the arc.
        boolean committed = boardCommitTicks > 0;
        if (!committed && !readyToBoard(box)) {
            return false;
        }
        Vec3 spot = TrainConfinement.boardingSpot(mob, box);
        if (spot == null) {
            boardCommitTicks = 0;      // opening gone — abandon the attempt, don't sail on blind
            return false;
        }
        boardCommitTicks = committed ? boardCommitTicks - 1 : BOARD_COMMIT_TICKS;
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
        // and it is NOT reset by markProgress. (Kept over the merge's other side, which dropped
        // this mark: that was written before the deck-height gate existed, and with the gate in
        // place a correctly-parked mob should not be timed out of a wait it is winning.)
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
            // No markProgress(): picking a block to walk to isn't gathering one. Reaching it and
            // breaking it both mark (see tickGather's in-reach branch), which is the real work.
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
                enterApproach();
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
        if (p == null) return;   // nothing dry to walk to — tickApproach handles it next tick
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
        boolean foundDry = false;
        for (int i = 1; i <= OFF_TRACK_MAX_STEPS; i++) {
            tz = faceZ + step * i;
            // Off the bed AND on dry land. Now that surfaceIsTrack reports false for a water column
            // (it has no footing, so it isn't the bed), "not track" alone would break on the first
            // WATER column out and launch the mob from a lake — so require real dry footing too.
            if (!surfaceIsTrack(centerX, box.minY, tz)
                    && offBedFootingY(Mth.floor(centerX), box.minY, Mth.floor(tz)) != Double.NEGATIVE_INFINITY) {
                foundDry = true;
                break;                          // first off-track dry column out — launch from here
            }
        }
        // NOTHING dry within reach of the face — every column out is water or void. The loop used to
        // fall out here with tz still pointing at that last water column, and groundSurfaceY would
        // then mask the void sentinel with the mob's own Y, so the "launch spot" was a plausible-looking
        // point in the middle of a lake. APPROACH navigated to it, the mob swam, climbed out, and came
        // straight back — the oscillation. Say so instead of inventing a spot.
        if (!foundDry) return null;
        // Scan along the carriage length (at this off-track offset) for higher ground.
        double bestX = centerX;
        double bestGroundY = groundSurfaceY(bestX, box.minY, tz);
        int samples = 10;
        for (int i = 0; i <= samples; i++) {
            double x = box.minX + (box.maxX - box.minX) * (i / (double) samples);
            // offBedFootingY, not groundSurfaceY: the latter masks the void sentinel by substituting
            // the mob's own Y, which would let a water / void column win the "highest ground" vote.
            double g = offBedFootingY(Mth.floor(x), box.minY, Mth.floor(tz));
            if (g == Double.NEGATIVE_INFINITY) continue;   // water / void column — never a launch spot
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
        int top = Mth.floor(deckMinY);
        // First block with real footing — see dryFootingCell. A column that goes under water has no
        // footing at all, so it isn't the bed either; callers pair this with an offBedFootingY check
        // when they need "off the bed AND dry".
        BlockPos cell = dryFootingCell(Mth.floor(x), top, top - GROUND_SCAN_DEPTH, Mth.floor(z));
        return cell != null && BlockSourcePolicy.isProtectedTrackBlock(level.getBlockState(cell));
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
     * Standable <em>dry</em> surface Y (top of the first block with a non-empty collision shape —
     * full block, slab or stairs; rails/air/plants are skipped) at column {@code (x, z)}, scanning
     * down from the deck floor, or {@link Double#NEGATIVE_INFINITY} if none within
     * {@link #GROUND_SCAN_DEPTH} (a void / long drop) <em>or the column goes under water first</em>.
     * The explicit void sentinel lets callers tell "no footing" from a real surface that happens to
     * be at the mob's Y.
     */
    private double offBedFootingY(int x, double deckMinY, int z) {
        int top = Mth.floor(deckMinY);
        BlockPos cell = dryFootingCell(x, top, top - GROUND_SCAN_DEPTH, z);
        return cell == null ? Double.NEGATIVE_INFINITY : cell.getY() + 1.0;
    }

    /**
     * The first cell with real footing in column {@code (x, z)}, scanning DOWN from {@code topY} to
     * {@code bottomY}, or {@code null} if the column has none — or if it goes under a fluid first.
     * Footing means a non-empty collision shape, so full blocks, slabs and stairs all count while
     * rails / air / plants are skipped ({@code isCollisionShapeFullBlock} would wrongly reject a
     * slab or stairs track piece).
     *
     * <p><b>The fluid stop is the load-bearing part.</b> Water's collision shape is EMPTY, exactly
     * like air, so a naive downward scan sails clean through the surface of a lake and reports the
     * LAKEBED as the column's standable surface. Every caller then believed a submerged spot was dry
     * ground: {@link #approachPoint} would pick a launch spot at the bottom of the lake and the mob
     * would wade in and try to tower up underwater. Treating the first fluid cell as the end of the
     * column means a column that goes under water simply has no footing — callers skip it and pick a
     * dry one instead. It excludes lava for free, which is the right answer for the same reason, and
     * it rejects waterlogged slabs/stairs, whose top is still underwater.</p>
     */
    private BlockPos dryFootingCell(int x, int topY, int bottomY, int z) {
        if (!(mob.level() instanceof ServerLevel level)) return null;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int y = topY; y >= bottomY; y--) {
            c.set(x, y, z);
            BlockState s = level.getBlockState(c);
            if (!s.getFluidState().isEmpty()) return null;   // column goes under water/lava — not dry
            if (!s.getCollisionShape(level, c).isEmpty()) {
                return c.immutable();           // stand on top of the first standable block
            }
        }
        return null;
    }

    /**
     * True if the mob can actually stand on {@code footing}: {@link #SHORE_HEADROOM} cells above it
     * are free of collision AND free of fluid. The fluid half is the point — a submerged ledge has
     * flawless "headroom" measured by collision alone (water collides with nothing), so a
     * collision-only test cheerfully reports the bottom of a lake as a place to stand.
     */
    private boolean hasDryHeadroom(ServerLevel level, BlockPos footing) {
        for (int i = 1; i <= SHORE_HEADROOM; i++) {
            BlockPos a = footing.above(i);
            BlockState s = level.getBlockState(a);
            if (!s.getCollisionShape(level, a).isEmpty()) return false;
            if (!s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /** Horizontal distance from the mob to the nearest point of {@code box}. */
    private double horizontalDistToBox(AABB box) {
        double dx = Math.max(Math.max(box.minX - mob.getX(), 0.0), mob.getX() - box.maxX);
        double dz = distToTrackLine(box, mob.getZ());
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

    /**
     * Note forward progress so the stall-abandon timer ({@link #STALL_ABANDON_TICKS}) resets.
     *
     * <p><b>Prefer progress the mob has actually ACHIEVED</b> — a block placed, a block broken, a
     * leap taken, or a new best distance/height on a watermark — over intentions (a fresh target
     * picked) and bare state changes (fell in the water, changed phase). Marking on those was what
     * let a stuck loop refresh the timer on every lap, leaving {@link #GLOBAL_ABANDON_TICKS} (5
     * minutes) as the effective bound on a wedged mob in full view of the player.</p>
     *
     * <p>"Watermark" means better than the best achieved so far this phase, not better than last
     * tick. A per-tick test looks equivalent but is not: any phase re-entry resets the last-tick
     * baseline, so the first tick after it always reports progress.</p>
     *
     * <p>The deliberate exception is {@link #waitForOpening}, which marks while standing still: its
     * entry is gated on the mob being genuinely at deck height and its duration is bounded
     * separately, so the stall timer is the wrong tool there.</p>
     */
    private void markProgress() {
        lastProgressTick = totalTicks;
    }

    private boolean mobGriefingOn() {
        return GameRuleCompat.mobGriefing(mob.level());
    }
}
