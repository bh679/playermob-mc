package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.DoorObstruction;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.phys.Vec3;

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
 * <p>Rate-limited per mob: the goal declines to start while
 * {@link PlayerMobEntity#isDoorChangeOnCooldown()}, and its open sets the next wait (2 s,
 * escalating to 34 s while the mob isn't getting anywhere). The close-behind is the one
 * exemption — see {@code DoorChangeCooldown}.</p>
 *
 * <p>Wooden doors only — iron doors aren't hand-openable and stay path-blocked,
 * and {@code DoorInteractGoal.setOpen} no-ops on anything that isn't a
 * {@link net.minecraft.world.level.block.DoorBlock}.</p>
 */
public final class PlayerMobDoorGoal extends DoorInteractGoal implements DescribableGoal {

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

    @Override
    public String objective() {
        return "Using door";
    }

    /**
     * Suppressed while the mob is holding doors closed after a stuck-recovery close, so this goal
     * can't immediately reopen the very door the recovery just shut to clear a blocked perpendicular
     * path (an open door's panel swings across the perpendicular edge of its cell). See
     * {@link PlayerMobEntity#isHoldingDoorsClosed()}.
     *
     * <p>Also suppressed while the mob's door rate limit is running
     * ({@link PlayerMobEntity#isDoorChangeOnCooldown()}). Declining here rather than letting the run
     * start and no-op matters: a run that started on cooldown would open nothing, then close
     * "behind" itself on {@link #stop()} — shutting a door this mob never opened.</p>
     */
    @Override
    public boolean canUse() {
        return !this.playerMob.isHoldingDoorsClosed()
            && !this.playerMob.isDoorChangeOnCooldown()
            && super.canUse();
    }

    @Override
    public void start() {
        this.forgetTime = CLOSE_DELAY_TICKS;
        // Non-exempt: the open is what spends this mob's door slot and sets the next wait.
        operateDeliberately(true, false);
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
            // Exempt: the close-behind is the second half of the operation the open already paid
            // for. Without the exemption the rate limit would swallow it and every closer would
            // behave as a non-closer.
            operateDeliberately(false, true);
        }
    }

    /**
     * Hand the open/close to the entity's deliberate door-operation window — face the door, swing,
     * then operate, briefly interrupting whatever the mob was doing — instead of flipping the door
     * instantly. Acts on the captured {@code doorPos} (the door this goal latched), so a later goal
     * re-detection can't redirect the deferred action to a different door. No-op if the base goal
     * hasn't resolved a door.
     *
     * @param exempt whether this operation bypasses the mob's door rate limit — true only for the
     *               close-behind; see {@link PlayerMobEntity#beginDoorOperation(double, double,
     *               double, Runnable, boolean)}
     */
    private void operateDeliberately(boolean open, boolean exempt) {
        if (!this.hasDoor) {
            return;
        }
        BlockPos pos = this.doorPos;
        Vec3 eye = this.playerMob.getEyePosition();
        this.playerMob.beginDoorOperation(
            pos.getX() + 0.5 - eye.x, pos.getY() + 0.5 - eye.y, pos.getZ() + 0.5 - eye.z,
            () -> DoorObstruction.setOpen(this.playerMob, this.playerMob.level(), pos, open),
            exempt);
    }
}
