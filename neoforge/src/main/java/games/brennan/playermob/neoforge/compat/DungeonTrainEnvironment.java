package games.brennan.playermob.neoforge.compat;

import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.compat.TrainEnvironment;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
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
 * doors are hand-openable and opened directly; iron doors are redstone-only, so their
 * nearest button or lever is operated instead. The group-boundary door is left shut
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
        BlockPos subPos = BlockPos.containing(sub.x, sub.y, sub.z);
        // Hand-openable doors (wooden/copper) open directly.
        if (tryOpenDoorNear(self, level, subPos) || tryOpenDoorNear(self, level, self.blockPosition())) {
            return;
        }
        // Iron doors are redstone-only — operate their adjacent button/lever instead.
        // But never the group-boundary door: opening it would walk the mob into the
        // inter-group gap (crossing it is behaviour #2). nextCarriageTarget is null there.
        if (atForwardBoundary(self)) {
            return;
        }
        if (tryOperateIronDoorControl(level, subPos)) {
            return;
        }
        tryOperateIronDoorControl(level, self.blockPosition());
    }

    /**
     * Open one closed, hand-openable door (wooden or copper) within {@link #DOOR_REACH}
     * of {@code base}; returns true if one was opened. Iron doors are redstone-only and
     * skipped here — {@link #tryOperateIronDoorControl} drives their button/lever instead.
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

    /**
     * Find a closed iron door within {@link #DOOR_REACH} of {@code base} and operate its
     * nearest button/lever control; returns true if a control was operated. Iron doors are
     * redstone-only, so {@link #tryOpenDoorNear} can't open them by hand — we drive their
     * control instead, in the same sub-level coordinate space.
     */
    private static boolean tryOperateIronDoorControl(ServerLevel level, BlockPos base) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -DOOR_REACH; dx <= DOOR_REACH; dx++) {
                for (int dz = -DOOR_REACH; dz <= DOOR_REACH; dz++) {
                    cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(Blocks.IRON_DOOR)
                            && state.getBlock() instanceof DoorBlock door
                            && !door.isOpen(state)
                            && operateControlNear(level, cursor.immutable())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Press/pull the nearest unpowered button or lever within {@link #CONTROL_REACH} of
     * {@code doorPos}; returns true if one was operated. Only OFF controls are touched, so
     * a lever already holding a door open is left alone, and an auto-unpressing button is
     * simply re-pressed on a later tick (this reflex runs every tick) — keeping the iron
     * door open until the mob has passed through.
     */
    private static boolean operateControlNear(ServerLevel level, BlockPos doorPos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState bestState = null;
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -CONTROL_REACH; dy <= CONTROL_REACH; dy++) {
            for (int dx = -CONTROL_REACH; dx <= CONTROL_REACH; dx++) {
                for (int dz = -CONTROL_REACH; dz <= CONTROL_REACH; dz++) {
                    cursor.set(doorPos.getX() + dx, doorPos.getY() + dy, doorPos.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!isOffControl(state)) {
                        continue;
                    }
                    double distSq = cursor.distSqr(doorPos);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestPos = cursor.immutable();
                        bestState = state;
                    }
                }
            }
        }
        if (bestState == null) {
            return false;
        }
        if (bestState.getBlock() instanceof LeverBlock lever) {
            lever.pull(bestState, level, bestPos, null);
        } else if (bestState.getBlock() instanceof ButtonBlock button) {
            button.press(bestState, level, bestPos, null);
        }
        return true;
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
