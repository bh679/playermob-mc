package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.IdleInventoryPolicy.MAX_DURATION_TICKS;
import static games.brennan.playermob.entity.IdleInventoryPolicy.MIN_DURATION_TICKS;
import static games.brennan.playermob.entity.IdleInventoryPolicy.NEVER;
import static games.brennan.playermob.entity.IdleInventoryPolicy.durationTicks;
import static games.brennan.playermob.entity.IdleInventoryPolicy.gapTicks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link IdleInventoryPolicy} — the gap between idle bag-checks and how
 * long each lasts. Primitives only, so no Minecraft bootstrap is needed.
 */
class IdleInventoryPolicyTest {

    @Test
    void gapSpansHalfToOneAndAHalfTimesTheInterval() {
        // 45 s interval = 900 ticks.
        assertEquals(450, gapTicks(45, 0.0));
        assertEquals(900, gapTicks(45, 0.5)); // the mean is the configured interval
        assertEquals(1350, gapTicks(45, 1.0));
    }

    @Test
    void zeroOrNegativeIntervalDisables() {
        assertEquals(NEVER, gapTicks(0, 0.5));
        assertEquals(NEVER, gapTicks(-10, 0.5));
    }

    @Test
    void gapClampsAnOutOfRangeRoll() {
        assertEquals(gapTicks(45, 0.0), gapTicks(45, -3.0));
        assertEquals(gapTicks(45, 1.0), gapTicks(45, 7.0));
    }

    @Test
    void durationStaysInsideItsWindow() {
        assertEquals(MIN_DURATION_TICKS, durationTicks(0.0));
        assertEquals(MAX_DURATION_TICKS, durationTicks(1.0));
        for (double roll = 0.0; roll <= 1.0; roll += 0.05) {
            int ticks = durationTicks(roll);
            assertTrue(ticks >= MIN_DURATION_TICKS && ticks <= MAX_DURATION_TICKS, "roll " + roll);
        }
    }
}
