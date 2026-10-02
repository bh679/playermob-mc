package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic oracle for {@link LipHopPolicy} — when a blocked carriage-riding mob hops. */
class LipHopPolicyTest {

    private static final int STALLED = LipHopPolicy.STALL_TICKS;

    @Test
    void hopsWhenBlockedWithTheWayOpen() {
        assertTrue(LipHopPolicy.shouldHop(true, true, STALLED, 0, false));
    }

    @Test
    void waitsOutABriefHesitation() {
        assertFalse(LipHopPolicy.shouldHop(true, true, STALLED - 1, 0, false));
    }

    @Test
    void aClosedDoorInTheWayIsTheDoorReflexesJob() {
        assertFalse(LipHopPolicy.shouldHop(true, true, STALLED * 10, 0, true));
    }

    @Test
    void doesNotHopWhenStandingStillOnPurpose() {
        assertFalse(LipHopPolicy.shouldHop(false, true, STALLED * 10, 0, false));
    }

    @Test
    void needsFootingToJumpFrom() {
        assertFalse(LipHopPolicy.shouldHop(true, false, STALLED, 0, false));
    }

    @Test
    void doesNotBounceOnTheSpot() {
        assertFalse(LipHopPolicy.shouldHop(true, true, STALLED, 1, false));
    }

    @Test
    void givesUpAfterAFewFruitlessHops() {
        assertTrue(LipHopPolicy.mayStillTry(0));
        assertTrue(LipHopPolicy.mayStillTry(LipHopPolicy.MAX_FRUITLESS_HOPS - 1));
        assertFalse(LipHopPolicy.mayStillTry(LipHopPolicy.MAX_FRUITLESS_HOPS));
    }

    @Test
    void movingABlockOnCountsAsGettingSomewhere() {
        assertFalse(LipHopPolicy.gotSomewhere(0.5, 0.0));
        assertTrue(LipHopPolicy.gotSomewhere(1.0, 0.0));
        assertTrue(LipHopPolicy.gotSomewhere(0.8, 0.8));
    }

    @Test
    void headwayThreshold() {
        assertTrue(LipHopPolicy.stalled(0.0, 0.0));
        assertTrue(LipHopPolicy.stalled(0.01, 0.01));
        assertFalse(LipHopPolicy.stalled(0.2, 0.0));   // walking pace is ~0.2 blocks a tick
        assertFalse(LipHopPolicy.stalled(0.0, -0.05));
    }
}
