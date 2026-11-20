package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * What the reading and writing goals share: finding the shelves a mob is walking past, judging
 * whether it can see them, and whether it is calm enough for a book at all.
 */
final class BookGoalSupport {

    /** A shelf this close (horizontally), with a clear line of sight, is one the mob is passing. */
    static final int PASS_RADIUS = 3;
    /** …and within this many blocks up or down. */
    static final int PASS_HEIGHT = 2;
    /** No books with an attacker or a feared entity this close. */
    static final double THREAT_RANGE = 12.0;
    /** Chiseled bookshelf slot count. */
    static final int CHISELED_SLOTS = 6;

    private BookGoalSupport() {}

    /**
     * The shelves in passing range that {@code isShelf} accepts and the mob can see and reach,
     * nearest first, which {@code memory} hasn't seen during this pass. Each returned shelf has
     * now been seen, so it isn't offered again until the mob has been away from it a while.
     */
    static List<BlockPos> newlyPassed(PlayerMobEntity mob, PassMemory memory,
                                      BiPredicate<Level, BlockPos> isShelf) {
        Level level = mob.level();
        BlockPos origin = mob.blockPosition();
        Vec3 eye = mob.getEyePosition();
        long now = mob.tickCount;
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -PASS_RADIUS; dx <= PASS_RADIUS; dx++) {
            for (int dy = -PASS_HEIGHT; dy <= PASS_HEIGHT; dy++) {
                for (int dz = -PASS_RADIUS; dz <= PASS_RADIUS; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!isShelf.test(level, cursor)) continue;
                    if (!TrainConfinement.allowsTarget(mob, cursor)) continue;
                    BlockPos pos = cursor.immutable();
                    if (!canSee(mob, eye, pos)) continue;
                    if (memory.firstSight(pos.asLong(), now)) out.add(pos);
                }
            }
        }
        out.sort(Comparator.comparingDouble(origin::distSqr));
        return out;
    }

    /** Line of sight to the shelf's face, as {@code RaidContainersGoal} judges a chest. */
    static boolean canSee(PlayerMobEntity mob, Vec3 eye, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = mob.level().clip(new ClipContext(
            eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS
            || hit.getBlockPos().equals(pos)
            || hit.getLocation().distanceToSqr(eye) + 1.0e-4 >= center.distanceToSqr(eye);
    }

    /** The chiseled shelf's slots holding a stack {@code wanted} accepts. */
    static List<Integer> slotsMatching(ChiseledBookShelfBlockEntity shelf, Predicate<ItemStack> wanted) {
        List<Integer> out = new ArrayList<>();
        for (int slot = 0; slot < CHISELED_SLOTS; slot++) {
            ItemStack stack = shelf.getItem(slot);
            if (!stack.isEmpty() && wanted.test(stack)) out.add(slot);
        }
        return out;
    }

    /** Nothing more pressing going on: no fight, no hurt, no flight, no digging, gap or door. */
    static boolean isCalm(PlayerMobEntity mob) {
        return mob.isAlive()
            && mob.getTarget() == null
            && mob.hurtTime == 0
            && !mob.isFleeing()
            && !mob.isDigging()
            && !mob.isCrossingGap()
            && !mob.isOperatingDoor()
            && !mob.isRecovering()
            && !mob.hasMenuViewers();
    }

    /** Someone nearby it would fight or flee — no time for a book. */
    static boolean isThreatened(PlayerMobEntity mob) {
        return mob.nearestWhereReaction(Reaction.FIGHT, THREAT_RANGE) != null
            || mob.nearestWhereReaction(Reaction.FLEE, THREAT_RANGE) != null;
    }

    /** Eyes on the book in hand: a point just ahead of and below the face. */
    static void lookAtHeldBook(PlayerMobEntity mob) {
        Vec3 look = mob.getLookAngle();
        mob.getLookControl().setLookAt(mob.getX() + look.x, mob.getEyeY() - 0.6, mob.getZ() + look.z);
    }
}
