package games.brennan.playermob.player;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingTallyTest {

    /** {@code met} traders met at {@code minutes} into the life, the first {@code traded} of them traded with. */
    private static ShoppingTally life(int met, int traded, double minutes) {
        ShoppingTally t = ShoppingTally.EMPTY;
        for (int i = 0; i < met; i++) {
            UUID id = new UUID(0L, i);
            t = t.withMet(id, minutes);
            if (i < traded) {
                t = t.withTrade(id, minutes, 1, 1);
            }
        }
        return t;
    }

    @Test
    void noTradersMetScoresZero() {
        assertEquals(0, ShoppingTally.EMPTY.score());
        assertTrue(ShoppingTally.EMPTY.isEmpty());
    }

    @Test
    void walkingPastTradersScoresZero() {
        assertEquals(0, life(10, 0, 5).score());
    }

    @Test
    void oneTraderTradedWithLeansOnThePrior() {
        assertEquals(3, life(1, 1, 5).score()); // 10 * 1 / (1 + 3) = 2.5
    }

    @Test
    void halfTheTradersScoresFour() {
        assertEquals(4, life(10, 5, 5).score()); // 10 * 5 / 13
    }

    @Test
    void nearlyEveryTraderScoresHigh() {
        assertEquals(8, life(40, 36, 5).score());
    }

    @Test
    void skippingMostTradersPullsALongLifeDown() {
        assertEquals(1, life(40, 4, 5).score());
    }

    @Test
    void tradingAgainWithTheSameVillagerDoesNotRaiseTheScore() {
        UUID id = new UUID(0L, 1L);
        ShoppingTally once = ShoppingTally.EMPTY.withMet(id, 5).withTrade(id, 5, 1, 0);
        ShoppingTally many = once;
        for (int i = 0; i < 50; i++) {
            many = many.withTrade(id, 5, 1, 0);
        }
        assertEquals(once.score(), many.score());
        assertEquals(51, many.trades());
    }

    @Test
    void meetingTheSameTraderTwiceCountsOnce() {
        UUID id = new UUID(0L, 7L);
        ShoppingTally t = ShoppingTally.EMPTY.withMet(id, 5);
        assertSame(t, t.withMet(id, 6));
    }

    @Test
    void aTradeWithAnUnmetTraderMeetsIt() {
        ShoppingTally t = ShoppingTally.EMPTY.withTrade(new UUID(0L, 3L), 5, 2, 0);
        assertEquals(1, t.tradedCount());
        assertEquals(0.0, t.missedWeight(), 1e-9);
    }

    @Test
    void missWeightFallsAfterTheFirstHour() {
        assertEquals(1.0, ShoppingTally.missWeight(0), 1e-9);
        assertEquals(1.0, ShoppingTally.missWeight(60), 1e-9);
        assertEquals(0.625, ShoppingTally.missWeight(120), 1e-9);
        assertEquals(ShoppingTally.LATE_MISS_WEIGHT, ShoppingTally.missWeight(180), 1e-9);
        assertEquals(ShoppingTally.LATE_MISS_WEIGHT, ShoppingTally.missWeight(600), 1e-9);
    }

    @Test
    void lateMissesArePunishedLessThanEarlyOnes() {
        int early = life(20, 5, 10).score();
        int late = life(20, 5, 200).score();
        assertTrue(late > early, "late " + late + " vs early " + early);
        assertEquals(2, early);  // 50 / (5 + 15 + 3) = 2.2
        assertEquals(4, late);   // 50 / (5 + 3.75 + 3) = 4.3
    }

    @Test
    void oldTradersFoldIntoTheArchiveWithoutChangingTheScore() {
        int over = ShoppingTally.MAX_REMEMBERED + 40;
        ShoppingTally t = life(over, over / 2, 5);
        assertEquals(over / 2, t.tradedCount());
        assertEquals(over - over / 2, t.missedWeight(), 1e-3);
        ShoppingTally back = ShoppingTally.load(t.save());
        assertEquals(t.score(), back.score());
        assertEquals(t.tradedCount(), back.tradedCount());
    }

    @Test
    void nbtRoundTrips() {
        ShoppingTally t = life(12, 7, 90);
        ShoppingTally back = ShoppingTally.load(t.save());
        assertEquals(t.tradedCount(), back.tradedCount());
        assertEquals(t.missedWeight(), back.missedWeight(), 1e-6);
        assertEquals(t.trades(), back.trades());
        assertEquals(t.emeraldsSpent(), back.emeraldsSpent());
        assertEquals(t.score(), back.score());
    }

    @Test
    void averageVisitIsMeanScreenTime() {
        assertEquals(0.0, ShoppingTally.EMPTY.averageVisitSeconds(), 1e-9);
        ShoppingTally t = ShoppingTally.EMPTY.withVisit(200).withVisit(400); // 10 s and 20 s
        assertEquals(2, t.visits());
        assertEquals(15.0, t.averageVisitSeconds(), 1e-9);
        assertEquals(0, t.score()); // opening screens alone doesn't score
    }

    @Test
    void visitsSurviveTradesAndNbt() {
        UUID id = new UUID(0L, 9L);
        ShoppingTally t = ShoppingTally.EMPTY.withVisit(100).withMet(id, 5).withTrade(id, 5, 3, 0);
        assertEquals(1, t.visits());
        ShoppingTally back = ShoppingTally.load(t.save());
        assertEquals(t.averageVisitSeconds(), back.averageVisitSeconds(), 1e-9);
        assertEquals(1, back.visits());
    }

    @Test
    void missingTagLoadsEmpty() {
        assertTrue(ShoppingTally.load(new CompoundTag()).isEmpty());
    }
}
