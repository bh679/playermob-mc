package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.Reaction;
import games.brennan.playermob.entity.ReactionChannel;
import games.brennan.playermob.entity.ShoppingPolicy;
import games.brennan.playermob.entity.VillagerTrading;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
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

    private enum Phase { WALKING, VISITING }

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
        if (merchant == null || !calm() || !peaceful(merchant) || !VillagerTrading.canTradeWith(merchant)) {
            return false;
        }
        return phase == Phase.WALKING ? phaseTicks <= PATH_TIMEOUT_TICKS : phaseTicks < visitTicks;
    }

    @Override
    public void start() {
        phase = Phase.WALKING;
        phaseTicks = 0;
        trades = 0;
        nothingLeft = false;
        lastKind = null;
        mob.getNavigation().moveTo(merchant, speed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.setTrading(false);
        merchant = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        mob.getLookControl().setLookAt(merchant, 30.0F, 30.0F);
        if (phase == Phase.WALKING) {
            tickWalking();
        } else {
            tickVisiting();
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
        if (mob.distanceToSqr(merchant) > TRADE_REACH_SQR * 2) {
            mob.getNavigation().moveTo(merchant, speed); // it shuffled off — step after it
        } else {
            mob.getNavigation().stop();
        }
        if (nothingLeft || trades >= ShoppingPolicy.MAX_TRADES_PER_TRIP || --beatTicks > 0) {
            return; // browsing out the rest of the visit
        }
        VillagerTrading.Result result =
            VillagerTrading.tradeOnce(mob, merchant, ShoppingPolicy.buysGear(mob.shopping()));
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
