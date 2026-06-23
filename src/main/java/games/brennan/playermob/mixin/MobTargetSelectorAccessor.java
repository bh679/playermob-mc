package games.brennan.playermob.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin exposing {@link Mob}'s {@code targetSelector} field.
 *
 * <p>{@code targetSelector} is {@code protected final} on {@link Mob}. A subclass
 * mixin (e.g. on {@code Pillager}) cannot reach it with an inherited {@code @Shadow}
 * — Mixin's preprocessor resolves shadows against the <em>declared</em> target class,
 * and the field isn't declared on {@code Pillager}. The idiomatic fix is an
 * {@code @Accessor} interface mixin on the class that actually declares the field;
 * every {@code Mob} instance then implements this interface at runtime, so any mixin
 * can cast and read the selector. See {@link PillagerTargetsPlayerMobMixin}.</p>
 */
@Mixin(Mob.class)
public interface MobTargetSelectorAccessor {

    @Accessor("targetSelector")
    GoalSelector playermob$getTargetSelector();
}
