package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.StayAnchor;
import games.brennan.playermob.entity.StayNearPolicy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Keeps a {@link PlayerMobEntity} tethered near its {@link StayAnchor} — a fixed position or a live
 * entity — so it never wanders (or is dragged by a fight) more than the anchor's radius away, then
 * lets it resume normal behaviour once it's walked back inside.
 *
 * <p><b>Leash, not glue</b> (mirrors {@link FollowLovedOneGoal}). The goal is a no-op while the mob
 * is inside its radius, so it does its own thing — raiding, harvesting, strolling. It engages only
 * once the mob drifts <em>past</em> the radius ({@link StayNearPolicy#beyond}), walks it back at a
 * calm {@link StayNearPolicy#RETURN_SPEED}, and releases once comfortably inside
 * ({@link StayNearPolicy#keep} — hysteresis, so it doesn't flicker on the boundary).</p>
 *
 * <p><b>Priority &amp; combat.</b> goalSelector priority 2, registered after the attack and
 * {@link FollowLovedOneGoal} goals; mutually exclusive with the attack goal via the
 * {@code target == null} self-gate (a target means fight, none means maybe return home). So the
 * tether <em>yields to combat</em> — a mob may chase a foe past its radius, then walks back once the
 * fight ends — while still preempting the autonomous own-tasks (raid 3, harvest 6, stroll 8) that
 * cause the wandering. Also yields to the get-back-aboard / gap-leap reflexes.</p>
 *
 * <p><b>Entity anchors.</b> When the anchored entity is unloaded or gone, {@link StayAnchor#resolvePos}
 * returns {@code null} and the goal simply idles — the anchor is kept, so the tether resumes when the
 * entity comes back.</p>
 */
public final class StayNearGoal extends Goal implements DescribableGoal {

    /** Re-path cadence: an entity anchor moves, so re-issue moveTo every ½s. */
    private static final int REPATH_INTERVAL = 10;

    private final PlayerMobEntity mob;

    private Vec3 target;
    private String anchorLabel;
    private int repathCooldown;

    public StayNearGoal(PlayerMobEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Staying near";
    }

    @Override
    public String subObjective() {
        return anchorLabel;
    }

    @Override
    public boolean canUse() {
        StayAnchor anchor = mob.getStayAnchor();
        if (anchor == null || mob.getTarget() != null || mob.isRecovering() || mob.isCrossingGap()) {
            return false; // no tether, or fight / get-back-aboard / mid-leap take precedence
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        Vec3 pos = anchor.resolvePos(level);
        if (pos == null || !StayNearPolicy.beyond(mob.position().distanceTo(pos), anchor.radius())) {
            return false; // inside the radius (or anchor entity gone) — behave normally
        }
        this.target = pos;
        this.anchorLabel = anchor.label(level);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        StayAnchor anchor = mob.getStayAnchor();
        if (anchor == null || mob.getTarget() != null || mob.isRecovering() || mob.isCrossingGap()
                || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        Vec3 pos = anchor.resolvePos(level);
        if (pos == null) {
            return false;
        }
        this.target = pos; // track a moving entity anchor
        return StayNearPolicy.keep(mob.position().distanceTo(pos), anchor.radius());
    }

    @Override
    public void start() {
        repathCooldown = 0;
        issueMove();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
        anchorLabel = null;
        repathCooldown = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        mob.getLookControl().setLookAt(target.x, target.y, target.z);
        if (--repathCooldown <= 0 || mob.getNavigation().isDone()) {
            repathCooldown = REPATH_INTERVAL;
            issueMove();
        }
    }

    /** Walk back toward the anchor at a calm pace — the tether only engages when already outside it. */
    private void issueMove() {
        if (target != null) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, StayNearPolicy.RETURN_SPEED);
        }
    }
}
