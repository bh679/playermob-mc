package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Executes a one-off {@link Order} placed on a PlayerMob by the
 * {@code /playermob order} command — the only goal that overrides the mob's
 * autonomous behaviour on an explicit player command.
 *
 * <p>Registered at priority 1 (before the social/recovery goals) so an order wins
 * the MOVE/LOOK slot while it runs; it claims no JUMP flag, so the priority-0
 * {@code FloatGoal} still keeps the mob afloat (see the goal-JUMP-vs-FloatGoal
 * gotcha). A small phase machine walks to the target, performs the action, then
 * clears the order:</p>
 *
 * <pre>PATH → ACT → DONE</pre>
 *
 * <p>Reuses the entity's own gifting helpers ({@link PlayerMobEntity#tossGift},
 * {@link PlayerMobEntity#selectGiftFromInventory}) and mirrors the crouch-bow of
 * {@link FriendlyGreetGoal} for the greeting. {@link OrderType#ATTACK} is handled
 * directly by the command (it just sets a combat target), so it never reaches
 * this goal.</p>
 */
public final class CommandedActionGoal extends Goal implements DescribableGoal {

    private static final int WALK_TIMEOUT_TICKS = 200;   // 10s to reach the target before giving up
    private static final double ENTITY_REACH_SQR = 9.0;  // 3 blocks — close enough to punch/gift/greet
    private static final double WALK_ARRIVE_SQR = 4.0;    // 2 blocks — "arrived" at a position
    private static final double PLACE_REACH_SQR = 20.25;  // 4.5 blocks — close enough to place

    private static final int CROUCH_HALF_PERIOD_TICKS = 5; // ticks per down / up half of a greeting bob
    private static final int MIN_CROUCHES = 3;
    private static final int MAX_CROUCHES = 10;

    private enum Phase { PATH, ACT, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Order order;
    private Phase phase = Phase.DONE;
    private int walkTicks;

    // Greeting crouch state (mirrors FriendlyGreetGoal).
    private int crouchesTarget;
    private int crouchesDone;
    private int halfPeriodTicks;
    private boolean crouchDown;

    public CommandedActionGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        // MOVE + LOOK only — no JUMP, so FloatGoal can still own JUMP in water.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getOrder() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.DONE
            && order != null
            && targetAlive();
    }

    /** An entity-directed order ends early if its target vanishes; position orders never do. */
    private boolean targetAlive() {
        LivingEntity target = order.targetEntity();
        return target == null || (target.isAlive() && !target.isRemoved());
    }

    @Override
    public void start() {
        this.order = mob.getOrder();
        this.phase = Phase.PATH;
        this.walkTicks = 0;
    }

    @Override
    public void tick() {
        if (order == null) {
            phase = Phase.DONE;
            return;
        }
        lookAtDestination();
        switch (phase) {
            case PATH -> tickPath();
            case ACT -> tickAct();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    /** Centre of the order's destination, whether an entity or a fixed position. */
    private Vec3 destination() {
        LivingEntity target = order.targetEntity();
        if (target != null) {
            return target.position();
        }
        BlockPos pos = order.targetPos();
        return pos != null ? Vec3.atCenterOf(pos) : mob.position();
    }

    private void lookAtDestination() {
        LivingEntity target = order.targetEntity();
        if (target != null) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        } else if (order.targetPos() != null) {
            Vec3 c = Vec3.atCenterOf(order.targetPos());
            mob.getLookControl().setLookAt(c.x, c.y, c.z);
        }
    }

    private double reachSqr() {
        return switch (order.type()) {
            case PLACE -> PLACE_REACH_SQR;
            case WALK -> order.targetEntity() != null ? ENTITY_REACH_SQR : WALK_ARRIVE_SQR;
            default -> ENTITY_REACH_SQR;
        };
    }

    private void tickPath() {
        if (mob.distanceToSqr(destination()) <= reachSqr()) {
            mob.getNavigation().stop();
            enterAct();
            return;
        }
        if (++walkTicks > WALK_TIMEOUT_TICKS) {
            // Couldn't reach it in time — abandon the order rather than stall forever.
            phase = Phase.DONE;
            return;
        }
        LivingEntity target = order.targetEntity();
        if (target != null) {
            mob.getNavigation().moveTo(target, speed);
        } else {
            Vec3 d = destination();
            mob.getNavigation().moveTo(d.x, d.y, d.z, speed);
        }
    }

    /** Arrived — perform the action (one-shot, except the multi-tick greeting bob). */
    private void enterAct() {
        phase = Phase.ACT;
        if (order.type() == OrderType.GREET) {
            crouchesTarget = MIN_CROUCHES + mob.getRandom().nextInt(MAX_CROUCHES - MIN_CROUCHES + 1);
            crouchesDone = 0;
            halfPeriodTicks = 0;
            crouchDown = true;
            mob.setCrouching(true);
        }
    }

    private void tickAct() {
        switch (order.type()) {
            case WALK -> phase = Phase.DONE;
            case PUNCH -> doPunch();
            case GIFT -> doGift();
            case GREET -> tickGreet();
            case PLACE -> doPlace();
            default -> phase = Phase.DONE; // ATTACK never routes here
        }
    }

    private void doPunch() {
        LivingEntity target = order.targetEntity();
        if (target != null) {
            mob.swing(InteractionHand.MAIN_HAND);
            //? if >=26 {
            /*mob.doHurtTarget((net.minecraft.server.level.ServerLevel) mob.level(), target);
            *///?} else {
            mob.doHurtTarget(target);
            //?}
        }
        phase = Phase.DONE;
    }

    private void doGift() {
        LivingEntity target = order.targetEntity();
        if (target != null) {
            mob.tossGift(target, chooseGift(target));
        }
        phase = Phase.DONE;
    }

    /**
     * Pick a gift for an ordered hand-over: the best the pack offers, else a nearby
     * dropped item if one is right here, else a token flower — so an explicit order
     * always produces a gift (unlike the love-gated greeting gift).
     */
    private ItemStack chooseGift(LivingEntity target) {
        ItemStack gift = mob.selectGiftFromInventory(target);
        if (gift.isEmpty()) {
            ItemEntity nearby = mob.findGiftableNearbyItem(4.0);
            if (nearby != null) {
                gift = nearby.getItem().copy();
                nearby.discard();
            }
        }
        return gift.isEmpty() ? mob.trinketGift() : gift;
    }

    private void tickGreet() {
        if (++halfPeriodTicks >= CROUCH_HALF_PERIOD_TICKS) {
            halfPeriodTicks = 0;
            crouchDown = !crouchDown;
            if (!crouchDown && ++crouchesDone >= crouchesTarget) {
                mob.setCrouching(false);
                phase = Phase.DONE;
                return;
            }
        }
        mob.setCrouching(crouchDown); // assert every tick so nothing resets the pose mid-bob
    }

    private void doPlace() {
        BlockPos pos = order.targetPos();
        if (pos != null && order.blockState() != null) {
            mob.level().setBlock(pos, order.blockState(), 3); // flag 3 = update + notify neighbours
            mob.swing(InteractionHand.MAIN_HAND);
        }
        phase = Phase.DONE;
    }

    @Override
    public void stop() {
        mob.setCrouching(false);
        mob.getNavigation().stop();
        mob.clearOrder();   // an order is one-shot — consumed whether it completed or was interrupted
        this.order = null;
        this.phase = Phase.DONE;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public String objective() {
        return "Following order";
    }

    @Override
    public String subObjective() {
        if (order == null) {
            return null;
        }
        return switch (order.type()) {
            case WALK -> "walking";
            case PUNCH -> "punching";
            case GIFT -> "gifting";
            case GREET -> "greeting";
            case PLACE -> "placing block";
            default -> null;
        };
    }
}
