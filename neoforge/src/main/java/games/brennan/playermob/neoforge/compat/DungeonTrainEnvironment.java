package games.brennan.playermob.neoforge.compat;

import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.compat.TrainEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
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
    public Vec3 nextGroupTarget(Entity self, int dir) {
        if (!(self.level() instanceof ServerLevel level)) {
            return null;
        }
        Trains.Carriage current = carriageAt(self);
        if (current == null) {
            return null;
        }
        UUID trainId = current.provider().getTrainId();
        int myLow = current.provider().getPIdx();
        int myHigh = current.provider().getGroupHighestPIdx();

        // The adjacent group of the *same* train, just beyond our boundary in `dir`:
        // marching down (dir < 0) we want the same-train group whose rooms sit
        // entirely below ours, nearest to the gap; marching up (dir > 0), the nearest
        // one above. pIdx is monotonic along world-X across the whole train, so the
        // current group is excluded automatically by these range tests.
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
