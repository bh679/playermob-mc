package games.brennan.playermob.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor/invoker mixin exposing two {@code protected} members declared on
 * {@link TargetGoal}: the owner {@code mob} field and the {@code getFollowDistance()}
 * method.
 *
 * <p><b>Why this exists.</b> {@link HostilesTargetPlayerMobMixin} mixes into
 * {@link net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal}, a
 * <em>subclass</em> of {@code TargetGoal}. Both {@code mob} ({@code protected final}) and
 * {@code getFollowDistance()} ({@code protected}) are declared on {@code TargetGoal}, not on
 * {@code NearestAttackableTargetGoal} — so an inherited {@code @Shadow} doesn't reliably
 * resolve (Mixin's preprocessor resolves shadows against the <em>declared</em> target class).
 * This is the exact same constraint that {@link MobTargetSelectorAccessor} solves for
 * {@code Mob.targetSelector}: put an {@code @Accessor}/{@code @Invoker} on the class that
 * actually declares the member. Every {@code TargetGoal} — and therefore every
 * {@code NearestAttackableTargetGoal} — implements this interface at runtime, so the goal
 * mixin can cast {@code this} and read both.</p>
 */
@Mixin(TargetGoal.class)
public interface TargetGoalAccessor {

    @Accessor("mob")
    Mob playermob$getMob();

    @Invoker("getFollowDistance")
    double playermob$callGetFollowDistance();
}
