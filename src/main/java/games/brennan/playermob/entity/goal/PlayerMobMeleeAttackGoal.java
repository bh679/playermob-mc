package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.MeleeSpacing;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.ReactionChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
    /** Sideways strafe speed of a fully skilled mob's back-step, so it backs off on an angle. */
    private static final float SIDESTEP_SPEED = 0.5F;
    /**
     * Ground blocks per tick per point of {@code MOVEMENT_SPEED} at speed modifier 1: a PlayerMob
     * (0.30) walks about 0.15 blocks a tick (measured: 8 blocks closed in ~2.5 s).
     */
    private static final double GROUND_SPEED_FACTOR = 0.5;

    private final PlayerMobEntity mob;
    private final double speed;
    private Path path;
    private long lastCanUseCheck;
    private int ticksUntilNextPathRecalculation;
    private int ticksUntilNextAttack;
    private double pathedTargetX;
    private double pathedTargetY;
    private double pathedTargetZ;
    /** Which side a skilled mob's next back-step angles to (+1 left, -1 right); flips every swing. */
    private int stepSide = 1;

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

        boolean sees = mob.getSensing().hasLineOfSight(target);
        // Unseen (a wall, door or partition between them) means no swing lands from here: keep pathing.
        // Hit-and-run: close in shortly before the swing recharges, keep out of the target's reach while
        // it recharges. Skill sets how far out that is (skill 0 just holds, as it always did).
        boolean ready = ticksUntilNextAttack <= leadTicks(skill, reach);
        MeleeSpacing.Move move = sees
            ? MeleeSpacing.move(skill, reach, ready, targetReach(target))
            : MeleeSpacing.Move.ADVANCE;
        switch (move) {
            case ADVANCE -> advance(target);
            case HOLD -> mob.getNavigation().stop();
            case RETREAT -> stepBack(target, skill);
        }

        ticksUntilNextAttack = Math.max(ticksUntilNextAttack - 1, 0);
        if (ticksUntilNextAttack <= 0 && sees && MeleeSpacing.inSwingReach(reach)) {
            ticksUntilNextAttack = ATTACK_INTERVAL_TICKS;
            stepSide = -stepSide;
            mob.swing(InteractionHand.MAIN_HAND);
            //? if >=26 {
            /*mob.doHurtTarget((net.minecraft.server.level.ServerLevel) mob.level(), target);
            *///?} else {
            mob.doHurtTarget(target);
            //?}
        }
    }

    /**
     * Vanilla's re-path cadence (timer and failure penalty always apply), except that a mob whose
     * navigation has stopped (it held, then the target stepped away) re-paths without waiting for the
     * target to have moved a block.
     */
    private void advance(LivingEntity target) {
        if (ticksUntilNextPathRecalculation > 0) return;
        boolean stopped = mob.getNavigation().isDone();
        boolean targetMoved = target.distanceToSqr(pathedTargetX, pathedTargetY, pathedTargetZ) >= 1.0
            || mob.getRandom().nextFloat() < 0.05F;
        if (!stopped && !targetMoved) return;
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

    /** How many ticks before its swing recharges the mob starts closing to strike distance. */
    private int leadTicks(int skill, double reach) {
        double gap = reach - MeleeSpacing.holdDistance(skill);
        double blocksPerTick = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * speed * GROUND_SPEED_FACTOR;
        return MeleeSpacing.leadTicks(gap, blocksPerTick);
    }

    /** How far {@code target}'s own swing reaches (eye to hitbox): a player's for players and PlayerMobs. */
    private static double targetReach(LivingEntity target) {
        return target instanceof Player || target instanceof PlayerMobEntity
            ? MeleeSpacing.PLAYER_REACH : MeleeSpacing.MOB_REACH;
    }

    /**
     * Step back from the target onto open floor; otherwise stand. A skilled mob backs off on an angle
     * (alternating sides each swing), as a player circles; if that way has no floor it tries straight back.
     */
    private void stepBack(LivingEntity target, int skill) {
        mob.getNavigation().stop();
        Vec3 away = new Vec3(mob.getX() - target.getX(), 0.0, mob.getZ() - target.getZ());
        if (away.lengthSqr() < 1.0E-4) return;
        mob.lookAt(target, 30.0F, 30.0F);
        Vec3 back = away.normalize();
        // Left of facing the target (the strafe "+x" side), from the yaw lookAt just set.
        Vec3 facing = Vec3.directionFromRotation(0.0F, mob.getYRot());
        Vec3 left = new Vec3(facing.z, 0.0, -facing.x);
        float side = (float) (stepSide * SIDESTEP_SPEED * MeleeSpacing.spacing(skill));
        if (side != 0.0F) {
            Vec3 angled = back.scale(-BACKSTEP_SPEED).add(left.scale(side)).normalize();
            if (clearToStep(angled)) {
                mob.getMoveControl().strafe(BACKSTEP_SPEED, side);
                return;
            }
        }
        if (clearToStep(back)) {
            mob.getMoveControl().strafe(BACKSTEP_SPEED, 0.0F);
        }
    }

    /** True if both the probe point and the mob's far edge, one step along {@code dir}, are standable. */
    private boolean clearToStep(Vec3 dir) {
        double far = BACKSTEP_PROBE + mob.getBbWidth() * 0.5;
        return standable(mob.position().add(dir.scale(BACKSTEP_PROBE)))
            && standable(mob.position().add(dir.scale(far)));
    }

    /**
     * True if a mob could stand with its feet at the world point {@code feet}: a solid block under it and
     * nothing solid at feet or head height. Read in the mob's block frame (a carriage's own, on a train).
     */
    private boolean standable(Vec3 feet) {
        Vec3 local = TrainConfinement.toBlockFrame(mob, feet);
        BlockPos cell = BlockPos.containing(local.x, local.y + 0.1, local.z);
        return solid(cell.below()) && !solid(cell) && !solid(cell.above());
    }

    private boolean solid(BlockPos pos) {
        Level level = mob.level();
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
