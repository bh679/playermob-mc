package games.brennan.playermob.neoforge.compat;

import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.compat.TrainEnvironment;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
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
        if (self instanceof Mob gazer) {
            applyControlGaze(gazer); // keep facing + punching a just-operated control — a deliberate look
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
        if (self instanceof Mob mob) {
            tryOperateIronDoorControl(mob, level, c, subPos);
        }
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
     * nearest reachable, visible button/lever control; returns true if a control was operated.
     * Iron doors are redstone-only, so {@link #tryOpenDoorNear} can't open them by hand — we
     * drive their control instead, in the same sub-level coordinate space.
     */
    private static boolean tryOperateIronDoorControl(Mob mob, ServerLevel level, Trains.Carriage c, BlockPos base) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -DOOR_REACH; dx <= DOOR_REACH; dx++) {
                for (int dz = -DOOR_REACH; dz <= DOOR_REACH; dz++) {
                    cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(Blocks.IRON_DOOR)
                            && state.getBlock() instanceof DoorBlock door
                            && !door.isOpen(state)
                            && operateControlNear(mob, level, c, cursor.immutable())) {
                        return true;
                    }
                }
            }
        }
        return false;
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
