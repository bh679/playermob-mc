package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Pillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes vanilla {@link Pillager}s treat a {@link PlayerMobEntity} exactly like a
 * human player: hunt it on sight, unprovoked.
 *
 * <p><b>Why this is needed.</b> PlayerMob deliberately extends
 * {@link net.minecraft.world.entity.PathfinderMob} rather than {@code Monster}, so it
 * does not implement the {@code Enemy} marker (that's what stopped iron golems attacking
 * it on sight). A consequence is that nothing vanilla targets a PlayerMob anymore. To
 * restore the pillager-vs-player-shaped-mob hostility we re-add it explicitly, on the
 * pillager side, scoped to PlayerMob only.</p>
 *
 * <p><b>"Same as players."</b> Vanilla {@code Pillager.registerGoals()} adds
 * {@code new NearestAttackableTargetGoal<>(this, Player.class, true)} at target-selector
 * priority 2. We mirror that precisely — same goal type, same priority (2), same
 * {@code checkSight = true} — pointed at {@link PlayerMobEntity}, so a PlayerMob is hunted
 * with identical precedence to a player (above villagers / iron golems at priority 3).
 * Goals may share a priority (vanilla itself stacks two at 3); the selector runs whichever
 * can-use first, so the player and player-mob goals coexist.</p>
 *
 * <p><b>Field access.</b> {@code targetSelector} is declared on {@link Mob}, not on
 * {@code Pillager}, so an inherited {@code @Shadow} doesn't resolve. We reach it through
 * {@link MobTargetSelectorAccessor} (an {@code @Accessor} on {@code Mob}), which every
 * {@code Mob} implements at runtime.</p>
 *
 * <p>Server-side AI only — registered in the {@code mixins} (not {@code client}) array of
 * {@code playermob.mixins.json}. Injected at {@code TAIL} so it appends after vanilla has
 * wired its own goals.</p>
 */
@Mixin(Pillager.class)
public abstract class PillagerTargetsPlayerMobMixin {

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void playermob$targetPlayerMobs(CallbackInfo ci) {
        // `this` is the Pillager instance at runtime; it's a Mob, and every Mob
        // implements MobTargetSelectorAccessor via the accessor mixin.
        Mob self = (Mob) (Object) this;
        MobTargetSelectorAccessor accessor = (MobTargetSelectorAccessor) self;
        accessor.playermob$getTargetSelector().addGoal(2,
            new NearestAttackableTargetGoal<>(self, PlayerMobEntity.class, true));
    }
}
