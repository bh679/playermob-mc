package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.ReactionChannel;
import games.brennan.playermob.entity.ShoppingPolicy;
import games.brennan.playermob.entity.VillagerTrading;
import net.minecraft.world.entity.ai.goal.Goal;
//? if >=26 {
/*import net.minecraft.world.entity.npc.villager.AbstractVillager;
*///?} else {
import net.minecraft.world.entity.npc.AbstractVillager;
//?}
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * The <b>Shopping</b> errand: walk to a nearby villager or wandering trader and trade with it
 * through its real offers ({@link VillagerTrading}) — sell junk from the backpack for emeralds and,
 * at {@link ShoppingPolicy#BUY_MIN_SCORE}+, buy armour, weapons and tools that beat what the mob
 * has, then equip them.
 *
 * <p>How often is the mob's {@code Shopping} score: a trip starts with chance {@code score / 10}
 * once its cooldown runs out, and the cooldown shrinks as the score rises
 * ({@link ShoppingPolicy#cooldownTicks}). Score 0 never shops. The pace between trades follows the
 * mob's {@link ReactionChannel#ITEMS} reaction speed.</p>
 *
 * <p>Never while hostile or fighting: it won't start with a target, while aggressive or just hurt,
 * and stands down the moment any of those turn up. Registered at priority 3 so greeting,
 * following, defending and every combat goal outrank it.</p>
 */
public final class ShopWithVillagerGoal extends Goal implements DescribableGoal {

    private static final double SCAN_RADIUS = 16.0;
    private static final double TRADE_REACH_SQR = 3.0 * 3.0;
    private static final int PATH_TIMEOUT_TICKS = 200; // 10s to reach the villager
    private static final int REPATH_TICKS = 20;

    private enum Phase { WALKING, TRADING }

    private final PlayerMobEntity mob;
    private final double speed;

    private int cooldown = -1; // -1 = not yet rolled (first canUse rolls a staggered start)
    private AbstractVillager merchant;
    private Phase phase = Phase.WALKING;
    private int phaseTicks;
    private int beatTicks;
    private int trades;
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
        if (cooldown < 0) {
            cooldown = ShoppingPolicy.cooldownTicks(score, mob.getRandom().nextDouble());
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (mob.getRandom().nextDouble() >= ShoppingPolicy.tripChance(score)) {
            cooldown = ShoppingPolicy.cooldownTicks(score, mob.getRandom().nextDouble());
            return false;
        }
        merchant = findMerchant(ShoppingPolicy.buysGear(score));
        if (merchant == null) {
            cooldown = ShoppingPolicy.RETRY_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return merchant != null && calm() && VillagerTrading.canTradeWith(merchant)
            && trades < ShoppingPolicy.MAX_TRADES_PER_TRIP
            && !(phase == Phase.WALKING && phaseTicks > PATH_TIMEOUT_TICKS);
    }

    @Override
    public void start() {
        phase = Phase.WALKING;
        phaseTicks = 0;
        trades = 0;
        lastKind = null;
        mob.getNavigation().moveTo(merchant, speed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        merchant = null;
        cooldown = ShoppingPolicy.cooldownTicks(mob.shopping(), mob.getRandom().nextDouble());
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
            if (mob.distanceToSqr(merchant) <= TRADE_REACH_SQR) {
                mob.getNavigation().stop();
                phase = Phase.TRADING;
                phaseTicks = 0;
                beatTicks = nextBeat();
            } else if (phaseTicks % REPATH_TICKS == 0) {
                mob.getNavigation().moveTo(merchant, speed);
            }
            return;
        }
        if (mob.distanceToSqr(merchant) > TRADE_REACH_SQR * 2) {
            phase = Phase.WALKING; // it wandered off — follow it
            phaseTicks = 0;
            return;
        }
        if (--beatTicks > 0) return;
        VillagerTrading.Result result =
            VillagerTrading.tradeOnce(mob, merchant, ShoppingPolicy.buysGear(mob.shopping()));
        if (result == null) {
            trades = ShoppingPolicy.MAX_TRADES_PER_TRIP; // nothing left to do here
            return;
        }
        lastKind = result.kind();
        if (result.kind() == VillagerTrading.Kind.BUY) {
            mob.acceptBoughtGear(result.item());
        }
        mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
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

    /** Nearest merchant within {@link #SCAN_RADIUS} that has a deal for this mob right now. */
    private AbstractVillager findMerchant(boolean buyGear) {
        AABB box = mob.getBoundingBox().inflate(SCAN_RADIUS);
        List<AbstractVillager> nearby = mob.level().getEntitiesOfClass(AbstractVillager.class, box,
            v -> VillagerTrading.hasDeal(mob, v, buyGear));
        AbstractVillager best = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractVillager v : nearby) {
            double d = mob.distanceToSqr(v);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }
}
