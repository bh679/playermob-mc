package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.FakePlayerSource;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Fires an <em>already-loaded</em> modded ranged weapon (a MusketMod-style firearm) once, by borrowing the
 * level's fake player ({@link FakePlayerSource}) to trigger the item's own fire code. A PlayerMob has no
 * {@code Player} of its own, but a loaded muzzle-loader fires through the player interaction pipeline
 * ({@code use} → spawn projectile), so we hand the fake player the mob's loaded weapon, aim it at the target,
 * and let the mod spawn its real bullet from the mob's position.
 *
 * <p>The <b>reload</b> is <em>not</em> done here — {@link ModdedRangedAttackGoal} makes the mob itself
 * {@code startUsingItem} the weapon so the mod's {@code onUseTick} runs the real, staged reload over time (with
 * its load animation + sounds) before this fire. That keeps the "full reload before each shot" behaviour a
 * player sees, rather than an instant load. This method only delivers the shot of a gun that's already loaded.</p>
 *
 * <p>The fake player is placed a short way <em>in front of</em> the mob toward the target so the spawned
 * projectile clears the mob's own hitbox, and oriented at the target (the mod reads shooter rotation for aim).
 * Server-thread only; the shared fake player's hand is cleared after the shot.</p>
 */
public final class ModdedGunFire {

    private ModdedGunFire() {}

    /** How far (blocks) in front of the mob's eye to spawn the shot, so the bullet doesn't clip the mob itself. */
    private static final double MUZZLE_FORWARD = 0.6;

    /**
     * Deliver one shot from the mob's <em>loaded</em> modded weapon at {@code target}. The caller is responsible
     * for having reloaded the weapon first (see {@link ModdedRangedAttackGoal}) and for the ammo cost. Returns
     * true if the fire was driven (weapon held); the mod itself decides whether a loaded gun actually discharges.
     */
    public static boolean fire(PlayerMobEntity mob, LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level) || target == null) {
            return false;
        }
        ItemStack weapon = mob.getMainHandItem();
        if (weapon.isEmpty()) {
            return false;
        }
        ServerPlayer actor = FakePlayerSource.get(level);
        if (actor == null) {
            return false;
        }
        actor.getInventory().clearContent();
        try {
            Vec3 eye = mob.getEyePosition();
            Vec3 aimPoint = aimPointOf(target);
            Vec3 dir = aimPoint.subtract(eye).normalize();
            Vec3 muzzle = eye.add(dir.scale(MUZZLE_FORWARD));
            actor.setPos(muzzle.x, muzzle.y - actor.getEyeHeight(), muzzle.z);
            face(actor, aimPoint);
            actor.setItemInHand(InteractionHand.MAIN_HAND, weapon);

            // A loaded muzzle-loader fires here; a release-to-fire weapon is caught by the release below.
            actor.gameMode.useItem(actor, level, actor.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
            if (actor.isUsingItem()) {
                actor.releaseUsingItem();
            }

            // Write the weapon (now spent/unloaded, durability updated) back onto the mob.
            mob.setItemSlot(EquipmentSlot.MAINHAND, actor.getItemInHand(InteractionHand.MAIN_HAND));
            return true;
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /** Aim at the midpoint of the target's torso/eye — matches how gun mobs lead a shot at a torso. */
    private static Vec3 aimPointOf(LivingEntity target) {
        return new Vec3(target.getX(), 0.5 * (target.getEyeY() + target.getY(0.5)), target.getZ());
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
