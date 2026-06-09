package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * The <b>Shy</b> behaviour, as a flee → hide loop toward any entity the mob is
 * {@link Personality#SHY} toward (players and/or hostile mobs — Shy is
 * disallowed for animals/villagers):
 *
 * <ol>
 *   <li><b>Flee</b> — sprint to a position away from the threat (vanilla's
 *       {@link DefaultRandomPos#getPosAway} retreat picker, same as
 *       {@code AvoidEntityGoal}).</li>
 *   <li><b>Hide</b> — once it's opened up some distance, stop, crouch, and keep
 *       watching the threat for a random 2–15 seconds (peeking from cover)
 *       before returning to its tasks. If the threat closes back in during the
 *       watch, it bolts again.</li>
 * </ol>
 *
 * <p>Registered at priority 1 and deliberately does <em>not</em> gate on
 * {@code target == null}, so a provoked-shy mob flees instead of fighting.
 * Crouching is cosmetic (see {@link PlayerMobEntity#setCrouching}); the mob
 * keeps full flee speed.</p>
 */
public final class FleeFromCategoryGoal extends Goal implements DescribableGoal {

    private static final double DETECT_RANGE_BONUS = 6.0;     // notice the threat a bit beyond flee range
    private static final double RETURN_TO_FLEE_MARGIN = 2.0;  // while hiding, re-flee if threat gets within (fleeDistance - margin)
    private static final double PANIC_RANGE = 5.0;            // this close → always flee, never brave a chest
    private static final int BRAVE_RADIUS = 12;               // sneak-raid a chest within this instead of fleeing
    private static final int HIDE_MIN_TICKS = 40;            // 2s
    private static final int HIDE_MAX_TICKS = 300;           // 15s
    private static final int RESUME_COOLDOWN_TICKS = 60;     // ~3s back-to-tasks before re-triggering
    private static final int RETREAT_RADIUS = 16;
    private static final int RETREAT_VERTICAL = 7;

    private enum Phase { FLEE, HIDE }

    private final PlayerMobEntity mob;
    private final double fleeDistance;
    private final double detectRange;
    private final double walkSpeed;
    private final double sprintSpeed;

    private Phase phase = Phase.FLEE;
    private LivingEntity threat;
    private int hideTicksLeft;
    private int cooldownTicks;

    public FleeFromCategoryGoal(PlayerMobEntity mob, float fleeDistance,
                                double walkSpeed, double sprintSpeed) {
        this.mob = mob;
        this.fleeDistance = fleeDistance;
        this.detectRange = fleeDistance + DETECT_RANGE_BONUS;
        this.walkSpeed = walkSpeed;
        this.sprintSpeed = sprintSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Fleeing";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case FLEE -> "running";
            case HIDE -> "hiding";
        };
    }

    @Override
    public boolean canUse() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        LivingEntity candidate = mob.nearestWithPersonality(Personality.SHY, detectRange);
        if (candidate == null) return false;
        // Unless the threat is right on top of us, if there's a chest worth braving
        // yield so the raid goal can sneak-grab it (crouching). Once it's looted the
        // chest is no longer raidable, so this goal fires again and the mob flees.
        if (mob.distanceTo(candidate) > PANIC_RANGE
                && mob.canRaid()
                && mob.hasRaidableContainerNearby(BRAVE_RADIUS)) {
            return false;
        }
        this.threat = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (threat == null || !threat.isAlive()) return false;
        if (mob.personalityToward(threat) != Personality.SHY) return false;
        if (mob.distanceTo(threat) > detectRange + 4.0) return false;
        return phase == Phase.FLEE || hideTicksLeft > 0;
    }

    @Override
    public void start() {
        this.phase = Phase.FLEE;
        this.hideTicksLeft = 0;
    }

    @Override
    public void tick() {
        if (threat == null) return;
        mob.getLookControl().setLookAt(threat, 30.0F, 30.0F); // keep eyes on the threat
        double dist = mob.distanceTo(threat);

        switch (phase) {
            case FLEE -> {
                mob.setCrouching(false);
                if (dist >= fleeDistance) {
                    // Opened up enough → hide and peek.
                    mob.getNavigation().stop();
                    phase = Phase.HIDE;
                    hideTicksLeft = HIDE_MIN_TICKS
                        + mob.getRandom().nextInt(HIDE_MAX_TICKS - HIDE_MIN_TICKS + 1);
                    mob.setCrouching(true);
                } else if (mob.getNavigation().isDone()) {
                    fleeAway();
                }
            }
            case HIDE -> {
                mob.getNavigation().stop();
                mob.setCrouching(true);
                hideTicksLeft--;
                if (dist <= fleeDistance - RETURN_TO_FLEE_MARGIN) {
                    phase = Phase.FLEE; // threat closing back in — bolt further away
                    mob.setCrouching(false);
                }
            }
        }
    }

    private void fleeAway() {
        Vec3 away = DefaultRandomPos.getPosAway(mob, RETREAT_RADIUS, RETREAT_VERTICAL, threat.position());
        if (away != null) {
            // Panic-sprint when the threat is close; settle to a walk once farther off.
            double speed = mob.distanceTo(threat) < fleeDistance * 0.5 ? sprintSpeed : walkSpeed;
            mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
        }
    }

    @Override
    public void stop() {
        mob.setCrouching(false);
        mob.getNavigation().stop();
        this.threat = null;
        this.phase = Phase.FLEE;
        this.hideTicksLeft = 0;
        this.cooldownTicks = RESUME_COOLDOWN_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
