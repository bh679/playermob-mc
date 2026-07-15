package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
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
 * <pre>PATH → ACT → [FLEE] → DONE</pre>
 *
 * <p>Reuses the entity's own gifting helpers ({@link PlayerMobEntity#tossGift},
 * {@link PlayerMobEntity#selectGiftFromInventory}), mirrors the crouch-bow of
 * {@link FriendlyGreetGoal} for the greeting, and the {@link DefaultRandomPos}
 * retreat picker of {@link FleeFromCategoryGoal} for a steal's getaway.
 * {@link OrderType#ATTACK} is handled directly by the command (it just sets a
 * combat target), so it never reaches this goal.</p>
 */
public final class CommandedActionGoal extends Goal implements DescribableGoal {

    // How far to scan for a threat worth yielding an interruptible order to (matches the flee goal's
    // detectRange = fleeDistance 10 + DETECT_RANGE_BONUS 6). The order itself never times out here —
    // PlayerMobEntity#tickOrderTimeout bounds the whole order lifetime.
    private static final double FLEE_DETECT_RANGE = 16.0;
    private static final double ENTITY_REACH_SQR = 9.0;  // 3 blocks — close enough to punch/gift/greet/steal
    private static final double WALK_ARRIVE_SQR = 4.0;    // 2 blocks — "arrived" at a position
    private static final double PLACE_REACH_SQR = 20.25;  // 4.5 blocks — close enough to place
    private static final double PUNCH_AT_STANDOFF_SQR = 20.25; // 4.5 blocks — just out of melee reach

    private static final int CROUCH_HALF_PERIOD_TICKS = 5; // ticks per down / up half of a greeting bob
    private static final int MIN_CROUCHES = 3;
    private static final int MAX_CROUCHES = 10;

    private static final int SWING_INTERVAL_TICKS = 8;   // gap between punch-at taunt swings
    private static final int MIN_PUNCH_AT_SWINGS = 2;    // taunt swing count is randomised 2–6
    private static final int MAX_PUNCH_AT_SWINGS = 6;
    // Hold facing the target after a one-shot strike so the ~6-tick swing plays out before the mob
    // releases control and turns away — otherwise the swing reads as "no animation" (like a player,
    // who stays planted through the swing).
    private static final int STRIKE_FOLLOW_THROUGH_TICKS = 8;

    private static final int FLEE_DEFAULT_TICKS = 100;   // 5s of running off when a steal has no limit
    private static final int FLEE_MAX_TICKS = 400;       // 20s safety cap (e.g. an unreachable block goal)
    private static final int FLEE_REPATH_INTERVAL = 10;  // re-pick a retreat point every 0.5s
    private static final int RETREAT_RADIUS = 16;
    private static final int RETREAT_VERTICAL = 7;
    private static final double FLEE_SPEED = 1.3;        // sprint away

    private enum Phase { PATH, ACT, FLEE, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Order order;
    private Phase phase = Phase.DONE;

    // Greeting crouch state (mirrors FriendlyGreetGoal).
    private int crouchesTarget;
    private int crouchesDone;
    private int halfPeriodTicks;
    private boolean crouchDown;

    // Punch-at taunt state.
    private int swingTicks;
    private int swingsDone;
    private int punchAtSwingsTarget;

    // One-shot strike (punch / single-strike attack) follow-through.
    private boolean struck;
    private int followThroughTicks;

    // Steal getaway state.
    private int fleeStartTick;
    private Vec3 fleeOrigin = Vec3.ZERO;
    private int fleeRepathTicks;

    public CommandedActionGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        // MOVE + LOOK only — no JUMP, so FloatGoal can still own JUMP in water.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Order pending = mob.getOrder();
        // An interruptible order defers to a live threat (combat / flee) so the mob reacts first,
        // then re-acquires and resumes once the threat clears. A nonstop order starts regardless.
        return pending != null && !(pending.interruptible() && reactionActive());
    }

    @Override
    public boolean canContinueToUse() {
        // The order was timed out by tickOrderTimeout, or replaced by a newer one — let go of the
        // stale one (the goal caches `order`, so it wouldn't otherwise notice the entity cleared it).
        if (order == null || mob.getOrder() != order || phase == Phase.DONE) {
            return false;
        }
        if (phase == Phase.FLEE) {
            return true;   // the steal getaway is itself the action — never treat it as an interruption
        }
        // Yield to a reaction mid-order (interruptible only) so combat/flee take over; the order
        // stays pending and this goal restarts from PATH once the threat is gone.
        if (order.interruptible() && reactionActive()) {
            return false;
        }
        return targetAlive();
    }

    /** True while a live threat (a combat target, or something to flee) warrants dropping the order. */
    private boolean reactionActive() {
        return mob.getTarget() != null
            || mob.nearestWhereReaction(Reaction.FLEE, FLEE_DETECT_RANGE) != null;
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
        this.struck = false;
    }

    @Override
    public void tick() {
        if (order == null) {
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case PATH -> { lookAtDestination(); tickPath(); }
            case ACT -> { lookAtDestination(); tickAct(); }
            case FLEE -> tickFlee();
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
            case PLACE -> order.targetEntity() != null ? ENTITY_REACH_SQR : PLACE_REACH_SQR;
            case PUNCH_AT -> PUNCH_AT_STANDOFF_SQR;
            case USE -> order.targetEntity() != null ? ENTITY_REACH_SQR : PLACE_REACH_SQR;
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
        // No local give-up here: the order persists until it's executed or PlayerMobEntity#tickOrderTimeout
        // abandons it (default 2 min). Re-path every tick so a moving/blocked target is still pursued.
        LivingEntity target = order.targetEntity();
        if (target != null) {
            mob.getNavigation().moveTo(target, speed);
        } else {
            Vec3 d = destination();
            mob.getNavigation().moveTo(d.x, d.y, d.z, speed);
        }
    }

    /** Arrived — perform the action (one-shot, except the multi-tick greeting / taunt / steal-flee). */
    private void enterAct() {
        phase = Phase.ACT;
        switch (order.type()) {
            case GREET -> {
                crouchesTarget = MIN_CROUCHES + mob.getRandom().nextInt(MAX_CROUCHES - MIN_CROUCHES + 1);
                crouchesDone = 0;
                halfPeriodTicks = 0;
                crouchDown = true;
                mob.setCrouching(true);
            }
            case PUNCH_AT -> {
                swingTicks = 0;
                swingsDone = 0;
                punchAtSwingsTarget = MIN_PUNCH_AT_SWINGS
                    + mob.getRandom().nextInt(MAX_PUNCH_AT_SWINGS - MIN_PUNCH_AT_SWINGS + 1);
            }
            default -> { /* one-shot actions act immediately in tickAct */ }
        }
    }

    private void tickAct() {
        switch (order.type()) {
            case WALK -> phase = Phase.DONE;
            case PUNCH -> doPunch();
            case PUNCH_AT -> tickPunchAt();
            case GIFT -> doGift();
            case GREET -> tickGreet();
            case STEAL -> doSteal();
            case USE -> doUse();
            case PLACE -> doPlace();
            default -> phase = Phase.DONE; // ATTACK never routes here
        }
    }

    private void doPunch() {
        if (!struck) {
            // Strike once, then plant + face the target (nav stopped here; the look is asserted each
            // ACT tick) so the swing animation plays out before the order releases — like a player.
            LivingEntity target = order.targetEntity();
            if (target != null) {
                faceBodyToward(target);   // body faces the target so the swing points at it
                mob.swing(InteractionHand.MAIN_HAND);
                //? if >=26 {
                /*mob.doHurtTarget((net.minecraft.server.level.ServerLevel) mob.level(), target);
                *///?} else {
                mob.doHurtTarget(target);
                //?}
            }
            mob.getNavigation().stop();
            struck = true;
            followThroughTicks = STRIKE_FOLLOW_THROUGH_TICKS;
        }
        if (--followThroughTicks <= 0) {
            phase = Phase.DONE;
        }
    }

    /** Throw a few punch motions from out of reach — a taunt that never lands. */
    private void tickPunchAt() {
        // Face the BODY at the target (the look control only turns the head) so the swing reads as
        // a punch toward you, not a sideways flail.
        LivingEntity target = order.targetEntity();
        if (target != null) {
            faceBodyToward(target);
        }
        if (swingTicks % SWING_INTERVAL_TICKS == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
            swingsDone++;
        }
        swingTicks++;
        if (swingsDone >= punchAtSwingsTarget) {
            phase = Phase.DONE;
        }
    }

    /** Snap the mob's body yaw to face {@code target}, so an arm swing points at it. */
    private void faceBodyToward(LivingEntity target) {
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yBodyRotO = yaw;
    }

    private void doGift() {
        LivingEntity target = order.targetEntity();
        if (target != null) {
            // A named item is gifted verbatim (conjured); otherwise pick the best from the pack.
            ItemStack gift = order.item().isEmpty() ? chooseGift(target) : order.item().copy();
            mob.tossGift(target, gift);
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

    /** Snatch the target's held item into the pack, then run for it. */
    private void doSteal() {
        LivingEntity target = order.targetEntity();
        if (target != null) {
            ItemStack hand = target.getMainHandItem();
            if (!hand.isEmpty()) {
                ItemStack taken = hand.copy();
                target.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                ItemStack leftover = mob.getInventory().addItem(taken);
                if (!leftover.isEmpty()) {
                    mob.dropAtLocation(leftover);
                }
            }
        }
        phase = Phase.FLEE;
        fleeStartTick = mob.tickCount;
        fleeOrigin = mob.position();
        fleeRepathTicks = 0;
    }

    private void tickFlee() {
        int elapsed = mob.tickCount - fleeStartTick;
        boolean done = switch (order.unit()) {
            case SECONDS -> elapsed >= order.amount() * 20;
            case BLOCKS -> mob.position().distanceTo(fleeOrigin) >= order.amount() || elapsed > FLEE_MAX_TICKS;
            case NONE -> elapsed >= FLEE_DEFAULT_TICKS;
        };
        if (done) {
            phase = Phase.DONE;
            return;
        }
        if (++fleeRepathTicks >= FLEE_REPATH_INTERVAL || mob.getNavigation().isDone()) {
            fleeRepathTicks = 0;
            LivingEntity target = order.targetEntity();
            Vec3 threat = target != null ? target.position() : fleeOrigin;
            Vec3 away = DefaultRandomPos.getPosAway(mob, RETREAT_RADIUS, RETREAT_VERTICAL, threat);
            if (away != null) {
                mob.getNavigation().moveTo(away.x, away.y, away.z, FLEE_SPEED);
            }
        }
    }

    private void doUse() {
        CommandedUse.perform(mob, order.item(), order.targetEntity(), order.targetPos());
        phase = Phase.DONE;
    }

    private void doPlace() {
        if (order.blockState() != null) {
            // A fixed pos, or — for a live entity target — its current feet block (chased down).
            BlockPos pos = order.targetEntity() != null
                ? placePosNear(order.targetEntity())
                : order.targetPos();
            if (pos != null) {
                mob.level().setBlock(pos, order.blockState(), 3); // flag 3 = update + notify neighbours
                mob.swing(InteractionHand.MAIN_HAND);
            }
        }
        phase = Phase.DONE;
    }

    /** The target's feet block if replaceable, else one above — "as close as possible (+1 ok)". */
    private BlockPos placePosNear(LivingEntity target) {
        BlockPos feet = target.blockPosition();
        return mob.level().getBlockState(feet).canBeReplaced() ? feet : feet.above();
    }

    @Override
    public void stop() {
        mob.setCrouching(false);
        mob.getNavigation().stop();
        // Consume the order only on genuine completion (every action routes through phase == DONE).
        // A yield/interruption or a newer order leaves phase at PATH/ACT, so the pending order stays
        // put and this goal re-acquires it later (bounded by PlayerMobEntity#tickOrderTimeout).
        if (phase == Phase.DONE && mob.getOrder() == order) {
            mob.clearOrder();
        }
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
        if (phase == Phase.FLEE) {
            return "fleeing";
        }
        return switch (order.type()) {
            case WALK -> "walking";
            case PUNCH -> "punching";
            case PUNCH_AT -> "feinting";
            case GIFT -> "gifting";
            case GREET -> "greeting";
            case STEAL -> "stealing";
            case USE -> "using item";
            case PLACE -> "placing block";
            default -> null;
        };
    }
}
