package games.brennan.playermob.neoforge.compat;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.compat.TrainEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;

import java.util.UUID;

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
 * doors are hand-openable and opened directly; iron doors are redstone-only and left
 * for a follow-up (button/lever/pressure-plate operation).</p>
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

    @Override
    public void openBlockingDoor(Entity self) {
        if (!(self.level() instanceof ServerLevel level)) {
            return;
        }
        Trains.Carriage c = carriageAt(self);
        if (c == null) {
            return; // not on a train — leave doors to PlayerMobDoorGoal
        }
        // The carriage's blocks live in the sub-level coordinate space (where the
        // navigation paths), not at the mob's apparent world position — convert there
        // and look for a door the mob is up against. Fall back to the world position in
        // case a build projects carriage blocks at the apparent location.
        Vector3d sub = c.ship().worldToShip(new Vector3d(self.getX(), self.getY(), self.getZ()));
        if (tryOpenDoorNear(self, level, BlockPos.containing(sub.x, sub.y, sub.z))) {
            return;
        }
        tryOpenDoorNear(self, level, self.blockPosition());
    }

    /**
     * Open one closed, hand-openable door (wooden or copper) within {@link #DOOR_REACH}
     * of {@code base}; returns true if one was opened. Iron doors are redstone-only and
     * skipped here — operating their button/lever is a separate follow-up.
     */
    private static boolean tryOpenDoorNear(Entity self, ServerLevel level, BlockPos base) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -DOOR_REACH; dx <= DOOR_REACH; dx++) {
                for (int dz = -DOOR_REACH; dz <= DOOR_REACH; dz++) {
                    cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof DoorBlock door)
                            || door.isOpen(state)
                            || state.is(Blocks.IRON_DOOR)) {
                        continue;
                    }
                    door.setOpen(self, level, state, cursor.immutable(), true);
                    return true;
                }
            }
        }
        return false;
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
