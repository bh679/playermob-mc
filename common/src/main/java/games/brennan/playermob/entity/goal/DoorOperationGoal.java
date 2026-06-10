package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Claims {@link Flag#MOVE} + {@link Flag#LOOK} for as long as the mob is mid door-operation, so
 * opening or closing a door reads as a deliberate, interrupting action rather than an instant
 * background flip.
 *
 * <p>The mechanics of the operation — facing the door, swinging the arm, performing the actual
 * open/close at the "reach" tick — live on the entity
 * ({@link PlayerMobEntity#beginDoorOperation}, applied each tick in {@code customServerAiStep}).
 * This goal's <em>only</em> job is to hold the movement + look flags while
 * {@link PlayerMobEntity#isOperatingDoor()} is true.</p>
 *
 * <p>Registered at priority 1 — above the priority-2 {@code WeaponAwareAttackGoal} — so while a
 * door is being operated the selector evicts combat (and any movement goal): the mob stops
 * hitting its target / walking, does the door, and combat resumes the instant the window ends.
 * Declaring the flags here, rather than checking an "operating door" flag inside every
 * combat/movement goal, is what makes the interruption fall out of the selector for free. Like
 * the other door goals it declares its {@link DescribableGoal} label for the Creative readout.</p>
 */
public final class DoorOperationGoal extends Goal implements DescribableGoal {

    private final PlayerMobEntity mob;

    public DoorOperationGoal(PlayerMobEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Using door";
    }

    @Override
    public boolean canUse() {
        return this.mob.isOperatingDoor();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isOperatingDoor();
    }

    @Override
    public void start() {
        // Halt so the door is operated from a stop, not mid-stride; the entity drives the look.
        this.mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
