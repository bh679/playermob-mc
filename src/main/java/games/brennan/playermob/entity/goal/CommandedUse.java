package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Performs a commanded {@link OrderType#USE} — a real right-click of an item on a target
 * entity or at a position — by borrowing the level's cached fake player
 * ({@link CommandedFakePlayer}). A PlayerMob has no player of its own, but item/entity
 * interactions (shears, buckets, hoes, placement) are written against
 * {@link net.minecraft.world.entity.player.Player}, so the fake player is positioned at the
 * mob, handed a copy of the item, and driven through the vanilla interaction paths.
 *
 * <p>Best-effort and inherently item-dependent: entity targets go through
 * {@code interactOn} (shear / milk / breed / lead); positions try {@code useOn} (placement /
 * till / bonemeal) then an air {@code use} (buckets / throwables). Positional semantics —
 * which block a placement lands on — depend on the clicked face, so a pos may need nudging.</p>
 */
public final class CommandedUse {

    private CommandedUse() {}

    /** Drive the level's fake player to right-click {@code item} on {@code targetEntity} or at {@code pos}. */
    public static void perform(PlayerMobEntity mob, ItemStack item, LivingEntity targetEntity, BlockPos pos) {
        if (item == null || item.isEmpty()) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer actor = CommandedFakePlayer.get(level);
        if (actor == null) {
            return;
        }
        actor.setItemInHand(InteractionHand.MAIN_HAND, item.copy());

        if (targetEntity != null) {
            Vec3 from = mob.getEyePosition();
            actor.setPos(from.x, from.y - actor.getEyeHeight(), from.z);
            face(actor, targetEntity.getEyePosition());
            //? if >=26 {
            /*actor.interactOn(targetEntity, InteractionHand.MAIN_HAND, targetEntity.getEyePosition());
            *///?} else {
            actor.interactOn(targetEntity, InteractionHand.MAIN_HAND);
            //?}
            return;
        }
        if (pos != null) {
            Vec3 center = Vec3.atCenterOf(pos);
            // Stand just above the block looking down so an air-use item's own raytrace lands on it.
            actor.setPos(center.x, pos.getY() + 1.2, center.z);
            face(actor, center);
            BlockHitResult hit = new BlockHitResult(
                new Vec3(center.x, pos.getY() + 1.0, center.z), Direction.UP, pos, false);
            item.useOn(new UseOnContext(actor, InteractionHand.MAIN_HAND, hit));
            // Air-use fallback (buckets, throwables …) — a no-op for items already handled by useOn.
            item.use(level, actor, InteractionHand.MAIN_HAND);
        }
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
