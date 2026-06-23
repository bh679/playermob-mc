package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the whole pillager-aligned faction — every {@link Raider} — treat a
 * {@link PlayerMobEntity} exactly like a human player: hunt it on sight.
 *
 * <p><b>Why {@code Raider}, not {@code Pillager}.</b> {@code Raider} is vanilla's base class
 * for the illager/raid faction: {@code Pillager}, {@code Vindicator}, {@code Evoker},
 * {@code Illusioner}, {@code Ravager}, and {@code Witch} all extend it (and so do modded
 * raiders). Every subclass's {@code registerGoals()} chains up to
 * {@code Raider.registerGoals()} via {@code super}, so a single {@code @Inject} here applies
 * to the entire faction — present and future — instead of one mixin per mob type.</p>
 *
 * <p><b>Why this is needed.</b> PlayerMob deliberately extends
 * {@link net.minecraft.world.entity.PathfinderMob} rather than {@code Monster}, so it does
 * not implement the {@code Enemy} marker (that's what stopped iron golems attacking it on
 * sight). A consequence is that nothing vanilla targets a PlayerMob anymore. We restore
 * raider hostility explicitly, on the raider side, scoped to PlayerMob only.</p>
 *
 * <p><b>"Same as players."</b> Vanilla illagers add
 * {@code new NearestAttackableTargetGoal<>(this, Player.class, true)} at target-selector
 * priority 2. We mirror that precisely — same goal type, same priority (2), same
 * {@code checkSight = true} — pointed at {@link PlayerMobEntity}, so a PlayerMob is hunted
 * with identical precedence to a player (above villagers / iron golems at priority 3).
 * Goals may share a priority; the selector runs whichever can-use first, so the player and
 * player-mob goals coexist.</p>
 *
 * <p><b>Witch note.</b> A vanilla witch doesn't hunt players on sight, but injecting at the
 * {@code Raider} level means witches <em>will</em> seek PlayerMobs on sight. That's
 * intentional: "anything aligned with pillagers is hostile to PlayerMobs."</p>
 *
 * <p><b>Field access.</b> {@code targetSelector} is declared on {@link Mob}, not on
 * {@code Raider}, so an inherited {@code @Shadow} doesn't resolve. We reach it through
 * {@link MobTargetSelectorAccessor} (an {@code @Accessor} on {@code Mob}), which every
 * {@code Mob} implements at runtime.</p>
 *
 * <p>Server-side AI only — registered in the {@code mixins} (not {@code client}) array of
 * {@code playermob.mixins.json}. Injected at {@code TAIL} so it appends after vanilla has
 * wired its own goals.</p>
 */
@Mixin(Raider.class)
public abstract class RaiderTargetsPlayerMobMixin {

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void playermob$targetPlayerMobs(CallbackInfo ci) {
        // `this` is the Raider instance at runtime; it's a Mob, and every Mob
        // implements MobTargetSelectorAccessor via the accessor mixin.
        Mob self = (Mob) (Object) this;
        MobTargetSelectorAccessor accessor = (MobTargetSelectorAccessor) self;
        accessor.playermob$getTargetSelector().addGoal(2,
            new NearestAttackableTargetGoal<>(self, PlayerMobEntity.class, true));
    }
}
