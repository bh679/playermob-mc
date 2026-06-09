package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * The <b>Skeptical</b> behaviour: when an entity the mob is
 * {@link Personality#SKEPTICAL} toward (players and/or hostile mobs) wanders
 * into view, stop whatever it was doing, face it, and hold a wary stare for
 * 1–5 seconds before resuming tasks. If that entity comes within
 * {@code closeRange}, ready defences — raise a held shield and/or pull a weapon
 * from the backpack into an empty main hand — and keep watching until it backs
 * off.
 *
 * <p>Self-gates on {@code target == null}, so it never fires mid-combat (the
 * mob fights via {@code WeaponAwareAttackGoal} instead). Owns MOVE+LOOK at
 * priority 1 so the stare briefly preempts strolling/raiding — "continue their
 * tasks" resumes once the stare timer elapses or the watched entity leaves.</p>
 */
public final class SkepticalWatchGoal extends Goal implements DescribableGoal {

    private static final int MIN_STARE_TICKS = 20;     // 1s
    private static final int MAX_STARE_TICKS = 100;    // 5s
    private static final int RESUME_COOLDOWN_TICKS = 80; // ~4s of "back to tasks" before glancing again

    private final PlayerMobEntity mob;
    private final double watchRange;
    private final double closeRange;

    private LivingEntity watched;
    private int stareTicksLeft;
    private int cooldownTicks;

    public SkepticalWatchGoal(PlayerMobEntity mob, double watchRange, double closeRange) {
        this.mob = mob;
        this.watchRange = watchRange;
        this.closeRange = closeRange;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Watching warily";
    }

    @Override
    public String subObjective() {
        if (watched == null) {
            return null;
        }
        return mob.isUsingItem() ? "guarding" : watched.getName().getString();
    }

    @Override
    public boolean canUse() {
        // After a stare elapses we sit out this cooldown so strolling/raiding
        // ("their tasks") gets a turn before the mob glances over again.
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        if (mob.getTarget() != null) return false; // fighting takes over
        LivingEntity candidate = mob.nearestWithPersonality(Personality.SKEPTICAL, watchRange);
        if (candidate == null) return false;
        this.watched = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return watched != null
            && watched.isAlive()
            && mob.getTarget() == null
            && mob.personalityToward(watched) == Personality.SKEPTICAL
            && mob.distanceTo(watched) <= watchRange + 4.0
            && (stareTicksLeft > 0 || mob.distanceTo(watched) <= closeRange);
    }

    @Override
    public void start() {
        this.stareTicksLeft = MIN_STARE_TICKS
            + mob.getRandom().nextInt(MAX_STARE_TICKS - MIN_STARE_TICKS + 1);
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (watched == null) return;
        mob.getLookControl().setLookAt(watched, 30.0F, 30.0F);
        mob.getNavigation().stop();
        if (stareTicksLeft > 0) stareTicksLeft--;

        // Too close → ready defences and keep watching while it lingers.
        if (mob.distanceTo(watched) <= closeRange) {
            mob.raiseShieldIfHeld();
            mob.drawWeaponFromBackpack();
        }

        // While the shield is up, square the body up to the threat so the block
        // actually deflects (vanilla only blocks hits from the facing direction).
        if (mob.isUsingItem()) {
            mob.faceBodyToward(watched);
        }
    }

    @Override
    public void stop() {
        mob.lowerShield();
        this.watched = null;
        this.stareTicksLeft = 0;
        this.cooldownTicks = RESUME_COOLDOWN_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
