package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * The <b>Friendly</b> behaviour: approach an entity the mob is
 * {@link Personality#FRIENDLY} toward (players, animals, villagers), lock eyes
 * with it, repeatedly bob into a crouch as a greeting gesture, and — for player
 * friends — periodically toss a gift from the backpack.
 *
 * <p>Self-gates on {@code target == null} so a friendly mob that's mid-fight
 * (e.g. defending against a zombie) doesn't try to greet at the same time.
 * Owns MOVE+LOOK at priority 1 so greeting preempts strolling/raiding while a
 * friend is near.</p>
 */
public final class FriendlyGreetGoal extends Goal {

    private static final double STOP_DISTANCE = 3.0;        // how close it approaches
    private static final int CROUCH_TOGGLE_TICKS = 10;      // ~0.5s bob cadence
    private static final int GIFT_COOLDOWN_MIN_TICKS = 100;  // 5s
    private static final int GIFT_COOLDOWN_MAX_TICKS = 200;  // 10s

    private final PlayerMobEntity mob;
    private final double range;
    private final double approachSpeed;

    private LivingEntity friend;
    private int crouchTimer;
    private boolean crouched;
    private int giftCooldown;

    public FriendlyGreetGoal(PlayerMobEntity mob, double range, double approachSpeed) {
        this.mob = mob;
        this.range = range;
        this.approachSpeed = approachSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null) return false;
        LivingEntity candidate = mob.nearestWithPersonality(Personality.FRIENDLY, range);
        if (candidate == null) return false;
        this.friend = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return friend != null
            && friend.isAlive()
            && mob.getTarget() == null
            && mob.personalityToward(friend) == Personality.FRIENDLY
            && mob.distanceTo(friend) <= range + 4.0;
    }

    @Override
    public void start() {
        this.crouchTimer = 0;
        this.crouched = false;
        this.giftCooldown = GIFT_COOLDOWN_MIN_TICKS;
    }

    @Override
    public void tick() {
        if (friend == null) return;
        mob.getLookControl().setLookAt(friend, 30.0F, 30.0F);

        // Approach until comfortably close, then hold position.
        if (mob.distanceTo(friend) > STOP_DISTANCE) {
            mob.getNavigation().moveTo(friend, approachSpeed);
        } else {
            mob.getNavigation().stop();
        }

        // Greeting bob — toggle the crouch flag on a steady cadence.
        if (++crouchTimer >= CROUCH_TOGGLE_TICKS) {
            crouchTimer = 0;
            crouched = !crouched;
            mob.setShiftKeyDown(crouched);
        }

        // Gift: only to players, on a cooldown, when the backpack has something.
        if (giftCooldown > 0) {
            giftCooldown--;
        } else if (friend instanceof Player && mob.distanceTo(friend) <= STOP_DISTANCE + 2.0) {
            if (mob.giveItemTo(friend)) {
                giftCooldown = GIFT_COOLDOWN_MIN_TICKS
                    + mob.getRandom().nextInt(GIFT_COOLDOWN_MAX_TICKS - GIFT_COOLDOWN_MIN_TICKS + 1);
            } else {
                // Nothing to give — wait a beat before checking again.
                giftCooldown = GIFT_COOLDOWN_MIN_TICKS;
            }
        }
    }

    @Override
    public void stop() {
        mob.setShiftKeyDown(false);
        mob.getNavigation().stop();
        this.friend = null;
        this.crouched = false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
