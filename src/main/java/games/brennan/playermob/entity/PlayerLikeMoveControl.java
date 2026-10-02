package games.brennan.playermob.entity;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * The one place a {@link PlayerMobEntity}'s movement input is decided, so it only ever moves at a
 * player's speeds (see {@link PlayerSpeeds}).
 *
 * <p>Vanilla {@link MoveControl} turns a goal's speed modifier into {@code setSpeed(modifier x
 * attribute)}, and {@code Mob.setSpeed} copies that into the forward input too — so ground
 * acceleration ends up as the <em>square</em> of it and every modifier is its own speed. This lets
 * vanilla do the steering, then replaces what it wrote with what a player's keyboard would send:</p>
 * <ul>
 *   <li><b>Full stick.</b> Moving is {@code forward = 1}; strafing is rescaled to unit length. The
 *       speed itself comes from {@link PlayerMobEntity#getSpeed()} (the attribute, as for a player).</li>
 *   <li><b>Use-item slow.</b> Input drops to 20% while drawing a bow, charging a crossbow, blocking
 *       or eating, and there is no sprinting.</li>
 *   <li><b>Sprint.</b> This control owns the sprint flag — on while a goal asked for
 *       {@link PlayerSpeeds#SPRINT} and the mob is actually driving forward, off otherwise. Vanilla
 *       never clears it for a mob, so goals must not set it themselves.</li>
 *   <li><b>Swimming.</b> Sprinting in water is a player's sprint-swim (drag 0.9 instead of 0.8);
 *       the swim pose is set here because only {@code Player} ever assigns it.</li>
 * </ul>
 *
 * <p>Input is <em>assigned</em>, never normalised in place: vanilla's move and wait branches leave
 * the strafe input untouched, so a stale strafe from a bow goal would otherwise steer the mob
 * sideways.</p>
 */
//? if >=26 {
/*public class PlayerLikeMoveControl extends MoveControl<PlayerMobEntity> {
*///?} else {
public class PlayerLikeMoveControl extends MoveControl {
//?}

    /**
     * Forward input for "stick fully forward". A player's input is damped by 0.98 before
     * {@code travel}; up to 1.21 that damping also catches what this control writes, on 26+ it runs
     * before the AI step so the control has to write the damped value itself.
     */
    //? if >=26 {
    /*private static final float FULL_STICK = 0.98F;
    *///?} else {
    private static final float FULL_STICK = 1.0F;
    //?}

    private final PlayerMobEntity playerMob;
    private final SprintJumpDriver sprintJump;

    public PlayerLikeMoveControl(PlayerMobEntity mob) {
        super(mob);
        this.playerMob = mob;
        this.sprintJump = new SprintJumpDriver(mob);
    }

    @Override
    public void tick() {
        // super.tick() resets the operation to WAIT, so read what was asked for first.
        Operation requested = this.operation;
        super.tick();

        boolean usingItem = playerMob.isUseItemSlowed();
        boolean sprinting = false;
        if ((requested == Operation.MOVE_TO || requested == Operation.JUMPING) && playerMob.zza != 0.0F) {
            float[] input = PlayerSpeeds.stickInput(1.0F, 0.0F, FULL_STICK, usingItem);
            playerMob.setZza(input[0]);
            playerMob.setXxa(0.0F);
            sprinting = !usingItem && PlayerSpeeds.gaitFor(this.speedModifier) == PlayerSpeeds.Gait.SPRINT;
        } else if (requested == Operation.STRAFE) {
            float[] input = PlayerSpeeds.stickInput(playerMob.zza, playerMob.xxa, FULL_STICK, usingItem);
            playerMob.setZza(input[0]);
            playerMob.setXxa(input[1]);
        } else {
            playerMob.setXxa(0.0F);
        }

        applySprint(sprinting);
        sprintJump.tick(sprinting);
    }

    /**
     * Set the sprint flag and the matching swim pose. Only on a change: {@code setSprinting}
     * re-applies the attribute modifier, which would otherwise resync the attribute every tick.
     */
    private void applySprint(boolean sprinting) {
        if (playerMob.isSprinting() != sprinting) {
            playerMob.setSprinting(sprinting);
        }
        // Afloat, not wading: a player sprinting through a puddle stays upright.
        boolean swimming = sprinting && playerMob.isInWater() && !playerMob.onGround();
        if (swimming && !playerMob.hasPose(Pose.SWIMMING)) {
            playerMob.setPose(Pose.SWIMMING);
        } else if (!swimming && playerMob.hasPose(Pose.SWIMMING)) {
            playerMob.setPose(Pose.STANDING);
        }
    }
}
