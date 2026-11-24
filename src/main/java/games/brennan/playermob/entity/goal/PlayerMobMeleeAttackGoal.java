package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.MeleeSpacing;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.ReactionChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Hand-to-hand combat the way a player fights: swing from up to {@link MeleeSpacing#PLAYER_REACH}
 * blocks and keep a distance set by melee skill rather than walking into the target's face. A skilled
 * mob stays near the edge of its reach; a novice crowds in (see {@link MeleeSpacing}). Inside the
 * band it stands and fights; too close, it steps back, but only onto floor, so it never backs off a
 * ledge or a carriage edge.
 *
 * <p>The melee delegate of {@link WeaponAwareAttackGoal}. Replaces vanilla {@code MeleeAttackGoal},
 * which paths until the hitboxes nearly touch. Pathing cadence, the 20-tick swing cooldown and the
 * line-of-sight check follow vanilla's.</p>
 */
public final class PlayerMobMeleeAttackGoal extends Goal {

    /** Vanilla's swing cooldown. */
    private static final int ATTACK_INTERVAL_TICKS = 20;
    /** How often {@link #canUse} may re-path (vanilla's throttle). */
    private static final long CAN_USE_THROTTLE_TICKS = 20L;
    /** How far behind the mob the floor is checked before a back-step. */
    private static final double BACKSTEP_PROBE = 0.8;
    /** Back-step strafe speed (the bow goal's). */
    private static final float BACKSTEP_SPEED = -0.5F;

    private final PlayerMobEntity mob;
    private final double speed;
    private Path path;
    private long lastCanUseCheck;
    private int ticksUntilNextPathRecalculation;
    private int ticksUntilNextAttack;
    private double pathedTargetX;
    private double pathedTargetY;
    private double pathedTargetZ;

    public PlayerMobMeleeAttackGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        long time = mob.level().getGameTime();
        if (time - lastCanUseCheck < CAN_USE_THROTTLE_TICKS) return false;
        lastCanUseCheck = time;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        path = mob.getNavigation().createPath(target, 0);
        return path != null || MeleeSpacing.inSwingReach(MeleeSpacing.reach(mob, target));
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        return !(target instanceof Player player && (player.isSpectator() || player.isCreative()));
    }

    @Override
    public void start() {
        if (path != null) mob.getNavigation().moveTo(path, speed);
        mob.setAggressive(true);
        ticksUntilNextPathRecalculation = 0;
        ticksUntilNextAttack = 0;
        pathedTargetX = pathedTargetY = pathedTargetZ = 0.0;
    }

    @Override
    public void stop() {
        LivingEntity target = mob.getTarget();
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            mob.setTarget(null);
        }
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        double reach = MeleeSpacing.reach(mob, target);
        int skill = mob.reactionSpeed(ReactionChannel.MELEE);
        ticksUntilNextPathRecalculation = Math.max(ticksUntilNextPathRecalculation - 1, 0);

        switch (MeleeSpacing.move(skill, reach)) {
            case ADVANCE -> advance(target);
            case HOLD -> mob.getNavigation().stop();
            case RETREAT -> stepBack(target);
        }

        ticksUntilNextAttack = Math.max(ticksUntilNextAttack - 1, 0);
        if (ticksUntilNextAttack <= 0 && MeleeSpacing.inSwingReach(reach)
                && mob.getSensing().hasLineOfSight(target)) {
            ticksUntilNextAttack = ATTACK_INTERVAL_TICKS;
            mob.swing(InteractionHand.MAIN_HAND);
            //? if >=26 {
            /*mob.doHurtTarget((net.minecraft.server.level.ServerLevel) mob.level(), target);
            *///?} else {
            mob.doHurtTarget(target);
            //?}
        }
    }

    /** Vanilla's re-path cadence, plus a fresh path whenever the mob has stopped (it held, then fell behind). */
    private void advance(LivingEntity target) {
        boolean stopped = mob.getNavigation().isDone();
        boolean targetMoved = target.distanceToSqr(pathedTargetX, pathedTargetY, pathedTargetZ) >= 1.0
            || mob.getRandom().nextFloat() < 0.05F;
        if (!mob.getSensing().hasLineOfSight(target) && !stopped) return;
        if (!stopped && (ticksUntilNextPathRecalculation > 0 || !targetMoved)) return;
        pathedTargetX = target.getX();
        pathedTargetY = target.getY();
        pathedTargetZ = target.getZ();
        ticksUntilNextPathRecalculation = 4 + mob.getRandom().nextInt(7);
        double distSq = mob.distanceToSqr(target);
        if (distSq > 1024.0) ticksUntilNextPathRecalculation += 10;
        else if (distSq > 256.0) ticksUntilNextPathRecalculation += 5;
        if (!mob.getNavigation().moveTo(target, speed)) {
            ticksUntilNextPathRecalculation += 15;
        }
    }

    /** Step straight back from the target, if there is floor to step onto; otherwise stand. */
    private void stepBack(LivingEntity target) {
        mob.getNavigation().stop();
        Vec3 away = new Vec3(mob.getX() - target.getX(), 0.0, mob.getZ() - target.getZ());
        if (away.lengthSqr() < 1.0E-4 || !floorAt(mob.position().add(away.normalize().scale(BACKSTEP_PROBE)))) {
            return;
        }
        mob.lookAt(target, 30.0F, 30.0F);
        mob.getMoveControl().strafe(BACKSTEP_SPEED, 0.0F);
    }

    /** True if the world point {@code feet} has a solid block under it, read in the mob's block frame (a carriage's own, on a train). */
    private boolean floorAt(Vec3 feet) {
        Vec3 local = TrainConfinement.toBlockFrame(mob, feet);
        BlockPos below = BlockPos.containing(local.x, local.y - 0.5, local.z);
        Level level = mob.level();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }
}
