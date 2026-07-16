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
 * Fires a modded ranged weapon (a MusketMod-style firearm) once, by driving the item's <em>own</em>
 * {@code use} / {@code onUseTick} lifecycle through the level's fake player — the same
 * {@link FakePlayerSource} seam {@link CommandedUse} uses. A PlayerMob has no {@code Player} of its own, but
 * modded firearms spawn their projectile and consume their ammo through the player interaction pipeline, so we
 * borrow the fake player, hand it the mob's live weapon + a lent round of ammo, aim it at the target, and run
 * the mod's real firing code. The result — a real bullet, real smoke/sound, real durability + ammo cost — is
 * written back onto the mob.
 *
 * <p>The drive covers the common firearm patterns generically, without compiling against any gun mod:</p>
 * <ol>
 *   <li><b>use once.</b> Instant guns fire here; muzzle-loaders instead begin loading (their {@code use}
 *       checks the shooter's inventory for ammo — which is why we lend a round first).</li>
 *   <li><b>force-load.</b> If the item entered a charge/reload state, a single {@code onUseTick} call with a
 *       near-zero {@code ticksLeft} makes the item's own progress math jump past all its loading stages, so it
 *       marks itself ready and consumes the ammo — no dependence on the mod's exact reload duration.</li>
 *   <li><b>use again / release.</b> A loaded muzzle-loader fires on the second {@code use}; a release-to-fire
 *       weapon fires when we then release. Either way the mod spawns its projectile from the fake player.</li>
 * </ol>
 *
 * <p>The fake player is placed a short way <em>in front of</em> the mob toward the target so the spawned
 * projectile clears the mob's own hitbox, and oriented at the target (the mod reads shooter rotation for aim).
 * Server-thread only; the shared fake player is cleaned up in a {@code finally} so a mid-drive exception can't
 * strand the mob's lent ammo.</p>
 */
public final class ModdedGunFire {

    private ModdedGunFire() {}

    /** How far (blocks) in front of the mob's eye to spawn the shot, so the bullet doesn't clip the mob itself. */
    private static final double MUZZLE_FORWARD = 0.6;

    /**
     * Drive one full load-and-fire cycle of the mob's held modded weapon at {@code target}. Returns true if the
     * shot was attempted (weapon held + ammo lent); false if there was nothing to fire. Caller is expected to
     * have gated on {@link PlayerMobEntity#hasRangedAmmo}.
     */
    public static boolean fireOnce(PlayerMobEntity mob, LivingEntity target) {
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
        // Start from a clean shared fake player, then lend a real round from the backpack so the mod's own
        // use/reload code can find + consume it (into a dedicated non-hotbar slot — see lendModdedAmmo).
        actor.getInventory().clearContent();
        if (!mob.lendModdedAmmo(actor, weapon)) {
            return false;
        }
        try {
            Vec3 eye = mob.getEyePosition();
            Vec3 aimPoint = aimPointOf(target);
            Vec3 dir = aimPoint.subtract(eye).normalize();
            Vec3 muzzle = eye.add(dir.scale(MUZZLE_FORWARD));
            actor.setPos(muzzle.x, muzzle.y - actor.getEyeHeight(), muzzle.z);
            face(actor, aimPoint);
            actor.setItemInHand(InteractionHand.MAIN_HAND, weapon);

            // 1) First use — fires instant guns, or begins a muzzle-loader's reload.
            actor.gameMode.useItem(actor, level, actor.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);

            // 2) If it started charging/reloading, complete it and fire.
            if (actor.isUsingItem()) {
                forceLoad(level, actor);
                face(actor, aimPointOf(target));
                actor.gameMode.useItem(actor, level, actor.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
                if (actor.isUsingItem()) {
                    actor.releaseUsingItem(); // release-to-fire weapons; a no-op fire for others
                }
            }

            // Write the weapon (durability + spent/loaded state) back onto the mob.
            mob.setItemSlot(EquipmentSlot.MAINHAND, actor.getItemInHand(InteractionHand.MAIN_HAND));
            return true;
        } finally {
            // Return the unspent lent ammo and clear the shared fake player (hand + inventory).
            mob.reclaimLentAmmo(actor);
        }
    }

    /**
     * Push the item being used past its loading stages with a single {@code onUseTick} call. Passing a
     * {@code ticksLeft} of 1 makes the item compute a "using time" of nearly its whole use duration, so any
     * staged loader (MusketMod and the like) sees itself as fully loaded and consumes the lent ammo — no need to
     * know the mod's exact reload timing.
     */
    private static void forceLoad(ServerLevel level, ServerPlayer actor) {
        ItemStack using = actor.getUseItem();
        if (!using.isEmpty()) {
            using.getItem().onUseTick(level, actor, using, 1);
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
