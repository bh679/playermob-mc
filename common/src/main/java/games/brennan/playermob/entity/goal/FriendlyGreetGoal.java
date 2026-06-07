package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.DispositionResolver;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * The <b>Friendly</b> behaviour, as a one-shot greeting sequence performed when
 * the mob first notices an entity it currently reacts to with
 * {@link Reaction#GREET} (a friendly-natured mob toward someone it doesn't dislike):
 *
 * <ol>
 *   <li><b>Follow</b> — walk up to the friend.</li>
 *   <li><b>Crouch</b> — bob into a crouch a random 3–10 times as a greeting.</li>
 *   <li><b>Gift</b> — only once it has come to love them (feeling ≥ 7), drop one
 *       item from the backpack toward the friend (a default token if empty).</li>
 * </ol>
 *
 * <p>When a cycle finishes the mob picks one of two things, matching the
 * requested behaviour: greet <em>again</em> (re-run immediately if the friend is
 * still near) or <em>disengage</em> for a spell — a cooldown during which this
 * goal stands down so the lower-priority raid/stroll goals get a turn (i.e. it
 * wanders off to a chest). Self-gates on {@code target == null} so it yields to
 * combat.</p>
 */
public final class FriendlyGreetGoal extends Goal {

    private static final double FOLLOW_STOP_DISTANCE = 3.0;   // close enough to greet
    private static final int FOLLOW_TIMEOUT_TICKS = 100;      // greet in place if it can't get closer (5s)
    private static final int CROUCH_HALF_PERIOD_TICKS = 5;    // ticks per down / up half of a bob
    private static final int MIN_CROUCHES = 3;
    private static final int MAX_CROUCHES = 10;
    private static final int MIN_DISENGAGE_TICKS = 200;       // ~10s of "go raid / wander"
    private static final int MAX_DISENGAGE_TICKS = 400;       // ~20s
    private static final float CHANCE_TO_DISENGAGE = 0.5F;    // else greet again

    private enum Phase { FOLLOW, CROUCH, GIFT, DONE }

    private final PlayerMobEntity mob;
    private final double range;
    private final double approachSpeed;

    private LivingEntity friend;
    private Phase phase = Phase.DONE;
    private int followTicks;
    private int crouchesTarget;
    private int crouchesDone;
    private int halfPeriodTicks;
    private boolean crouchDown;
    private int cooldownTicks;

    public FriendlyGreetGoal(PlayerMobEntity mob, double range, double approachSpeed) {
        this.mob = mob;
        this.range = range;
        this.approachSpeed = approachSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Disengage cooldown — sit out so the mob can go raid / wander between greets.
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        if (mob.getTarget() != null) return false;
        LivingEntity candidate = mob.nearestWhereReaction(Reaction.GREET, range);
        if (candidate == null) return false;
        this.friend = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.DONE
            && friend != null
            && friend.isAlive()
            && mob.getTarget() == null
            && mob.reactionToward(friend) == Reaction.GREET
            && mob.distanceTo(friend) <= range + 6.0;
    }

    @Override
    public void start() {
        this.phase = Phase.FOLLOW;
        this.followTicks = 0;
        this.crouchesTarget = MIN_CROUCHES + mob.getRandom().nextInt(MAX_CROUCHES - MIN_CROUCHES + 1);
        this.crouchesDone = 0;
        this.halfPeriodTicks = 0;
        this.crouchDown = false;
    }

    @Override
    public void tick() {
        if (friend == null) return;
        mob.getLookControl().setLookAt(friend, 30.0F, 30.0F);

        switch (phase) {
            case FOLLOW -> tickFollow();
            case CROUCH -> tickCrouch();
            case GIFT -> tickGift();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    private void tickFollow() {
        boolean closeEnough = mob.distanceTo(friend) <= FOLLOW_STOP_DISTANCE;
        if (closeEnough || ++followTicks > FOLLOW_TIMEOUT_TICKS) {
            mob.getNavigation().stop();
            phase = Phase.CROUCH;
            halfPeriodTicks = 0;
            crouchDown = true;
            mob.setCrouching(true); // first crouch-down
            return;
        }
        mob.getNavigation().moveTo(friend, approachSpeed);
    }

    private void tickCrouch() {
        mob.getNavigation().stop();
        if (++halfPeriodTicks >= CROUCH_HALF_PERIOD_TICKS) {
            halfPeriodTicks = 0;
            crouchDown = !crouchDown;
            if (!crouchDown && ++crouchesDone >= crouchesTarget) {
                // Completed the last bob, ending upright → move on to the gift.
                phase = Phase.GIFT;
                return;
            }
        }
        // Assert the crouch state every tick so nothing resets the pose mid-bob.
        mob.setCrouching(crouchDown);
    }

    private void tickGift() {
        mob.setCrouching(false);
        // A friendly mob greets everyone it likes, but only parts with a gift for
        // someone it has truly come to love (feeling >= 7).
        if (mob.feelingToward(friend) >= DispositionResolver.FEELING_LOVE) {
            mob.giveItemTo(friend); // tosses a backpack item, or a default token gift if empty
        }
        phase = Phase.DONE;     // cycle complete — stop() decides greet-again vs disengage
    }

    @Override
    public void stop() {
        mob.setCrouching(false);
        mob.getNavigation().stop();
        // Only a *completed* greet rolls the disengage choice; an interrupted one
        // (friend left / combat) leaves the cooldown at 0 so it can resume freely.
        if (phase == Phase.DONE && mob.getRandom().nextFloat() < CHANCE_TO_DISENGAGE) {
            cooldownTicks = MIN_DISENGAGE_TICKS
                + mob.getRandom().nextInt(MAX_DISENGAGE_TICKS - MIN_DISENGAGE_TICKS + 1);
        }
        this.friend = null;
        this.phase = Phase.DONE;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
