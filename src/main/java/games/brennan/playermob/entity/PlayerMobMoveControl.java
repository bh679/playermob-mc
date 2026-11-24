package games.brennan.playermob.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Vanilla movement with two fixes for a mob that strafes the way a player does.
 *
 * <ul>
 *   <li><b>No forward flip.</b> When a strafe would step somewhere vanilla doesn't think is walkable,
 *       {@link MoveControl#tick} turns it into a full-speed walk <em>forward</em>, straight at whatever
 *       the mob is facing: a back-step from a fight becomes a charge through the target and out the
 *       far side (off a ledge, or off a train). A PlayerMob stops instead.</li>
 *   <li><b>No sideways drift.</b> Vanilla clears only forward input when it isn't strafing, so the
 *       sideways part of a strafe keeps sliding the mob (decaying slowly) after the strafe ends, past any
 *       floor check its goal made. A PlayerMob drops it as soon as it stops strafing.</li>
 * </ul>
 */
public final class PlayerMobMoveControl extends MoveControl {

    public PlayerMobMoveControl(Mob mob) {
        super(mob);
    }

    @Override
    public void tick() {
        boolean strafing = this.operation == Operation.STRAFE;
        boolean wantedForward = strafing && this.strafeForwards > 0.0F;
        super.tick();
        if (!strafing) {
            this.mob.setXxa(0.0F);
        } else if (!wantedForward && this.strafeForwards == 1.0F && this.strafeRight == 0.0F) {
            // Vanilla found the strafe unwalkable and swapped in "walk forward": stand still instead.
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
        }
    }
}
