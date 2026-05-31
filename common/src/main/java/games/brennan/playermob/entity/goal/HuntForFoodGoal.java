package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.ForagePolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;

/**
 * Make a hungry mob hunt passive animals for meat. This is purely a
 * <em>target</em> goal — it sets a food animal as the mob's attack target and
 * lets the existing {@link WeaponAwareAttackGoal} (goalSelector priority 2) do
 * the actual killing with whatever weapon the mob is holding. The dropped raw
 * meat is then collected by the existing {@code CollectFloorItemsGoal} + vanilla
 * underfoot pickup, and eaten by {@code EatFoodGoal} once HP drops.
 *
 * <p>Because execution rides the priority-2 attack goal, hunting is as assertive
 * as combat — but it's gated to only fire while {@link PlayerMobEntity#wantsFood()}
 * holds, so a fed mob never chases animals. Registered in the target selector
 * <em>below</em> the hostile-targeting goal (priority 2), so defending against a
 * zombie always wins over hunting a cow.</p>
 *
 * <p>Unlike {@link HarvestCropsGoal}, hunting is <b>not</b> gated on the
 * {@code mobGriefing} gamerule — killing a mob isn't a block change, and vanilla
 * hostiles attack regardless of that rule.</p>
 */
public final class HuntForFoodGoal extends NearestAttackableTargetGoal<Animal> {

    private final PlayerMobEntity playerMob;

    public HuntForFoodGoal(PlayerMobEntity mob) {
        // Mirrors the hostile-targeting goal wiring in PlayerMobEntity#registerGoals:
        // randomInterval 10, mustSee true, mustReach false, single-arg selector.
        super(mob, Animal.class, 10, true, false,
            candidate -> ForagePolicy.isHuntableFoodAnimal(candidate));
        this.playerMob = mob;
    }

    @Override
    public boolean canUse() {
        return playerMob.wantsFood() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        // Disengage the instant the mob is fed — drop the hunt and get back to
        // whatever it was doing rather than finishing an now-pointless kill.
        return playerMob.wantsFood() && super.canContinueToUse();
    }
}
