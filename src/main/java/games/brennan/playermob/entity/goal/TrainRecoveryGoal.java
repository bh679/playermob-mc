package games.brennan.playermob.entity.goal;

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
 * SWIM_TO_SHORE → APPROACH → BRIDGE → GATHER → CRAFT. Registered at goalSelector
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
    private static final int SHORE_SCAN_RADIUS = 12;
    /**
     * Vertical band of the shore search, relative to the swimming mob's feet: a bank the mob can
     * climb out onto is at or just above the waterline, never far below it.
     */
    private static final int SHORE_SCAN_UP = 4;
    private static final int SHORE_SCAN_DOWN = 3;
    /**
     * Rescan cadence (1s). {@link #requiresUpdateEveryTick()} is true and the shore sweep is
     * hundreds of block lookups, so it must NOT run per tick — but the train slides on and the mob
     * drifts, so a one-shot scan at phase entry goes stale. Once a second is imperceptible at swim
     * speed and keeps the goal off the hot path.
     */
    private static final int SHORE_RESCAN_TICKS = 20;
    /**
     * How hard to pull the shore choice toward the track line, in blocks-of-equivalent-distance.
     * The mob is chasing a MOVING train, so a bank 8 blocks away beside the rails beats one 6
     * blocks away out in the wilderness — it lands where APPROACH can immediately start working.
     * A weight, not a filter: with no track-side shore in range it still takes the nearest dry land.
     */
    private static final double SHORE_TRACK_BIAS = 2.0;
    /** Cells of clear, water-free air a standing spot needs above its footing (the mob is 2 tall). */
    private static final int SHORE_HEADROOM = 2;

    private enum Phase { IDLE, SWIM_TO_SHORE, APPROACH, BRIDGE, GATHER, CRAFT }

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

    /** Re-resolved every evaluation/tick — the carriage moves. */
    private TrainEnvironment.ReboardTarget target;
    private BlockPos gatherTargetPos;
    private BlockPos pillarColumn;     // foot column captured at jump-stack launch
    private BlockPos shorePoint;       // dry land we're swimming to; null = none found in range
    private int shoreScanTick = 0;     // mob.tickCount of the last shore scan (rescan cadence)
    private double lastShoreDist = Double.MAX_VALUE;  // BEST mob→shore dist so far (watermark, not last tick)
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
        if (phase == Phase.APPROACH && onTracks()) {
            return "leaving tracks";   // on the rails/bed → top priority is getting off the side
        }
        return switch (phase) {
            case SWIM_TO_SHORE -> "swimming to shore";
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
        gatherTargetPos = null;
        pillarColumn = null;
        shorePoint = null;
        shoreScanTick = 0;
        lastShoreDist = Double.MAX_VALUE;
        mob.setRecovering(true);   // off the train, re-boarding is the mob's sole focus (no combat)
        if (isAfloat()) {
            enterSwimToShore();    // fell in the drink — get out of the water before anything else
        } else {
            moveTowardCarriage();
        }
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
        shorePoint = null;
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
        // Being afloat trumps every other early-out below. Nothing recovery does works from the
        // water: it can't board (no jump height treading water, and no footing to launch from),
        // can't tower, can't gather. Worse, onTracks() is a pure Z-SPAN test — a mob swimming in a
        // lake the line crosses reads as "on the tracks", so without this gate the handoff below
        // would drop it into tickGetOffTracks and it would paddle sideways forever instead of
        // making for the bank.
        if (isAfloat() && phase != Phase.SWIM_TO_SHORE) {
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
        markProgress();            // hitting the water is a state change, not a stall
    }

    /** Enter (or re-enter) APPROACH, resetting the approach progress trackers. */
    private void enterApproach() {
        phase = Phase.APPROACH;
        phaseTicks = 0;
        lastApproachDist = Double.MAX_VALUE;
        lastApproachY = -1.0e9;
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
        // Cadence, not per-tick: this is a few hundred block lookups on a requiresUpdateEveryTick
        // goal. Rescan on entry, then once a second as the mob drifts and the train slides on.
        if (shorePoint == null || mob.tickCount - shoreScanTick >= SHORE_RESCAN_TICKS) {
            shoreScanTick = mob.tickCount;
            BlockPos found = findShorePoint(box);
            if (found != null && !found.equals(shorePoint)) {
                shorePoint = found;
                lastShoreDist = Double.MAX_VALUE;   // new target → restart the progress watermark
                markProgress();                     // committing to a fresh target is forward motion
            }
        }
        if (shorePoint == null) {
            // Nothing dry within SHORE_SCAN_RADIUS (mid-ocean / a wide river crossing). Best-effort:
            // swim perpendicular toward the track line so successive rescans sweep fresh water.
            // Deliberately NO markProgress() here — bobbing with no shore in range is exactly the
            // hopeless case STALL_ABANDON_TICKS exists to end (the class javadoc's "the mob is lost").
            double towardZ = Mth.clamp(mob.getZ(), box.minZ, box.maxZ);
            if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(mob.getX(), mob.getY(), towardZ, moveSpeed);
            }
            mob.getLookControl().setLookAt(mob.getX(), mob.getY(), towardZ);
            return;
        }
        double tx = shorePoint.getX() + 0.5, ty = shorePoint.getY() + 1.0, tz = shorePoint.getZ() + 0.5;
        if (phaseTicks % PATH_REISSUE_TICKS == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(tx, ty, tz, moveSpeed);
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
     * Nearest dry, standable block a swimming mob can climb out onto, or {@code null} if there's
     * none in range (mid-ocean → the caller lets the stall backstop end it).
     *
     * <p>A column sweep, not a cube sweep: for each column in the {@link #SHORE_SCAN_RADIUS} disc we
     * take the FIRST dry footing scanning down from {@link #SHORE_SCAN_UP} above the mob's feet —
     * i.e. the shallowest way out of that column — then score it with {@link #shoreCost}.</p>
     */
    private BlockPos findShorePoint(AABB box) {
        if (!(mob.level() instanceof ServerLevel level)) return null;
        BlockPos feet = mob.blockPosition();
        int topY = feet.getY() + SHORE_SCAN_UP;
        int bottomY = feet.getY() - SHORE_SCAN_DOWN;
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dx = -SHORE_SCAN_RADIUS; dx <= SHORE_SCAN_RADIUS; dx++) {
            for (int dz = -SHORE_SCAN_RADIUS; dz <= SHORE_SCAN_RADIUS; dz++) {
                if (dx * dx + dz * dz > SHORE_SCAN_RADIUS * SHORE_SCAN_RADIUS) continue;
                int cx = feet.getX() + dx, cz = feet.getZ() + dz;
                // Cheap rejects BEFORE any block lookup: already dearer than the best candidate.
                double cost = shoreCost(box, mob.getX(), mob.getZ(), cx + 0.5, cz + 0.5);
                if (cost >= bestCost) continue;
                BlockPos footing = dryFootingCell(cx, topY, bottomY, cz);
                if (footing == null) continue;                    // water column / no footing
                if (!hasDryHeadroom(level, footing)) continue;
                if (box.intersects(new AABB(footing))) continue;  // never the carriage itself
                bestCost = cost;
                best = footing;
            }
        }
        return best;
    }

    /**
     * Cost of a shore candidate: how far the mob has to swim, plus a weighted penalty for how far
     * the bank sits from the track line. Pure geometry, no world access — kept package-private and
     * static so it can be unit-tested the way the policy classes are.
     */
    static double shoreCost(AABB box, double mobX, double mobZ, double x, double z) {
        return Math.hypot(x - mobX, z - mobZ) + SHORE_TRACK_BIAS * distToTrackLine(box, z);
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
        return mob.onGround() && !mob.isInWater();
    }

    // ---- APPROACH ---------------------------------------------------------

    private void tickApproach() {
        AABB box = target.worldBox();
        boolean on = onTracks();
        if (on != wasOnTracks) {       // crossed the bed boundary → reset the approach progress trackers
            lastApproachDist = Double.MAX_VALUE;
            lastApproachY = -1.0e9;
            approachStuckTicks = 0;
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
        if (mob.getY() >= box.minY - 1.0 && horizontalDistToBox(box) <= 2.5) {
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
        double targetY = groundSurfaceY(mx, box.minY, offZ);
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
        // Wedged at the bed edge (a drop beside an elevated line) → lay a step block to cross.
        if (approachStuckTicks > APPROACH_STUCK_LIMIT) {
            placeOffBedStep(box, stepZ);
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
    private void placeOffBedStep(AABB box, int stepZ) {
        BlockPos foot = mob.blockPosition();
        int placeZ = foot.getZ() + stepZ;
        double placeCenterZ = placeZ + 0.5;
        if (placeCenterZ >= box.minZ && placeCenterZ <= box.maxZ) {
            return;                              // next cell still inside the track footprint — keep walking
        }
        int slot = bridgeBlockSlot();
        if (slot < 0 || phaseTicks % PLACE_INTERVAL_TICKS != 0) {
            return;                              // no block to bridge a void, or pacing placement
        }
        // One below the mob's feet, so the block's top is level with the bed surface for a flat step.
        BlockPos step = new BlockPos(foot.getX(), foot.getY() - 1, placeZ);
        lookAt(step);
        if (tryPlaceBridgeBlock(step, slot)) {
            placementsUsed++;
            approachStuckTicks = 0;
            markProgress();
            // Nudge onto the new step (off the bed); next tick onTracks() flips false.
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
        return dir < 0 ? Mth.floor(box.minZ) - 1 : Mth.ceil(box.maxZ);
    }

    /** True if off-bed column {@code (x,z)} has footing the mob can step to without bridging (≤1 up, ≤3 down). */
    private boolean stepFooting(int x, double deckMinY, int z, int footY) {
        double y = offBedFootingY(x, deckMinY, z);
        return y != Double.NEGATIVE_INFINITY && y <= footY + 1.0 && y >= footY - 3.0;
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
        // Submerged terrain is not a step. isCollisionShapeFullBlock is true for a stone block at
        // the bottom of a lake, and water clears a collision-only headroom test, so without this the
        // mob mistakes the lakebed for a finished staircase and stalls against it.
        if (!s.getFluidState().isEmpty()) return false;
        if (BlockSourcePolicy.isProtectedTrackBlock(s)) return false;
        if (target != null && target.worldBox().intersects(new AABB(pos))) return false;
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
            // Off the bed AND on dry land. Now that surfaceIsTrack reports false for a water column
            // (it has no footing, so it isn't the bed), "not track" alone would break on the first
            // WATER column out and launch the mob from a lake — so require real dry footing too.
            if (!surfaceIsTrack(centerX, box.minY, tz)
                    && offBedFootingY(Mth.floor(centerX), box.minY, Mth.floor(tz)) != Double.NEGATIVE_INFINITY) {
                break;                          // first off-track dry column out — launch from here
            }
        }
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

    /** Note forward progress so the stall-abandon timer ({@link #STALL_ABANDON_TICKS}) resets. */
    private void markProgress() {
        lastProgressTick = totalTicks;
    }

    private boolean mobGriefingOn() {
        return GameRuleCompat.mobGriefing(mob.level());
    }
}
