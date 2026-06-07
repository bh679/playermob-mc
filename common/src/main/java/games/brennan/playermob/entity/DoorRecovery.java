package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The "close a door that's blocking me" half of door handling — the inverse of the
 * open logic in {@code PlayerMobDoorGoal} / {@code DungeonTrainEnvironment}.
 *
 * <p>A door panel always occupies one of two perpendicular edges of its cell:
 * <em>closed</em> it lies across the doorway, <em>open</em> it has swung 90° and
 * lies across the perpendicular edge. So an open door clears the doorway direction
 * but blocks the perpendicular path through the same cell — and when a mob's route
 * runs perpendicular (a corner, an L-/T-junction), the open door is the obstacle and
 * closing it clears the way. Vanilla pathfinding never models which edge the panel
 * occupies (it sees "open = passable"), so the recovery is driven externally, on a
 * stuck signal — see {@link DoorStuckMonitor}.</p>
 *
 * <p>Closes only <em>hand-closable</em> doors (wooden + copper); iron doors are
 * redstone-only and a closed iron door isn't pathable, so closing one could strand
 * the mob — they're left to the open-via-control logic instead, mirroring the
 * iron-skip in {@code DungeonTrainEnvironment.tryOpenDoorNear}.</p>
 *
 * <p>Coordinate-frame agnostic: the caller passes {@code base} in whatever frame the
 * doors live in — world space off a train, or the carriage's sub-level space on a
 * moving Dungeon Train carriage. Vanilla types only, so it compiles on every loader.</p>
 */
public final class DoorRecovery {

    private DoorRecovery() {}

    /**
     * Close the <em>nearest</em> open, hand-closable door within {@code reach} blocks of
     * {@code base} (checking both door halves). Iron doors are skipped.
     *
     * @param closer the mob credited with the close (passed to {@link DoorBlock#setOpen})
     * @param level  the level whose blocks are addressed by {@code base}
     * @param base   the search centre, in the same coordinate frame as {@code level}'s blocks
     * @param reach  half-width of the cube scanned around {@code base}
     * @return {@code true} if a door was closed
     */
    public static boolean closeBlockingOpenDoor(Entity closer, Level level, BlockPos base, int reach) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        DoorBlock bestDoor = null;
        BlockState bestState = null;
        BlockPos bestPos = null;
        long bestDistSq = Long.MAX_VALUE;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof DoorBlock door)
                            || !door.isOpen(state)
                            || state.is(Blocks.IRON_DOOR)) {
                        continue;
                    }
                    long distSq = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestDoor = door;
                        bestState = state;
                        bestPos = cursor.immutable();
                    }
                }
            }
        }
        if (bestDoor == null) {
            return false;
        }
        bestDoor.setOpen(closer, level, bestState, bestPos, false);
        return true;
    }
}
