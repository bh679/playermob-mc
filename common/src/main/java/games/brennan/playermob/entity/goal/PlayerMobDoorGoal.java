package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;

/**
 * Walk up to a closed wooden door on the path, open it, and — depending on this
 * mob's personality — either close it behind or leave it standing open.
 *
 * <p>Structurally a copy of vanilla {@link OpenDoorGoal}, with one behavioural
 * difference: the close-behind decision is read <em>per mob</em> from
 * {@link PlayerMobEntity#closesDoors()} on every evaluation rather than fixed at
 * construction. That dynamic read is required because the goal is registered
 * inside the {@code Mob} constructor — before {@code finalizeSpawn} rolls the
 * flag and before {@code readAdditionalSaveData} loads it — so a constructor
 * argument would always capture the default.</p>
 *
 * <p>It also adds a "leave open" mode vanilla can't express: vanilla
 * {@code OpenDoorGoal.stop()} always closes, so {@code closeDoor=false} just
 * closes <em>sooner</em>, never leaves the door open. Here a non-closer's
 * {@link #stop()} is a no-op, so the door stays open after it passes.</p>
 *
 * <p>Pathing through the door is enabled separately by
 * {@code GroundPathNavigation.setCanOpenDoors(true)} in the entity constructor;
 * without it {@link DoorInteractGoal#canUse()} never fires. Like its vanilla
 * base this goal declares no {@link net.minecraft.world.entity.ai.goal.Goal.Flag}s,
 * so it runs concurrently with the movement goal driving the walk and never
 * preempts combat, raiding, or strolling.</p>
 *
 * <p>Wooden doors only — iron doors aren't hand-openable and stay path-blocked,
 * and {@code DoorInteractGoal.setOpen} no-ops on anything that isn't a
 * {@link net.minecraft.world.level.block.DoorBlock}.</p>
 */
public final class PlayerMobDoorGoal extends DoorInteractGoal {

    /**
     * Ticks a closer keeps the goal alive after opening, as a fallback close
     * trigger if the mob never crosses the threshold (e.g. it gives up pathing).
     * Matches vanilla {@code OpenDoorGoal}'s timer (20 ticks = 1 second).
     */
    private static final int CLOSE_DELAY_TICKS = 20;

    private final PlayerMobEntity playerMob;
    private int forgetTime;

    /**
     * @param mob the owning PlayerMob. {@link DoorInteractGoal}'s constructor
     *            throws {@link IllegalArgumentException} unless the mob uses
     *            {@code GroundPathNavigation} — PlayerMob does by default.
     */
    public PlayerMobDoorGoal(PlayerMobEntity mob) {
        super(mob);
        this.playerMob = mob;
    }

    /**
     * Suppressed while the mob is holding doors closed after a stuck-recovery close, so this goal
     * can't immediately reopen the very door the recovery just shut to clear a blocked perpendicular
     * path (an open door's panel swings across the perpendicular edge of its cell). See
     * {@link PlayerMobEntity#isHoldingDoorsClosed()}.
     */
    @Override
    public boolean canUse() {
        return !this.playerMob.isHoldingDoorsClosed() && super.canUse();
    }

    @Override
    public void start() {
        this.forgetTime = CLOSE_DELAY_TICKS;
        this.setOpen(true);
    }

    @Override
    public void tick() {
        this.forgetTime--;
        // super.tick() flips the inherited `passed` flag once the mob crosses
        // the door plane, which ends a closer's run (see canContinueToUse).
        super.tick();
    }

    /**
     * A closer lingers until it has passed through (or the fallback timer
     * elapses), at which point {@link #stop()} closes the door behind it. A
     * non-closer returns {@code false} immediately, so its run ends the tick
     * after {@link #start()} opened the door — and its no-op {@code stop()}
     * leaves the door open.
     */
    @Override
    public boolean canContinueToUse() {
        return this.playerMob.closesDoors()
            && this.forgetTime > 0
            && super.canContinueToUse();
    }

    @Override
    public void stop() {
        if (this.playerMob.closesDoors()) {
            this.setOpen(false);
        }
    }
}
