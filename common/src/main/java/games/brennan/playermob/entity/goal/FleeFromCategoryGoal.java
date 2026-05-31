package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;

/**
 * The <b>Shy</b> behaviour: run away from any entity the mob is currently
 * {@link Personality#SHY} toward (players and/or hostile mobs — Shy is
 * disallowed for animals/villagers), crouching to "hide" whenever it ends up
 * next to solid cover.
 *
 * <p>Built on vanilla {@link AvoidEntityGoal} for the battle-tested flee
 * pathing; the avoid-predicate reads the mob's <em>current</em> personality
 * each evaluation, so a runtime flip (e.g. provoked → Shy) starts this goal and
 * a flip away from Shy stops it — no goal re-wiring. Registered at priority 1
 * (above raiding/combat) and deliberately does <em>not</em> gate on
 * {@code target == null}, so a provoked-shy mob flees rather than fights.</p>
 *
 * <p>Crouching a mob is purely cosmetic — it sets the shared sneak flag (which
 * the {@code PlayerModel} renders as the sneak pose) without the player-only
 * movement-speed penalty — so the mob keeps full flee speed while "hiding".</p>
 */
public final class FleeFromCategoryGoal extends AvoidEntityGoal<LivingEntity> {

    private final PlayerMobEntity playerMob;

    public FleeFromCategoryGoal(PlayerMobEntity mob, float maxDistance,
                                double walkSpeed, double sprintSpeed) {
        super(mob, LivingEntity.class, maxDistance, walkSpeed, sprintSpeed,
              candidate -> mob.personalityToward(candidate) == Personality.SHY);
        this.playerMob = mob;
    }

    @Override
    public void tick() {
        super.tick();
        // Hide: crouch when next to solid cover while fleeing.
        playerMob.setShiftKeyDown(isNextToCover());
    }

    @Override
    public void stop() {
        super.stop();
        playerMob.setShiftKeyDown(false);
    }

    /**
     * True if any of the four cardinal blocks at the mob's body height is a
     * non-air, non-fluid block — a cheap "is there something to hide behind?"
     * test. Intentionally simple; tuned visually at Gate 2.
     */
    private boolean isNextToCover() {
        BlockPos body = playerMob.blockPosition().above();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = body.relative(dir);
            var state = playerMob.level().getBlockState(neighbor);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
