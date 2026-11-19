package games.brennan.playermob.player;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingTallyTest {

    private static ShoppingTally trades(int count, int spentEach, int earnedEach) {
        ShoppingTally t = ShoppingTally.EMPTY;
        for (int i = 0; i < count; i++) {
            t = t.withTrade(spentEach, earnedEach);
        }
        return t;
    }

    @Test
    void noTradesScoresZero() {
        assertEquals(0, ShoppingTally.EMPTY.score());
        assertTrue(ShoppingTally.EMPTY.isEmpty());
    }

    @Test
    void oneSmallTradeScoresOne() {
        assertEquals(1, trades(1, 1, 0).score());
    }

    @Test
    void eightTradesMovingThirtyTwoEmeraldsScoresFive() {
        assertEquals(5, trades(8, 0, 4).score());
    }

    @Test
    void fiftyTradesMovingTwoHundredEmeraldsScoresNine() {
        assertEquals(9, trades(50, 2, 2).score());
    }

    @Test
    void scoreNeverPassesTen() {
        assertEquals(10, trades(5000, 64, 64).score());
    }

    @Test
    void scoreRisesWithMoreTrading() {
        int last = -1;
        for (int n = 0; n <= 100; n += 10) {
            int score = trades(n, 1, 1).score();
            assertTrue(score >= last, "score fell at " + n);
            last = score;
        }
    }

    @Test
    void withTradeReturnsACopy() {
        ShoppingTally before = trades(2, 1, 0);
        ShoppingTally after = before.withTrade(3, 5);
        assertEquals(2, before.trades());
        assertEquals(3, after.trades());
        assertEquals(5, after.emeraldsEarned());
        assertEquals(5, after.emeraldsSpent());
    }

    @Test
    void negativeAmountsCountAsZero() {
        ShoppingTally t = ShoppingTally.EMPTY.withTrade(-4, -2);
        assertEquals(1, t.trades());
        assertEquals(0, t.emeraldsSpent());
        assertEquals(0, t.emeraldsEarned());
    }

    @Test
    void nbtRoundTrips() {
        ShoppingTally t = trades(7, 3, 2);
        ShoppingTally back = ShoppingTally.load(t.save());
        assertEquals(t.trades(), back.trades());
        assertEquals(t.emeraldsSpent(), back.emeraldsSpent());
        assertEquals(t.emeraldsEarned(), back.emeraldsEarned());
        assertEquals(t.score(), back.score());
    }

    @Test
    void missingTagLoadsEmpty() {
        assertTrue(ShoppingTally.load(new CompoundTag()).isEmpty());
    }
}
