package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Readout-only goal: declares the "Digging through" objective for the Creative under-name
 * readout while the mob is mining its way through a Dungeon-Train carriage packed with fill
 * (ice/dirt/mud/moss/logs).
 *
 * <p>The dig itself — detecting the stuck mob, reading the blocking block in the carriage's
 * sub-level coordinate space, pacing the break, and destroying it — is a per-tick reflex in
 * the Dungeon-Train seam ({@code TrainConfinement#digObstructingBlock} →
 * {@code DungeonTrainEnvironment}), run from {@code customServerAiStep} after the goal selector
 * so it can reach blocks an ordinary block-breaking goal can't (a riding carriage's blocks
 * don't sit at the mob's world position). This goal mirrors {@link PlayerMobDoorGoal}: it
 * declares <b>no flags</b>, so it never evicts the movement goal — the mob keeps being pushed
 * forward into the gap as the wall clears — and exists purely to surface what the reflex is
 * doing. No-op off a train (the reflex never sets {@link PlayerMobEntity#isDigging()} there).</p>
 */
public final class DigThroughGoal extends Goal implements DescribableGoal {

    private final PlayerMobEntity mob;

    public DigThroughGoal(PlayerMobEntity mob) {
        this.mob = mob;   // no flags — readout only; must not evict the advance/movement goal
    }

    @Override
    public String objective() {
        return "Digging through";
    }

    @Override
    public boolean canUse() {
        return mob.isDigging();
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isDigging();
    }
}
