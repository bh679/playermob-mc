package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.DispositionResolver;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * The <b>Friendly</b> behaviour, as a one-shot greeting sequence performed when
 * the mob first notices an entity it currently reacts to with
 * {@link Reaction#GREET} (a friendly-natured mob toward someone it doesn't dislike):
 *
 * <ol>
 *   <li><b>Follow</b> — walk up to the friend.</li>
 *   <li><b>Crouch</b> — bob into a crouch a random 3–10 times as a greeting.</li>
 *   <li><b>Gift</b> — only once it has come to love them (feeling ≥ 7), give a
 *       present scaled by how loved they are ({@code GiftPolicy}): surplus it won't
 *       miss, spare gear, or a real upgrade. With nothing suitable in its pack it
 *       <b>fetches</b> a nearby dropped item to give; failing that, a flower.</li>
 * </ol>
 *
 * <p>When a cycle finishes the mob picks one of two things, matching the
 * requested behaviour: greet <em>again</em> (re-run immediately if the friend is
 * still near) or <em>disengage</em> for a spell — a cooldown during which this
 * goal stands down so the lower-priority raid/stroll goals get a turn (i.e. it
 * wanders off to a chest). Self-gates on {@code target == null} so it yields to
 * combat.</p>
 */
public final class FriendlyGreetGoal extends Goal implements DescribableGoal {

    private static final double FOLLOW_STOP_DISTANCE = 3.0;   // close enough to greet
    private static final int FOLLOW_TIMEOUT_TICKS = 100;      // greet in place if it can't get closer (5s)
    private static final int CROUCH_HALF_PERIOD_TICKS = 5;    // ticks per down / up half of a bob
    private static final int MIN_CROUCHES = 3;
    private static final int MAX_CROUCHES = 10;
    private static final int MIN_DISENGAGE_TICKS = 200;       // ~10s of "go raid / wander"
    private static final int MAX_DISENGAGE_TICKS = 400;       // ~20s
    private static final float CHANCE_TO_DISENGAGE = 0.5F;    // else greet again
    private static final int FETCH_TIMEOUT_TICKS = 100;       // 5s to reach a fetched gift before giving up
    private static final double FETCH_REACH_SQR = 4.0;        // 2 blocks — close enough to grab it

    private enum Phase { FOLLOW, CROUCH, GIFT, FETCH, DONE }

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
    private ItemEntity fetchItem;
    private int fetchTicks;

    public FriendlyGreetGoal(PlayerMobEntity mob, double range, double approachSpeed) {
        this.mob = mob;
        this.range = range;
        this.approachSpeed = approachSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Greeting";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case FOLLOW -> "approaching";
            case CROUCH -> "bowing";
            case GIFT -> "giving gift";
            default -> null;
        };
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

        switch (phase) {
            case FOLLOW -> { lookAtFriend(); tickFollow(); }
            case CROUCH -> { lookAtFriend(); tickCrouch(); }
            case GIFT -> { lookAtFriend(); tickGift(); }
            case FETCH -> tickFetch(); // looks at the item it's fetching, not the friend
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    private void lookAtFriend() {
        mob.getLookControl().setLookAt(friend, 30.0F, 30.0F);
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
        if (mob.feelingToward(friend) < DispositionResolver.FEELING_LOVE) {
            phase = Phase.DONE;
            return;
        }
        // Prefer a real item from the pack, its value scaled by how loved they are.
        ItemStack gift = mob.selectGiftFromInventory(friend);
        if (!gift.isEmpty()) {
            mob.tossGift(friend, gift);
            phase = Phase.DONE;
            return;
        }
        // Pack has nothing to give — go fetch a nearby dropped item to gift instead.
        ItemEntity nearby = mob.findGiftableNearbyItem(range);
        if (nearby != null) {
            fetchItem = nearby;
            fetchTicks = 0;
            phase = Phase.FETCH;
            mob.getNavigation().moveTo(fetchItem, approachSpeed);
            return;
        }
        // Nothing to give and nothing nearby — a flower keeps the gesture warm.
        mob.tossGift(friend, mob.trinketGift());
        phase = Phase.DONE;     // cycle complete — stop() decides greet-again vs disengage
    }

    /** Walk to the dropped item we chose to fetch, then lob it to the friend. */
    private void tickFetch() {
        if (fetchItem == null || !fetchItem.isAlive() || fetchItem.isRemoved()) {
            mob.tossGift(friend, mob.trinketGift()); // item vanished → flower fallback
            phase = Phase.DONE;
            return;
        }
        mob.getLookControl().setLookAt(fetchItem, 30.0F, 30.0F);
        if (mob.distanceToSqr(fetchItem) <= FETCH_REACH_SQR) {
            mob.getNavigation().stop();
            ItemStack gift = fetchItem.getItem().copy();
            fetchItem.discard();
            mob.tossGift(friend, gift);
            phase = Phase.DONE;
        } else if (++fetchTicks > FETCH_TIMEOUT_TICKS) {
            mob.tossGift(friend, mob.trinketGift()); // couldn't reach it → flower fallback
            phase = Phase.DONE;
        } else if (mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(fetchItem, approachSpeed); // re-issue if the path stalled
        }
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
        this.fetchItem = null;
        this.phase = Phase.DONE;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
