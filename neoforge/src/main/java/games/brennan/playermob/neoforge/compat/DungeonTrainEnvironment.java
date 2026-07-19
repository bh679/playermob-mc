package games.brennan.playermob.neoforge.compat;

//? if >=26 {
/*// Dungeon Train ({@code dungeontrain}) is a NeoForge-1.21.1-only optional compile dependency
// (see neoforge/build.gradle.kts: the modCompileOnly + JOML deps attach on `mc == "1.21.1"` only).
// It does not exist for MC 26.x, so the real DT-backed environment below cannot compile there.
// On >=26 this becomes a harmless, never-instantiated stub: PlayerMobNeoForge.installDungeonTrain()
// is itself guarded to <26 (DT can never load on 26 anyway), so nothing references this class at
// runtime on 26. It still has to be a compilable TrainEnvironment in the source set, so the full
// interface is implemented with "not on a train" answers — identical behaviour to TrainEnvironment.ABSENT.
// Line comments only inside this >=26 block (no block comments — they cannot nest).
import games.brennan.playermob.compat.TrainEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class DungeonTrainEnvironment implements TrainEnvironment {
    @Override public boolean isOnTrain(Entity self) { return false; }
    @Override public boolean sameTrain(Entity self, Entity candidate) { return false; }
    @Override public boolean sameTrain(Entity self, BlockPos candidatePos) { return false; }
    @Override public ReboardTarget nearestCarriage(Entity self, double radius) { return null; }
    @Override public int carriageIndex(Entity self) { return NO_CARRIAGE; }
    @Override public int spawnCarriageIndex(Entity self) { return NO_CARRIAGE; }
    @Override public Vec3 nextCarriageTarget(Entity self, int dir) { return null; }
    @Override public Vec3 nextGroupTarget(Entity self, int dir) { return null; }
    @Override public void openBlockingDoor(Entity self) {}
    @Override public boolean digObstructingBlock(Entity self) { return false; }
    @Override public Vec3 boardingSpot(Entity self, AABB carriageBox) { return null; }
}
*///?} else {
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.TrainEnvironment;
import games.brennan.playermob.entity.BlockSourcePolicy;
import games.brennan.playermob.entity.DoorObstruction;
import games.brennan.playermob.entity.DoorStuckMonitor;
import games.brennan.playermob.entity.MiningMath;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.TrainDigPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Dungeon Train-backed {@link TrainEnvironment}. The only class in PlayerMob that
 * references Dungeon Train ({@code dungeontrain}) symbols; instantiated solely from
 * inside the {@code ModList.isLoaded("dungeontrain")} guard in
 * {@code PlayerMobNeoForge}, so the JVM never classloads it (or the DT/JOML types it
 * imports) when Dungeon Train is absent.
 *
 * <p><b>Matching a mob to its carriage.</b> A carriage group is a moving Sable
 * sub-level, but a mob riding it stays in the <em>parent</em> {@link ServerLevel}
 * (Sable's "entities stick to sub-levels" model), so its {@code level()} is the
 * overworld and {@code getX/Y/Z} are world-space. {@code Shipyards.findAt} keys off
 * sub-level-local block storage and does not see a riding mob's world position, so we
 * instead test that position against each group's current world bounding box
 * ({@link games.brennan.dungeontrain.ship.ManagedShip#worldAABB()}) over
 * {@link Trains#allCarriages(ServerLevel)}. (This also fixes behaviour #1's
 * confinement, which used the non-working {@code findAt} path.)</p>
 *
 * <p><b>Room index.</b> One {@link games.brennan.dungeontrain.train.TrainTransformProvider}
 * exists per group and reports the group's pIdx range
 * ({@code getPIdx()}..{@code getGroupHighestPIdx()}). The train runs along world-X
 * with rooms laid head-to-tail, so a mob's signed carriage index is read by mapping
 * its world-X across that AABB onto the pIdx range. (Assumes the group is axis-aligned
 * along X, which Dungeon Train guarantees for a rigid group.)</p>
 *
 * <p><b>Doors.</b> A carriage's blocks live in the sub-level's coordinate space (the
 * same far-offset space the navigation paths in), not at the mob's apparent world
 * position — so door-opening converts the mob's position via
 * {@link games.brennan.dungeontrain.ship.ManagedShip#worldToShip}. Wooden and copper
 * doors are hand-openable and opened directly; iron doors are redstone-only, so the mob
 * turns to a reachable button/lever it has line of sight to and swings to operate it. The
 * group-boundary door is left shut
 * (opening it would march the mob into the inter-group gap — behaviour #2), and
 * pressure-plate-controlled doors rely on the mob walking onto the plate.</p>
 *
 * <p>All queries are server-authoritative: every method first checks the entity is on
 * a {@link ServerLevel} and returns the "not on a train" answer otherwise.</p>
 */
public final class DungeonTrainEnvironment implements TrainEnvironment {

    /**
     * Inflate a carriage's world AABB by this many blocks when testing whether a mob
     * is "on" it — a mob on the floor or brushing a wall should still resolve. Small
     * enough not to claim a mob standing in the gap between groups.
     */
    private static final double RIDE_MARGIN = 1.0;

    /** How far around the mob to look for a door it's standing against. */
    private static final int DOOR_REACH = 2;

    /** How far from an iron door to look for its button/lever control. */
    private static final int CONTROL_REACH = 3;

    /** Max eye-to-control distance for the mob to operate a control (≈ a player's block reach). */
    private static final double INTERACT_REACH = 4.5;
    private static final double INTERACT_REACH_SQR = INTERACT_REACH * INTERACT_REACH;

    /** Ticks the mob keeps facing — and punching at — a control it just operated, so it reads as deliberate. */
    private static final int CONTROL_GAZE_TICKS = 16;

    /**
     * Per-mob "keep looking at / swinging at the control I just operated" hint:
     * {@code {ticksLeft, dx, dy, dz}} where {@code (dx,dy,dz)} is the control's offset from the
     * mob's eyes. Stored mob-relative so the look tracks both the moving carriage and the mob's
     * own drift. Server-thread only; weak keys so entries vanish with the mob.
     */
    private static final Map<Entity, double[]> CONTROL_GAZE = new WeakHashMap<>();

    /** Ticks a just-toggled hand door is left alone before it may be toggled again — anti-flap. */
    private static final int DOOR_TOGGLE_COOLDOWN = 20;

    /** How close another PlayerMob must be for a mob to hold a door open rather than close it on them. */
    private static final double DOOR_COMPANION_REACH = 2.5;

    /**
     * Per-mob "I just toggled this door" memory for the path-aware door reflex:
     * {@code {doorPosLong, ticksLeft}}. A door that obstructed the mob's travel axis is toggled
     * to clear it; toggling makes it stop obstructing that axis, so the obstruction scan won't
     * re-pick it on its own — this cooldown is the belt-and-suspenders guard against a flickering
     * heading re-toggling the same door before the mob has crossed. Server-thread only; weak keys
     * so entries vanish with the mob, exactly like {@link #CONTROL_GAZE}.
     */
    private static final Map<Entity, long[]> DOOR_COOLDOWN = new WeakHashMap<>();

    @Override
    public boolean isOnTrain(Entity self) {
        return carriageAt(self) != null;
    }

    @Override
    public boolean sameTrain(Entity self, Entity candidate) {
        if (self.level() != candidate.level()) {
            return false;
        }
        UUID mine = trainIdAt(self);
        return mine != null && mine.equals(trainIdAt(candidate));
    }

    @Override
    public boolean sameTrain(Entity self, BlockPos candidatePos) {
        if (!(self.level() instanceof ServerLevel level)) {
            return false;
        }
        UUID mine = trainIdAt(self);
        if (mine == null) {
            return false;
        }
        Trains.Carriage at = carriageAtPos(level,
            candidatePos.getX() + 0.5, candidatePos.getY() + 0.5, candidatePos.getZ() + 0.5);
        return at != null && mine.equals(at.provider().getTrainId());
    }

    // ---- Recovery (behaviour #2) -----------------------------------------

    @Override
    public TrainEnvironment.ReboardTarget nearestCarriage(Entity self, double radius) {
        if (!(self.level() instanceof ServerLevel level)) {
            return null;
        }
        double px = self.getX();
        double py = self.getY();
        double pz = self.getZ();
        double radiusSq = radius * radius;
        Trains.Carriage best = null;
        AABBdc bestBox = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            AABBdc bb = c.ship().worldAABB();
            // Skip a sub-level Sable hasn't ticked yet (null / zero-volume AABB) —
            // no resolvable world position to path toward.
            if (bb == null
                    || bb.maxX() <= bb.minX() || bb.maxY() <= bb.minY() || bb.maxZ() <= bb.minZ()) {
                continue;
            }
            double distSq = distanceSqToBox(px, py, pz, bb);
            if (distSq < bestDistSq && distSq <= radiusSq) {
                bestDistSq = distSq;
                best = c;
                bestBox = bb;
            }
        }
        if (best == null) {
            return null;
        }
        AABB worldBox = new AABB(
            bestBox.minX(), bestBox.minY(), bestBox.minZ(),
            bestBox.maxX(), bestBox.maxY(), bestBox.maxZ());
        // DT's target velocity is blocks/second (the m/s speed knob); the recovery
        // goal reasons in ticks, so convert to blocks/tick (20 tps).
        Vector3dc v = best.provider().getTargetVelocity();
        Vec3 velocity = new Vec3(v.x() / 20.0, v.y() / 20.0, v.z() / 20.0);
        return new TrainEnvironment.ReboardTarget(worldBox, velocity);
    }

    /** Horizontal blocks the mob can clear in one leap when boarding from atop its tower. */
    private static final int BOARD_REACH = 3;

    @Override
    public Vec3 boardingSpot(Entity self, AABB carriageWorldBox) {
        if (!(self.level() instanceof ServerLevel level)) {
            return null;
        }
        Trains.Carriage c = nearestCarriageHandle(level, self.getX(), self.getY(), self.getZ());
        if (c == null) {
            return null;
        }
        AABBdc bb = c.ship().worldAABB();
        if (bb == null) {
            return null;
        }
        ManagedShip ship = c.ship();
        double mx = self.getX();
        double my = self.getY();
        double mz = self.getZ();
        int mobFootY = Mth.floor(my);
        int deckY = Mth.floor(bb.minY());
        // From atop its tower the mob can drop into ANY carriage column it now sits above and
        // can reach in a leap: a flatbed / open-top section, a low or half wall, an opening
        // that clears its height, or an open group-end. Scan the interior columns within jump
        // reach and pick the nearest such landing spot whose approach corridor is also clear.
        int loX = Math.max(Mth.floor(bb.minX()), Mth.floor(mx) - BOARD_REACH);
        int hiX = Math.min(Mth.ceil(bb.maxX()) - 1, Mth.floor(mx) + BOARD_REACH);
        int loZ = Mth.floor(bb.minZ());
        int hiZ = Mth.ceil(bb.maxZ()) - 1;
        Vec3 best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int xi = loX; xi <= hiX; xi++) {
            for (int zi = loZ; zi <= hiZ; zi++) {
                double wx = xi + 0.5;
                double wz = zi + 0.5;
                double hdx = wx - mx;
                double hdz = wz - mz;
                double horizSq = hdx * hdx + hdz * hdz;
                if (horizSq > (double) BOARD_REACH * BOARD_REACH) {
                    continue;                               // out of jump reach
                }
                Double footY = dropInFootY(ship, level, wx, wz, deckY, mobFootY);
                if (footY == null) {
                    continue;
                }
                if (footY > mobFootY + 1) {
                    continue;                               // landing is higher than the mob can hop up to
                }
                if (!corridorClear(ship, level, mx, mz, wx, wz, mobFootY)) {
                    continue;                               // a too-tall wall blocks the leap
                }
                double dy = footY - my;
                double distSq = horizSq + dy * dy;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = new Vec3(wx, footY, wz);
                }
            }
        }
        return best;
    }

    /**
     * Feet Y to land if the mob — standing at {@code mobFootY} atop its tower — can drop into
     * the carriage column at world {@code (wx, wz)}: a standable floor near the deck, with the
     * whole column OPEN (no collision) from that floor up through the mob's height, so nothing
     * (roof, full-height wall) blocks the drop. Covers flatbeds, open tops, low/half walls, an
     * opening that clears the mob's height, and open group-ends. {@code null} if the column is
     * roofed/walled above the mob. Reads go through {@link ManagedShip#worldToShip} — the
     * carriage's blocks live in its sub-level coordinate space.
     */
    private static Double dropInFootY(ManagedShip ship, ServerLevel level,
                                      double wx, double wz, int deckY, int mobFootY) {
        Integer floorY = null;
        for (int fy = deckY + 1; fy >= deckY - 1; fy--) {
            if (shipSolid(ship, level, wx, fy, wz)) {
                floorY = fy;
                break;
            }
        }
        if (floorY == null) {
            return null;                                    // nothing to land on
        }
        int footY = floorY + 1;
        // Open from the stand position up to (at least) the mob's height — the mob is above
        // everything here and can drop straight in.
        int top = Math.max(mobFootY, footY + 1);
        for (int y = footY; y <= top; y++) {
            if (shipSolid(ship, level, wx, y, wz)) {
                return null;
            }
        }
        return (double) footY;
    }

    /**
     * The straight horizontal corridor from {@code (fromX,fromZ)} to {@code (toX,toZ)} is open
     * at heights {@code y} and {@code y+1} — so the mob can move in at its tower height without
     * bonking a wall/roof on the way to the landing column. Sampled in ~half-block steps.
     */
    private static boolean corridorClear(ManagedShip ship, ServerLevel level,
                                         double fromX, double fromZ, double toX, double toZ, int y) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) * 2.0));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double cx = fromX + dx * t;
            double cz = fromZ + dz * t;
            if (shipSolid(ship, level, cx, y, cz) || shipSolid(ship, level, cx, y + 1, cz)) {
                return false;
            }
        }
        return true;
    }

    /** True if the carriage has no collision at world cell {@code (wx,wy,wz)} (air / passable). */
    private static boolean shipPassable(ManagedShip ship, ServerLevel level, double wx, double wy, double wz) {
        Vector3d s = ship.worldToShip(new Vector3d(wx, wy, wz));
        BlockPos p = BlockPos.containing(s.x, s.y, s.z);
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** True if the carriage has a standable (collidable) block at world cell {@code (wx,wy,wz)}. */
    private static boolean shipSolid(ManagedShip ship, ServerLevel level, double wx, double wy, double wz) {
        return !shipPassable(ship, level, wx, wy, wz);
    }

    /** Nearest carriage to {@code (px,py,pz)} with a valid world box (no radius bound), or null. */
    private static Trains.Carriage nearestCarriageHandle(ServerLevel level, double px, double py, double pz) {
        Trains.Carriage best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            AABBdc bb = c.ship().worldAABB();
            if (bb == null
                    || bb.maxX() <= bb.minX() || bb.maxY() <= bb.minY() || bb.maxZ() <= bb.minZ()) {
                continue;
            }
            double distSq = distanceSqToBox(px, py, pz, bb);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = c;
            }
        }
        return best;
    }

    // ---- Carriage exploration (behaviour #3) -----------------------------

    @Override
    public int carriageIndex(Entity self) {
        Trains.Carriage c = carriageAt(self);
        return c == null ? NO_CARRIAGE : roomPidx(c, self.getX());
    }

    @Override
    public int spawnCarriageIndex(Entity self) {
        if (!(self.level() instanceof ServerLevel level)) {
            return NO_CARRIAGE;
        }
        // A mob already riding sits at world coords — the normal world-AABB path resolves it.
        int worldIdx = carriageIndex(self);
        if (worldIdx != NO_CARRIAGE) {
            return worldIdx;
        }
        // A just-spawned mob (Dungeon Train moveTo's it before finalizeSpawn) sits at the
        // carriage's sub-level / shipyard coordinates, not its world position. Resolve the
        // owning ship there and read its group's base pIdx — precise enough for a wide depth
        // band, and it avoids re-deriving room geometry in sub-level space.
        ManagedShip ship = Shipyards.of(level).findAt(self.blockPosition());
        if (ship == null) {
            return NO_CARRIAGE;
        }
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            if (ship.equals(c.ship())) {
                return c.provider().getPIdx();
            }
        }
        return NO_CARRIAGE;
    }

    @Override
    public Vec3 nextCarriageTarget(Entity self, int dir) {
        Trains.Carriage c = carriageAt(self);
        if (c == null) {
            return null;
        }
        AABBdc bb = c.ship().worldAABB();
        if (bb == null) {
            return null;
        }
        int low = c.provider().getPIdx();
        int high = c.provider().getGroupHighestPIdx();
        int target = roomPidx(c, self.getX()) + dir;
        if (target < low || target > high) {
            return null; // next room is in another group — physical gap, behaviour #2
        }
        double roomLen = (bb.maxX() - bb.minX()) / (high - low + 1);
        // Centre of the target room in the carriage's *current* world position;
        // recomputed each tick by the caller, so it tracks the moving carriage.
        double targetX = bb.minX() + (target - low + 0.5) * roomLen;
        double centerZ = (bb.minZ() + bb.maxZ()) / 2.0;
        return new Vec3(targetX, self.getY(), centerZ);
    }

    /**
     * The adjacent group of the <em>same</em> train, just beyond {@code current}'s boundary in
     * {@code dir}: marching down ({@code dir < 0}) the same-train group whose rooms sit entirely
     * below ours, nearest to the gap; marching up ({@code dir > 0}), the nearest one above.
     * pIdx is monotonic along world-X across the whole train, so the current group is excluded
     * automatically by these range tests. {@code null} at the genuine end of the train.
     *
     * <p>Shared by {@link #nextGroupTarget} (which aims at a room in it) and
     * {@link #groupGapWidth} (which measures the clearance to it), so the two can never
     * disagree about <em>which</em> group is being crossed to.</p>
     */
    private static Trains.Carriage adjacentGroup(ServerLevel level, Trains.Carriage current, int dir) {
        UUID trainId = current.provider().getTrainId();
        int myLow = current.provider().getPIdx();
        int myHigh = current.provider().getGroupHighestPIdx();

        Trains.Carriage best = null;
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            if (c == current || !trainId.equals(c.provider().getTrainId())) {
                continue;
            }
            int cLow = c.provider().getPIdx();
            int cHigh = c.provider().getGroupHighestPIdx();
            if (dir < 0) {
                if (cHigh < myLow && (best == null || cHigh > best.provider().getGroupHighestPIdx())) {
                    best = c;
                }
            } else {
                if (cLow > myHigh && (best == null || cLow < best.provider().getPIdx())) {
                    best = c;
                }
            }
        }
        return best;
    }

    @Override
    public double groupGapWidth(Entity self, int dir) {
        if (!(self.level() instanceof ServerLevel level)) {
            return UNKNOWN_GAP;
        }
        Trains.Carriage current = carriageAt(self);
        if (current == null) {
            return UNKNOWN_GAP;
        }
        Trains.Carriage best = adjacentGroup(level, current, dir);
        if (best == null) {
            return UNKNOWN_GAP; // genuine end of the train this way
        }
        AABBdc mine = current.ship().worldAABB();
        AABBdc theirs = best.ship().worldAABB();
        if (mine == null || theirs == null) {
            return UNKNOWN_GAP;
        }
        // Marching down we sit above them, so the clearance is our min minus their max;
        // marching up, the reverse. The same minX/maxX subtraction Dungeon Train's own
        // CarriageGroupGap debug HUD uses, so the two agree on screen.
        double gap = dir < 0 ? mine.minX() - theirs.maxX()
                             : theirs.minX() - mine.maxX();
        // Clamp: overlapping or jittering AABBs must not yield a negative, which would read
        // as UNKNOWN_GAP and silently restore the full sprint jump.
        return Math.max(0.0, gap);
    }

    @Override
    public Vec3 nextGroupTarget(Entity self, int dir) {
        if (!(self.level() instanceof ServerLevel level)) {
            return null;
        }
        Trains.Carriage current = carriageAt(self);
        if (current == null) {
            return null;
        }
        Trains.Carriage best = adjacentGroup(level, current, dir);
        if (best == null) {
            return null; // genuine end of the train this way
        }
        AABBdc bb = best.ship().worldAABB();
        if (bb == null) {
            return null;
        }
        int bLow = best.provider().getPIdx();
        int bHigh = best.provider().getGroupHighestPIdx();
        int rooms = bHigh - bLow + 1;
        if (rooms <= 0) {
            return null;
        }
        // The room facing the gap: the high-pIdx (max-X) room when we approach from
        // above (dir < 0), the low-pIdx (min-X) room when we approach from below.
        int room = dir < 0 ? bHigh : bLow;
        double roomLen = (bb.maxX() - bb.minX()) / rooms;
        double targetX = bb.minX() + (room - bLow + 0.5) * roomLen;
        double centerZ = (bb.minZ() + bb.maxZ()) / 2.0;
        return new Vec3(targetX, self.getY(), centerZ);
    }

    @Override
    public void openBlockingDoor(Entity self) {
        if (!(self.level() instanceof ServerLevel level)) {
            return;
        }
        Trains.Carriage c = carriageAt(self);
        if (c == null) {
            return; // not on a train — leave doors to PlayerMobDoorGoal
        }
        if (self instanceof Mob gazer) {
            applyControlGaze(gazer); // keep facing + punching a just-operated control — a deliberate look
        }
        if (!(self instanceof PlayerMobEntity playerMob)) {
            return;
        }
        // The carriage's blocks live in the sub-level coordinate space (where the navigation
        // paths), not at the mob's apparent world position — convert there to find the door, and
        // measure the heading there too so the carriage's carry-along drift is stripped out.
        Vector3d sub = c.ship().worldToShip(new Vector3d(self.getX(), self.getY(), self.getZ()));
        BlockPos subPos = BlockPos.containing(sub.x, sub.y, sub.z);

        // Path-aware: only touch a door that actually blocks the way the mob is going, toggling it
        // to whichever state clears that axis (a closed door blocks its facing axis, an open one the
        // perpendicular axis). No confident heading yet ⇒ leave every door alone this tick.
        Direction.Axis travelAxis = DoorObstruction.travelAxis(playerMob, sub.x, sub.z);
        if (travelAxis == null) {
            return;
        }

        long[] cooldown = DOOR_COOLDOWN.get(self);
        if (cooldown != null && cooldown[1] > 0) {
            cooldown[1]--;
        }

        // Hand-openable doors (wooden/copper) toggle directly. Try the sub-level position first,
        // then the world position in case a build projects carriage blocks at the apparent location.
        DoorObstruction.Obstruction hand =
            DoorObstruction.nearestObstructing(level, subPos, DOOR_REACH, travelAxis, DoorObstruction.HAND_DOOR);
        if (hand == null) {
            hand = DoorObstruction.nearestObstructing(
                level, self.blockPosition(), DOOR_REACH, travelAxis, DoorObstruction.HAND_DOOR);
        }
        if (hand != null) {
            long posLong = hand.pos().asLong();
            BlockPos doorPos = hand.pos();
            boolean desiredOpen = !hand.state().getValue(DoorBlock.OPEN);
            boolean coolingSameDoor = cooldown != null && cooldown[0] == posLong && cooldown[1] > 0;
            // Don't slam a door shut on a companion. The "close it to clear my perpendicular axis"
            // reflex, run independently per mob, otherwise fights another mob's "open it to clear my
            // facing axis" reflex on the SAME door — two PlayerMobs flapping one door open/closed
            // (now common when a pair travels together). So a mob holds off *closing* while another
            // PlayerMob is right beside it; whoever's left closes it once alone. Opening is never held.
            boolean holdOpenForCompanion = !desiredOpen && anotherPlayerMobBeside(level, playerMob);
            if (!coolingSameDoor && !holdOpenForCompanion) {
                // Defer the open/close into a deliberate window: the mob faces the door, swings, then
                // operates it, interrupting combat/movement (DoorOperationGoal) rather than flipping it
                // silently. The eye→door offset is taken in the carriage's sub-level frame, which equals
                // the world-frame offset (rotation is locked to identity), so it's the right look target.
                Vector3d subEye = c.ship().worldToShip(new Vector3d(self.getX(), self.getEyeY(), self.getZ()));
                playerMob.beginDoorOperation(
                    doorPos.getX() + 0.5 - subEye.x,
                    doorPos.getY() + 0.5 - subEye.y,
                    doorPos.getZ() + 0.5 - subEye.z,
                    () -> DoorObstruction.setOpen(self, level, doorPos, desiredOpen));
                DOOR_COOLDOWN.put(self, new long[]{posLong, DOOR_TOGGLE_COOLDOWN});
            }
            return;
        }

        // Iron doors are redstone-only — open a closed one that's blocking us via its button/lever.
        // But never the group-boundary door: opening it would walk the mob into the inter-group gap
        // (crossing it is behaviour #2). nextCarriageTarget is null there.
        if (atForwardBoundary(self)) {
            return;
        }
        DoorObstruction.Obstruction iron =
            DoorObstruction.nearestObstructing(level, subPos, DOOR_REACH, travelAxis, DoorObstruction.CLOSED_IRON_DOOR);
        if (iron != null && operateControlNear(playerMob, level, c, iron.pos())) {
            playerMob.interruptForDoorOperation(); // pause combat/movement for the control press too
        }
    }

    /**
     * Whether another {@link PlayerMobEntity} is right beside {@code self} (within {@link
     * #DOOR_COMPANION_REACH}). Used to hold a door open rather than close it on a companion —
     * two mobs otherwise flap a shared door, each closing it to clear its own path while the
     * other reopens it.
     */
    private static boolean anotherPlayerMobBeside(ServerLevel level, PlayerMobEntity self) {
        AABB box = self.getBoundingBox().inflate(DOOR_COMPANION_REACH);
        return !level.getEntitiesOfClass(PlayerMobEntity.class, box, other -> other != self).isEmpty();
    }

    /**
     * Operate the nearest unpowered button/lever the mob can actually reach and see within
     * {@link #CONTROL_REACH} of {@code doorPos}; returns true if one was operated. The mob must
     * be within {@link #INTERACT_REACH} of the control and have a clear line of sight to it — it
     * never powers a control through a wall — and it turns to face the control and swings its
     * arm, so the press is a visible interaction rather than a remote toggle.
     *
     * <p>Only OFF controls are touched: a lever already holding a door open is left alone, and an
     * auto-unpressing button is re-pressed on a later tick (this reflex runs every tick), keeping
     * the iron door open until the mob has passed through.</p>
     */
    private static boolean operateControlNear(Mob mob, ServerLevel level, Trains.Carriage c, BlockPos doorPos) {
        Vector3d eyeShip = c.ship().worldToShip(new Vector3d(mob.getX(), mob.getEyeY(), mob.getZ()));
        Vec3 eye = new Vec3(eyeShip.x, eyeShip.y, eyeShip.z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState bestState = null;
        BlockPos bestPos = null;
        Vec3 bestCenter = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -CONTROL_REACH; dy <= CONTROL_REACH; dy++) {
            for (int dx = -CONTROL_REACH; dx <= CONTROL_REACH; dx++) {
                for (int dz = -CONTROL_REACH; dz <= CONTROL_REACH; dz++) {
                    cursor.set(doorPos.getX() + dx, doorPos.getY() + dy, doorPos.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!isOffControl(state)) {
                        continue;
                    }
                    Vec3 center = Vec3.atCenterOf(cursor);
                    double distSq = eye.distanceToSqr(center);
                    if (distSq > INTERACT_REACH_SQR || distSq >= bestDistSq) {
                        continue; // out of arm's reach, or farther than a candidate we have
                    }
                    if (!hasLineOfSight(level, mob, eye, center, cursor.immutable())) {
                        continue; // can't see it — don't power it through a wall
                    }
                    bestDistSq = distSq;
                    bestPos = cursor.immutable();
                    bestState = state;
                    bestCenter = center;
                }
            }
        }
        if (bestState == null) {
            return false;
        }
        // Turn to face the control and swing, then hold the gaze + repeated swing for a short
        // window so the look and punch read deliberately. Carriage rotation is locked to identity,
        // so the sub-frame eye→control offset is the world-frame offset.
        Vec3 offset = bestCenter.subtract(eye);
        mob.getLookControl().setLookAt(mob.getX() + offset.x, mob.getEyeY() + offset.y, mob.getZ() + offset.z);
        CONTROL_GAZE.put(mob, new double[]{CONTROL_GAZE_TICKS, offset.x, offset.y, offset.z});
        mob.swing(InteractionHand.MAIN_HAND);
        if (bestState.getBlock() instanceof LeverBlock lever) {
            lever.pull(bestState, level, bestPos, null);
        } else if (bestState.getBlock() instanceof ButtonBlock button) {
            button.press(bestState, level, bestPos, null);
        }
        return true;
    }

    /**
     * Keep {@code mob} looking at — and swinging its arm at — the control it most recently
     * operated, for a short window, so the interaction reads as a deliberate "look at it and
     * punch it" rather than a one-tick glance the movement goal immediately overrides. Runs every
     * tick from {@link #openBlockingDoor} (called after the goal selector), so this look wins.
     * {@link Mob#swing} self-gates to a punch roughly every half-swing, so calling it each tick
     * yields a visible repeated arm-swing. The stored offset is mob-relative, so it tracks the
     * moving carriage and the mob's own drift.
     */
    private static void applyControlGaze(Mob mob) {
        double[] g = CONTROL_GAZE.get(mob);
        if (g == null) {
            return;
        }
        if (g[0] <= 0) {
            CONTROL_GAZE.remove(mob);
            return;
        }
        g[0] -= 1;
        mob.getLookControl().setLookAt(mob.getX() + g[1], mob.getEyeY() + g[2], mob.getZ() + g[3]);
        mob.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * True if {@code mob} has a clear line of sight from {@code eye} to the control at
     * {@code targetPos} (centre {@code target}), both in sub-level coordinates. Buttons and
     * levers have little or no collision, so a clear shot registers as a MISS or as a hit
     * at/after the control's distance (e.g. the wall it's mounted on); only a solid block
     * strictly in front of the control counts as blocked.
     */
    private static boolean hasLineOfSight(ServerLevel level, Mob mob, Vec3 eye, Vec3 target, BlockPos targetPos) {
        BlockHitResult hit = level.clip(
            new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS
            || hit.getBlockPos().equals(targetPos)
            || hit.getLocation().distanceToSqr(eye) + 1.0e-4 >= target.distanceToSqr(eye);
    }

    /** True for an unpowered (OFF) button or lever — a control that turns power on when operated. */
    private static boolean isOffControl(BlockState state) {
        if (state.getBlock() instanceof ButtonBlock) {
            return !state.getValue(ButtonBlock.POWERED);
        }
        if (state.getBlock() instanceof LeverBlock) {
            return !state.getValue(LeverBlock.POWERED);
        }
        return false;
    }

    /**
     * True if {@code self} is at the forward (march-direction) edge of its carriage group,
     * where the only door ahead is the boundary door into the inter-group gap. Read from
     * the mob's latched explore direction; {@link #nextCarriageTarget} is {@code null} when
     * no further room exists in that direction within the group.
     */
    private boolean atForwardBoundary(Entity self) {
        if (!(self instanceof PlayerMobEntity mob)) {
            return false;
        }
        int dir = mob.getTrainExploreDir();
        return dir != 0 && nextCarriageTarget(self, dir) == null;
    }

    // ---- Dig through a blocked carriage (pass through ice/dirt/mud/moss/log fill) ----

    /** Swing the arm roughly this often (ticks) while mining, so it reads as repeated digging. */
    private static final int DIG_SWING_INTERVAL = 4;

    /**
     * Keep a dig episode alive this long (ticks) after the way ahead clears, so the mob can step
     * into the next slice of a thick fill before the episode ends rather than re-waiting the full
     * stuck window per block. ~1s.
     */
    private static final int EPISODE_CLEAR_GRACE = 20;

    /**
     * Per-mob dig progress: a stuck detector (fed the carriage's sub-level coords so the
     * carry-along drift is stripped out), whether a stuck-triggered episode is active, and the
     * current target block + break timing. Server-thread only; weak keys so entries vanish with the
     * mob, exactly like {@link #CONTROL_GAZE}/{@link #DOOR_COOLDOWN}.
     */
    private static final class DigState {
        final DoorStuckMonitor stuck = new DoorStuckMonitor();
        boolean episode;        // true once stuck-triggered, until the way ahead stays clear
        int clearTicks;         // consecutive ticks with nothing diggable ahead (mob stepping through)
        BlockPos target;        // sub-level block being mined, or null
        int breakTicks;         // ticks spent on the current target
        int breakTotal;         // ticks the current target needs (0 = not sized yet)
        boolean swappedTool;    // best tool already equipped for the current target
    }

    private static final Map<Entity, DigState> DIG_STATE = new WeakHashMap<>();

    /**
     * Per-tick reflex: if the mob is stuck against soft fill (ice/dirt/mud/moss/log) packing the
     * carriage directly ahead of its march, mine it — auto-swapping to the best tool it owns and
     * pacing a realistic, hardness-scaled break — so it can pass through. Returns {@code true}
     * while digging. The carriage's blocks live in its sub-level coordinate space (not at the mob's
     * world position), so this reads/destroys through {@link ManagedShip#worldToShip}, the same way
     * {@link #openBlockingDoor} reaches a door — which is why it's a reflex, not a goal.
     */
    @Override
    public boolean digObstructingBlock(Entity self) {
        if (!(self.level() instanceof ServerLevel level) || !(self instanceof PlayerMobEntity mob)) {
            return false;
        }
        // Feature + world-mutation gates, matching the recovery/raid goals.
        if (!PlayerMobConfig.trainDigThrough()
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            endDig(level, mob);
            return false;
        }
        Trains.Carriage c = carriageAt(self);
        int dir = mob.getTrainExploreDir();
        // Off a train, no latched heading, or at the forward group boundary (the wall ahead is the
        // inter-group gap door — crossing it is CrossGroupGapGoal's job) ⇒ never dig.
        if (c == null || dir == 0 || atForwardBoundary(self)) {
            endDig(level, mob);
            return false;
        }
        ManagedShip ship = c.ship();
        DigState st = DIG_STATE.computeIfAbsent(self, k -> new DigState());

        // "Only when stuck": progress is measured in the carriage's own frame (the train carries the
        // mob's world position forward even while it's wedged), so feed the monitor the sub-level x/z.
        // The mob is "trying to move" whenever it isn't busy fighting — a confined mob always wants to
        // march on. The fill-ahead check below is the real safety, so an eager stuck reading is benign.
        Vector3d sub = ship.worldToShip(new Vector3d(self.getX(), self.getY(), self.getZ()));
        if (st.stuck.tick(sub.x, sub.z, mob.getTarget() == null)) {
            st.episode = true;
        }
        if (!st.episode) {
            endDig(level, mob);
            return false;
        }

        // The diggable fill block directly ahead along the march axis (foot cell first, then head —
        // a 2-high passage). None ⇒ the immediate slice is clear.
        BlockPos targetSub = diggableAhead(ship, level, mob, dir);
        if (targetSub == null) {
            // For a thick fill the mob now steps forward into the next slice, so hold the episode
            // briefly rather than re-waiting the full stuck window per block; only once the way has
            // stayed clear (the mob walked out the far side) is the dig genuinely done.
            mob.setDigging(false);
            clearCrack(level, mob, st.target);
            st.target = null;
            if (++st.clearTicks >= EPISODE_CLEAR_GRACE) {
                endDig(level, mob);
            }
            return false;
        }
        st.clearTicks = 0;

        BlockState state = level.getBlockState(targetSub);
        if (!targetSub.equals(st.target)) {            // a new block to mine — reset the break
            clearCrack(level, mob, st.target);
            st.target = targetSub;
            st.breakTicks = 0;
            st.breakTotal = 0;
            st.swappedTool = false;
        }
        if (!st.swappedTool) {
            mob.equipBetterToolFor(state);             // auto-swap to the best tool the mob carries
            st.swappedTool = true;
        }
        if (st.breakTotal == 0) {
            st.breakTotal = MiningMath.breakTicks(state, level, targetSub, mob.getMainHandItem());
        }

        mob.setDigging(true);
        // Look forward at the wall (post-selector, so this look wins, like applyControlGaze): the
        // block is one step ahead along world-X (== sub-level X — carriage rotation is identity).
        mob.getLookControl().setLookAt(self.getX() + dir, self.getEyeY() - 0.2, self.getZ());
        if (st.breakTicks % DIG_SWING_INTERVAL == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        st.breakTicks++;
        level.destroyBlockProgress(mob.getId(), targetSub,
            Math.min(9, (int) (st.breakTicks / (double) st.breakTotal * 10.0)));   // cracking overlay
        if (st.breakTicks >= st.breakTotal) {
            // Break sound at the mob's WORLD position — reliable feedback even though the block's own
            // break particles fire at its (far-offset) sub-level coords and may not be visible.
            level.playSound(null, self.blockPosition(), state.getSoundType().getBreakSound(),
                SoundSource.BLOCKS, 1.0F, 0.85F + mob.getRandom().nextFloat() * 0.1F);
            level.destroyBlock(targetSub, /* dropBlock */ true, mob);   // vanilla break: drops on the floor
            clearCrack(level, mob, targetSub);
            st.target = null;
            st.breakTicks = 0;
            st.breakTotal = 0;
            st.swappedTool = false;
            // Stay in the episode: next tick re-scans the next column of fill, or clears.
        }
        return true;
    }

    /**
     * The sub-level position of the diggable fill block directly ahead of {@code mob} along its
     * march axis (leg cell first, then head — a 2-high passage), or {@code null} if the way ahead
     * is clear / not soft fill. Reads in the carriage's sub-level frame ({@link ManagedShip#worldToShip}):
     * the blocks live there, not at the mob's world position, and Dungeon Train locks carriage
     * rotation to identity, so world ±X equals sub ±X.
     *
     * <p><b>Never the bottom layer.</b> The scan starts at the <em>leg</em> row — the block whose
     * bottom sits at the walking surface, taken as {@code round(feetY)} so a hair of collision sink
     * or a half-height (slab) floor can't drop it onto the floor block — and only goes up from there.
     * Nothing at or below the floor (deck) is ever a target, so a packed carriage's bottom layer is
     * never mined out from under the train (themed DT floors can themselves be dirt/moss/slab fill).
     * Also requires real collision so passable decoration (e.g. moss carpet) isn't treated as a wall,
     * and excludes the protected stone-brick bed / rails.</p>
     */
    private static BlockPos diggableAhead(ManagedShip ship, ServerLevel level, PlayerMobEntity mob, int dir) {
        Vector3d feet = ship.worldToShip(new Vector3d(mob.getX(), mob.getY(), mob.getZ()));
        int aheadX = Mth.floor(feet.x) + dir;
        int subZ = Mth.floor(feet.z);
        // Leg row, rounded so it's always the block ABOVE the floor — never the floor/deck itself.
        int legY = Mth.floor(feet.y + 0.5);
        for (int dy = 0; dy <= 1; dy++) {                       // legs then head — a 2-high passage
            BlockPos subPos = new BlockPos(aheadX, legY + dy, subZ);
            BlockState state = level.getBlockState(subPos);
            if (state.getCollisionShape(level, subPos).isEmpty()) {
                continue;                                       // passable — not actually blocking
            }
            if (BlockSourcePolicy.isProtectedTrackBlock(state)) {
                continue;                                       // never the bed/rails
            }
            if (TrainDigPolicy.isDiggableObstruction(state)) {
                return subPos;
            }
        }
        return null;
    }

    /** Clear the cracking overlay for {@code pos} (if any) on {@code mob}'s break channel. */
    private static void clearCrack(ServerLevel level, PlayerMobEntity mob, BlockPos pos) {
        if (pos != null) {
            level.destroyBlockProgress(mob.getId(), pos, -1);
        }
    }

    /** Stop any in-progress dig: clear the overlay, drop the target + episode, and lower the flag. */
    private static void endDig(ServerLevel level, PlayerMobEntity mob) {
        DigState st = DIG_STATE.get(mob);
        if (st != null) {
            clearCrack(level, mob, st.target);
            st.episode = false;
            st.clearTicks = 0;
            st.target = null;
            st.breakTicks = 0;
            st.breakTotal = 0;
            st.swappedTool = false;
        }
        mob.setDigging(false);
    }

    // ---- Resolution ------------------------------------------------------

    /** The train id of the carriage {@code self} is riding, or {@code null}. */
    private static UUID trainIdAt(Entity self) {
        Trains.Carriage c = carriageAt(self);
        return c == null ? null : c.provider().getTrainId();
    }

    /** The carriage group {@code self} is currently riding, or {@code null} if not on a train. */
    private static Trains.Carriage carriageAt(Entity self) {
        if (!(self.level() instanceof ServerLevel level)) {
            return null;
        }
        return carriageAtPos(level, self.getX(), self.getY(), self.getZ());
    }

    /** The carriage whose current world box contains {@code (x,y,z)} (inflated by {@link #RIDE_MARGIN}). */
    private static Trains.Carriage carriageAtPos(ServerLevel level, double x, double y, double z) {
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            AABBdc bb = c.ship().worldAABB();
            if (bb != null && contains(bb, x, y, z)) {
                return c;
            }
        }
        return null;
    }

    private static boolean contains(AABBdc bb, double x, double y, double z) {
        return x >= bb.minX() - RIDE_MARGIN && x <= bb.maxX() + RIDE_MARGIN
            && y >= bb.minY() - RIDE_MARGIN && y <= bb.maxY() + RIDE_MARGIN
            && z >= bb.minZ() - RIDE_MARGIN && z <= bb.maxZ() + RIDE_MARGIN;
    }

    /** Squared distance from {@code (px,py,pz)} to the nearest point on {@code bb}. */
    private static double distanceSqToBox(double px, double py, double pz, AABBdc bb) {
        double dx = Math.max(Math.max(bb.minX() - px, 0.0), px - bb.maxX());
        double dy = Math.max(Math.max(bb.minY() - py, 0.0), py - bb.maxY());
        double dz = Math.max(Math.max(bb.minZ() - pz, 0.0), pz - bb.maxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * The mob's signed carriage index: map its world-X across the group's world AABB
     * onto the group's pIdx range, clamped into {@code [low, high]} (the mob is inside
     * this group's box, so its index must lie within the range).
     */
    private static int roomPidx(Trains.Carriage c, double worldX) {
        int low = c.provider().getPIdx();
        int high = c.provider().getGroupHighestPIdx();
        AABBdc bb = c.ship().worldAABB();
        int rooms = high - low + 1;
        if (bb == null || rooms <= 0) {
            return low;
        }
        double roomLen = (bb.maxX() - bb.minX()) / rooms;
        if (roomLen <= 0) {
            return low;
        }
        int p = low + (int) Math.round((worldX - bb.minX()) / roomLen - 0.5);
        return Math.max(low, Math.min(high, p));
    }
}
//?}
