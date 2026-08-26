package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.FakePlayerSource;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Performs a commanded {@link OrderType#USE} — a real right-click of an item on a target
 * entity or at a position — by borrowing the level's fake player ({@link FakePlayerSource}).
 * A PlayerMob has no player of its own, but item/entity interactions are written against
 * {@link net.minecraft.world.entity.player.Player}.
 *
 * <p>To work with <b>modded</b> items as well as vanilla, the interaction is driven through the
 * full server interaction pipeline — {@code ServerPlayerGameMode.useItemOn / useItem} and
 * {@code Player.interactOn} — so the loaders' interaction events fire (Forge/NeoForge
 * {@code PlayerInteractEvent}, Fabric API {@code UseBlock/UseItem/UseEntityCallback}), which is
 * where most modded item logic lives. The mob's <em>live</em> held stack is used (not a copy) and
 * written back afterwards, so stateful items (buckets, energy/fluid, durability) reflect their
 * result. NeoForge supplies a real {@code FakePlayer} via {@link FakePlayerSource} for
 * capability- and {@code instanceof FakePlayer}-aware mods.</p>
 *
 * <p>Still best-effort: items needing a client/screen or a specific player identity won't work.</p>
 */
public final class CommandedUse {

    private CommandedUse() {}

    /** Drive the level's fake player to right-click the mob's held item on {@code targetEntity} or at {@code pos}. */
    public static void perform(PlayerMobEntity mob, ItemStack fallbackItem, LivingEntity targetEntity, BlockPos pos) {
        perform(mob, fallbackItem, targetEntity, pos, null);
    }

    /**
     * Right-click the mob's held item on a <em>specific</em> {@code face} of the block at {@code pos} —
     * the same pipeline as {@link #perform}, with the clicked face chosen by the caller instead of
     * derived from the mob's eye position.
     *
     * <p>Placement-sensitive items need that exactness. Flint and steel puts its fire on the face you
     * clicked, so a mob standing a couple of blocks off would otherwise catch a <em>side</em> face and
     * light the ground beside its target rather than the block the target is standing in — the same
     * reasoning as {@code FireBucketGoal} choosing its water spot deliberately rather than by raytrace.</p>
     */
    public static void performOnFace(PlayerMobEntity mob, BlockPos pos, Direction face) {
        perform(mob, ItemStack.EMPTY, null, pos, face);
    }

    /**
     * Shared implementation. A non-null {@code face} overrides the derived click face; it only applies
     * to the {@code pos} (block) path — an entity interaction has no block face.
     */
    private static void perform(PlayerMobEntity mob, ItemStack fallbackItem, LivingEntity targetEntity,
                                BlockPos pos, Direction face) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        // Prefer the mob's actual held stack (the provisioned use item) so mutations write back;
        // fall back to a copy of the ordered item if the hand is somehow empty.
        ItemStack stack = mob.getMainHandItem();
        boolean writeBack = !stack.isEmpty();
        if (!writeBack) {
            stack = fallbackItem == null ? ItemStack.EMPTY : fallbackItem.copy();
        }
        if (stack.isEmpty()) {
            return;
        }
        ServerPlayer actor = FakePlayerSource.get(level);
        if (actor == null) {
            return;
        }
        actor.setItemInHand(InteractionHand.MAIN_HAND, stack);

        if (targetEntity != null) {
            Vec3 from = mob.getEyePosition();
            actor.setPos(from.x, from.y - actor.getEyeHeight(), from.z);
            face(actor, targetEntity.getEyePosition());
            //? if >=26 {
            /*actor.interactOn(targetEntity, InteractionHand.MAIN_HAND, targetEntity.getEyePosition());
            *///?} else {
            actor.interactOn(targetEntity, InteractionHand.MAIN_HAND);
            //?}
        } else if (pos != null) {
            Vec3 center = Vec3.atCenterOf(pos);
            // Stand just above the block looking down so an air-use item's own raytrace lands on it.
            actor.setPos(center.x, pos.getY() + 1.2, center.z);
            face(actor, center);
            Direction clicked = face != null ? face : faceToward(mob.getEyePosition(), center);
            BlockHitResult hit = new BlockHitResult(center, clicked, pos, false);
            // Through the gamemode so the loaders' block-use events fire (modded blocks/items).
            var result = actor.gameMode.useItemOn(actor, level, actor.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hit);
            if (!result.consumesAction()) {
                // Air-use fallback (buckets, throwables …) — also event-firing via the gamemode.
                actor.gameMode.useItem(actor, level, actor.getItemInHand(InteractionHand.MAIN_HAND),
                    InteractionHand.MAIN_HAND);
            }
        }

        // Reflect any change (filled/emptied bucket, drained energy, durability, milk) back on the mob.
        if (writeBack) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, actor.getItemInHand(InteractionHand.MAIN_HAND));
        }
        actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); // don't leave the item on the shared fake player
    }

    /** The block face pointing toward {@code from} (the natural face a mob at {@code from} would click). */
    private static Direction faceToward(Vec3 from, Vec3 center) {
        double dx = from.x - center.x;
        double dy = from.y - center.y;
        double dz = from.z - center.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Point the fake player's head at {@code target} (yaw/pitch from its eye position). */
    private static void face(ServerPlayer actor, Vec3 target) {
        Vec3 eye = actor.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        actor.setYRot(yaw);
        actor.setXRot(pitch);
        actor.yHeadRot = yaw;
    }
}
