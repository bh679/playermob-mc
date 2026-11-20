package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.GiftShopping;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import games.brennan.playermob.entity.ReactionChannel;
import games.brennan.playermob.entity.ShoppingPolicy;
import games.brennan.playermob.entity.VillagerTrading;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The <b>Shopping</b> errand, played the way the mob's player played: notice a trader (a villager
 * with a job, or a wandering trader), decide once whether to stop, and if so walk over and spend
 * about as long at it as the player used to spend in a trading screen — selling junk from the
 * backpack and, at {@link ShoppingPolicy#BUY_MIN_SCORE}+, buying armour, weapons and tools that beat
 * what it has, through the trader's real offers ({@link VillagerTrading}).
 *
 * <ul>
 *   <li><b>Noticing:</b> traders within {@link ShoppingPolicy#noticeRadius} — further the keener.</li>
 *   <li><b>Stopping:</b> one roll per trader at {@link ShoppingPolicy#stopChance} ({@code score / 10}),
 *       the share of traders its player traded with; stop or skip is remembered for
 *       {@link ShoppingPolicy#DECISION_MEMORY_TICKS} so a skipped trader stays skipped.</li>
 *   <li><b>Visiting:</b> stands at the trader for {@code ShoppingSeconds} (clamped by
 *       {@link ShoppingPolicy#visitTicks}), holding up its Trading panel; trades are paced by its
 *       {@link ReactionChannel#ITEMS} reaction speed. A visit with nothing to trade is still a visit —
 *       the player opened screens they bought nothing from too.</li>
 * </ul>
 *
 * <p><b>Gifts:</b> an echo that loves the player it came from ({@link PlayerMobEntity#belovedSourcePlayer})
 * and is keen enough to buy gear first buys that player one piece that beats what they own
 * ({@link GiftShopping}) — at most one a visit — then, once the visit is over, walks to them and
 * throws it ({@code tossGift}). If they wander off or it gets pulled into a fight, the gift stays in
 * its backpack.</p>
 *
 * <p>Never while hostile or fighting: it won't start with a target, while aggressive or just hurt,
 * or toward a trader it would fight or flee, and it stands down the moment any of those change.
 * Registered at priority 3, so greeting, following, defending and every combat goal outrank it.</p>
 */
public final class ShopWithVillagerGoal extends Goal implements DescribableGoal {

    private static final double TRADE_REACH_SQR = 3.0 * 3.0;
    private static final int PATH_TIMEOUT_TICKS = 200; // 10s to reach the trader
    private static final int REPATH_TICKS = 20;
    /** Goal checks between looks for traders it hasn't decided about yet (checks run every other tick). */
    private static final int NOTICE_EVERY_CHECKS = 5;

    /** Ticks to reach the loved player with a gift before giving up (it stays in the backpack). */
    private static final int DELIVER_TIMEOUT_TICKS = 300; // 15s
    private static final double TOSS_REACH_SQR = 3.0 * 3.0;

    private enum Phase { WALKING, VISITING, DELIVERING }

    private final PlayerMobEntity mob;
    private final double speed;
    /** Trader → game time its stop/skip decision expires. Transient; oldest first. */
    private final Map<UUID, Long> decided = new LinkedHashMap<>();

    private int checksUntilLook;
    private AbstractVillager merchant;
    private Phase phase = Phase.WALKING;
    private int phaseTicks;
    private int visitTicks;
    private int beatTicks;
    private int trades;
    private boolean nothingLeft;
    private VillagerTrading.Kind lastKind;
    /** The gift bought this visit, still to be thrown to {@link #recipient}; empty if none. */
    private ItemStack pendingGift = ItemStack.EMPTY;
    private ServerPlayer recipient;

    public ShopWithVillagerGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Shopping";
    }

    @Override
    public String subObjective() {
        if (phase == Phase.WALKING) return "heading to a trader";
        if (phase == Phase.DELIVERING) return "bringing a gift";
        if (lastKind == VillagerTrading.Kind.GIFT) return "buying a gift";
        if (lastKind == VillagerTrading.Kind.BUY) return "buying gear";
        return lastKind == VillagerTrading.Kind.SELL ? "selling junk" : "browsing";
    }

    @Override
    public boolean canUse() {
        int score = mob.shopping();
        if (!ShoppingPolicy.trades(score) || !calm()) return false;
        if (--checksUntilLook > 0) return false;
        checksUntilLook = NOTICE_EVERY_CHECKS;
        long now = mob.level().getGameTime();
        forgetExpired(now);
        merchant = decideOnNewTraders(score, now);
        return merchant != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!calm()) return false;
        if (phase == Phase.DELIVERING) {
            return !pendingGift.isEmpty() && recipient != null && recipient.isAlive()
                && recipient.level() == mob.level() && phaseTicks <= DELIVER_TIMEOUT_TICKS
                && mob.distanceToSqr(recipient) <= GiftShopping.GIFT_RANGE * GiftShopping.GIFT_RANGE * 2;
        }
        if (merchant == null || !peaceful(merchant) || !VillagerTrading.canTradeWith(merchant)) {
            return false;
        }
        if (phase == Phase.WALKING) return phaseTicks <= PATH_TIMEOUT_TICKS;
        return phaseTicks < visitTicks || !pendingGift.isEmpty(); // a bought gift still goes out
    }

    @Override
    public void start() {
        phase = Phase.WALKING;
        phaseTicks = 0;
        trades = 0;
        nothingLeft = false;
        lastKind = null;
        pendingGift = ItemStack.EMPTY;
        recipient = null;
        mob.getNavigation().moveTo(merchant, speed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.setTrading(false);
        merchant = null;
        // An undelivered gift simply stays in the backpack; a later greeting may still hand it over.
        pendingGift = ItemStack.EMPTY;
        recipient = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        switch (phase) {
            case WALKING -> { mob.getLookControl().setLookAt(merchant, 30.0F, 30.0F); tickWalking(); }
            case VISITING -> { mob.getLookControl().setLookAt(merchant, 30.0F, 30.0F); tickVisiting(); }
            case DELIVERING -> tickDelivering();
        }
    }

    private void tickWalking() {
        if (mob.distanceToSqr(merchant) <= TRADE_REACH_SQR) {
            mob.getNavigation().stop();
            phase = Phase.VISITING;
            phaseTicks = 0;
            visitTicks = ShoppingPolicy.visitTicks(mob.shoppingSeconds());
            beatTicks = nextBeat();
            mob.setTrading(true);
        } else if (phaseTicks % REPATH_TICKS == 0) {
            mob.getNavigation().moveTo(merchant, speed);
        }
    }

    private void tickVisiting() {
        if (phaseTicks >= visitTicks && !pendingGift.isEmpty()) {
            phase = Phase.DELIVERING;
            phaseTicks = 0;
            mob.setTrading(false);
            mob.getNavigation().moveTo(recipient, speed);
            return;
        }
        if (mob.distanceToSqr(merchant) > TRADE_REACH_SQR * 2) {
            mob.getNavigation().moveTo(merchant, speed); // it shuffled off — step after it
        } else {
            mob.getNavigation().stop();
        }
        if (nothingLeft || trades >= ShoppingPolicy.MAX_TRADES_PER_TRIP || --beatTicks > 0) {
            return; // browsing out the rest of the visit
        }
        boolean buysGear = ShoppingPolicy.buysGear(mob.shopping());
        if (buysGear && pendingGift.isEmpty() && tryBuyGift()) {
            trades++;
            beatTicks = nextBeat();
            return;
        }
        VillagerTrading.Result result = VillagerTrading.tradeOnce(mob, merchant, buysGear);
        if (result == null) {
            nothingLeft = true;
            return;
        }
        lastKind = result.kind();
        if (result.kind() == VillagerTrading.Kind.BUY) {
            mob.acceptBoughtGear(result.item());
        }
        mob.swing(InteractionHand.MAIN_HAND);
        trades++;
        beatTicks = nextBeat();
    }

    /**
     * If this echo loves the player it came from and they are nearby, buy them one piece of gear that
     * beats what they own — at most one gift a visit, before anything for itself.
     */
    private boolean tryBuyGift() {
        ServerPlayer beloved = mob.belovedSourcePlayer(GiftShopping.GIFT_RANGE);
        if (beloved == null) return false;
        VillagerTrading.Result gift = VillagerTrading.tradeGift(mob, merchant,
            GiftShopping.ownedBy(beloved, ItemStack.EMPTY));
        if (gift == null) return false;
        pendingGift = gift.item().copyWithCount(1);
        recipient = beloved;
        lastKind = gift.kind();
        mob.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Walk to the loved player and throw them the gift. */
    private void tickDelivering() {
        mob.getLookControl().setLookAt(recipient, 30.0F, 30.0F);
        if (mob.distanceToSqr(recipient) <= TOSS_REACH_SQR) {
            mob.getNavigation().stop();
            ItemStack gift = mob.takeOneMatching(pendingGift);
            if (!gift.isEmpty()) {
                mob.tossGift(recipient, gift);
                mob.swing(InteractionHand.MAIN_HAND);
            }
            pendingGift = ItemStack.EMPTY; // delivered (or no longer carried) — the goal ends
        } else if (phaseTicks % REPATH_TICKS == 1) {
            mob.getNavigation().moveTo(recipient, speed);
        }
    }

    private int nextBeat() {
        return Math.max(1, mob.reactTicks(ReactionChannel.ITEMS, ShoppingPolicy.TRADE_BEAT_TICKS));
    }

    /** Not hostile, not fighting, not just hurt. */
    private boolean calm() {
        return mob.getTarget() == null && !mob.isAggressive() && mob.hurtTime == 0;
    }

    /**
     * True when this mob's disposition toward {@code merchant} is neither to fight nor to flee it
     * ({@link games.brennan.playermob.entity.DispositionResolver}). Villagers resolve to greet or
     * ignore, but a merchant that has turned on the mob is reclassified hostile — never trade with that.
     */
    private boolean peaceful(AbstractVillager merchant) {
        Reaction reaction = mob.reactionToward(merchant);
        return reaction != Reaction.FIGHT && reaction != Reaction.FLEE;
    }

    /**
     * Roll for traders within the notice radius this mob hasn't decided about yet, nearest first,
     * and return the first it chooses to stop at (or {@code null}). Each roll — stop or skip — is
     * remembered, so walking past a trader it skipped doesn't roll again; traders beyond the one it
     * stops at stay undecided until a later look.
     */
    private AbstractVillager decideOnNewTraders(int score, long now) {
        AABB box = mob.getBoundingBox().inflate(ShoppingPolicy.noticeRadius(score));
        List<AbstractVillager> nearby = mob.level().getEntitiesOfClass(AbstractVillager.class, box,
            v -> !decided.containsKey(v.getUUID()) && VillagerTrading.isTrader(v));
        nearby.sort(java.util.Comparator.comparingDouble(mob::distanceToSqr));
        for (AbstractVillager v : nearby) {
            remember(v.getUUID(), now);
            if (mob.getRandom().nextDouble() < ShoppingPolicy.stopChance(score)
                    && VillagerTrading.canTradeWith(v) && peaceful(v)) {
                return v;
            }
        }
        return null;
    }

    private void remember(UUID id, long now) {
        decided.put(id, now + ShoppingPolicy.DECISION_MEMORY_TICKS);
        Iterator<UUID> oldest = decided.keySet().iterator();
        while (decided.size() > ShoppingPolicy.DECISION_MEMORY_CAP && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    private void forgetExpired(long now) {
        decided.values().removeIf(expiry -> expiry <= now);
    }
}
