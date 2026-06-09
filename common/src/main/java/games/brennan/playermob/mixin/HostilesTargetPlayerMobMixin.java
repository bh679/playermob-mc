package games.brennan.playermob.mixin;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a {@link PlayerMobEntity} hunted on sight by every mob that hunts <em>players</em>
 * on sight — zombies, skeletons, creepers, spiders, slimes, blazes, silverfish, vexes,
 * ghasts, illagers, and any (modded) mob that uses vanilla's player-targeting goal — so a
 * PlayerMob is treated exactly like a human player.
 *
 * <p><b>Why one mixin on the goal, not one per mob.</b> Nearly every hostile mob acquires the
 * player through the <em>same</em> vanilla goal:
 * {@code new NearestAttackableTargetGoal<>(this, Player.class, …)}. Its {@code findTarget()}
 * takes a fast path — {@code level.getNearestPlayer(…)} — that only ever finds real
 * {@link Player} entities, never a PlayerMob (which extends {@code PathfinderMob}, not
 * {@code Player}). Injecting once at the {@code TAIL} of {@code findTarget} covers the entire
 * faction — present and future, vanilla and modded — instead of a mixin per mob type. This
 * mirrors the design rationale of {@link RaiderTargetsPlayerMobMixin} (inject at the shared
 * base, not the leaves).</p>
 *
 * <p><b>Why this is needed.</b> PlayerMob deliberately extends
 * {@link net.minecraft.world.entity.PathfinderMob} rather than {@code Monster}, so it does
 * not implement the {@code Enemy} marker (that's what stopped iron golems attacking it on
 * sight). A consequence is that nothing vanilla targets a PlayerMob anymore. The raider mixin
 * restored pillager-faction hostility; this restores the rest of the standard hostile mobs.</p>
 *
 * <p><b>"Same as players."</b> We reuse the goal's own {@code targetConditions} and search
 * area, so a PlayerMob is validated under <em>identical</em> rules to a player — range,
 * line-of-sight ({@code mustSee}), invisibility, and any per-goal selector predicate all
 * apply unchanged. We never loosen vanilla's checks. When both a real player and a PlayerMob
 * are in range, the mob picks whichever is closer — exactly how it arbitrates between two
 * players. The search branch here mirrors vanilla's own non-player branch of
 * {@code findTarget} ({@code getEntitiesOfClass} + {@code getNearestEntity(list, …)}).</p>
 *
 * <p><b>No PlayerMob friendly-fire.</b> The guard is scoped to goals whose {@code targetType}
 * is {@code Player}/{@code ServerPlayer}. PlayerMob's own hostile goal targets
 * {@code LivingEntity.class} with a personality predicate, so it is skipped — PlayerMobs do
 * not begin targeting each other. Their targeting stays governed by {@code TargetCategory}.</p>
 *
 * <p><b>Inherited members.</b> {@code mob} and {@code getFollowDistance()} are declared on
 * {@code TargetGoal} (the superclass), so they're reached through {@link TargetGoalAccessor}
 * rather than an inherited {@code @Shadow}; the fields/method declared on
 * {@code NearestAttackableTargetGoal} itself ({@code target}, {@code targetType},
 * {@code targetConditions}, {@code getTargetSearchArea}) are shadowed directly.</p>
 *
 * <p>Server-side AI only — registered in the {@code mixins} (not {@code client}) array of
 * {@code playermob.mixins.json}. Injected at {@code TAIL} so it runs after vanilla has set
 * (or cleared) its own player target.</p>
 */
@Mixin(NearestAttackableTargetGoal.class)
public abstract class HostilesTargetPlayerMobMixin {

    @Shadow @Final protected Class<? extends LivingEntity> targetType;
    @Shadow protected LivingEntity target;
    @Shadow protected TargetingConditions targetConditions;

    @Shadow protected abstract AABB getTargetSearchArea(double followRange);

    @Inject(method = "findTarget", at = @At("TAIL"))
    private void playermob$alsoTargetPlayerMobs(CallbackInfo ci) {
        // Only the player-targeting goals — leave LivingEntity/Mob-typed goals (including
        // PlayerMob's own personality-driven goal) untouched.
        if (this.targetType != Player.class && this.targetType != ServerPlayer.class) {
            return;
        }

        TargetGoalAccessor self = (TargetGoalAccessor) this;
        Mob mob = self.playermob$getMob();
        Level level = mob.level();
        AABB area = this.getTargetSearchArea(self.playermob$callGetFollowDistance());

        // Mirror vanilla's non-player branch of findTarget, but for PlayerMobEntity. The
        // goal's own targetConditions enforce the same range/sight rules a player would get.
        PlayerMobEntity candidate = level.getNearestEntity(
            level.getEntitiesOfClass(PlayerMobEntity.class, area, playerMob -> true),
            this.targetConditions, mob, mob.getX(), mob.getEyeY(), mob.getZ());

        if (candidate != null
            && (this.target == null
                || mob.distanceToSqr(candidate) < mob.distanceToSqr(this.target))) {
            // `target` is a LivingEntity field; a PlayerMobEntity is type-valid.
            this.target = candidate;
        }
    }
}
