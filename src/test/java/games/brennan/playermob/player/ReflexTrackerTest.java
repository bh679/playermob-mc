package games.brennan.playermob.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic oracle for {@link ReflexTracker} — the tick-timed rules behind each sample. */
class ReflexTrackerTest {

    private ReflexTally tally = ReflexTally.EMPTY;
    private final ReflexTracker tracker = new ReflexTracker(update -> tally = update.apply(tally));
    private final UUID zombie = UUID.randomUUID();

    @Test
    void strikeBackMeasuresLatencyFromTheFirstUnansweredHit() {
        tracker.onStruckBy(zombie, 100);
        tracker.onStruckBy(zombie, 105);       // a second hit doesn't restart the clock
        tracker.onHitMob(zombie, true, 110);
        assertEquals(1, tally.strikeSamples());
        assertEquals(ReflexTally.strikeBackScore(10), tally.strikeScore(), 1e-6);
    }

    @Test
    void anAnswerAfterTheWindowIsNoSample() {
        tracker.onStruckBy(zombie, 0);
        tracker.onHitMob(zombie, true, ReflexTracker.STRIKE_WINDOW_TICKS + 1);
        assertEquals(0, tally.strikeSamples());
    }

    @Test
    void expiryDropsAnUnansweredHit() {
        tracker.onStruckBy(zombie, 0);
        tracker.expire(ReflexTracker.STRIKE_WINDOW_TICKS + 1);
        tracker.onHitMob(zombie, true, ReflexTracker.STRIKE_WINDOW_TICKS + 2);
        assertEquals(0, tally.strikeSamples());
    }

    @Test
    void aFirstHitThenTheKillIsClean() {
        tracker.onHitMob(zombie, true, 10);
        tracker.onMobDied(zombie, true);
        assertEquals(1, tally.cleanFirstHits());
        assertEquals(0, tally.firstHits());
    }

    @Test
    void beingHitBackSpoilsTheCleanFirstHit() {
        tracker.onHitMob(zombie, true, 10);
        tracker.onStruckBy(zombie, 20);
        tracker.onMobDied(zombie, true);
        assertEquals(0, tally.cleanFirstHits());
        assertEquals(1, tally.firstHits());
    }

    @Test
    void someoneElsesKillSettlesAsAPlainFirstHit() {
        tracker.onHitMob(zombie, true, 10);
        tracker.onMobDied(zombie, false);
        assertEquals(1, tally.firstHits());
        assertEquals(0, tally.cleanFirstHits());
    }

    @Test
    void hittingAnIdleMobFirstIsNotAReflex() {
        tracker.onHitMob(zombie, false, 10);
        tracker.onMobDied(zombie, true);
        assertTrue(tally.isEmpty());
    }

    @Test
    void notFirstIfItHurtYouRecently() {
        tracker.onStruckBy(zombie, 0);
        tracker.expire(ReflexTracker.STRIKE_WINDOW_TICKS + 1);    // strike-back window gone
        tracker.onHitMob(zombie, true, ReflexTracker.STRIKE_WINDOW_TICKS + 10);
        tracker.onMobDied(zombie, true);
        assertEquals(0, tally.cleanFirstHits());
        assertEquals(0, tally.firstHits());
    }

    @Test
    void anUnresolvedFirstHitExpiresAsPlain() {
        tracker.onHitMob(zombie, true, 0);
        tracker.expire(ReflexTracker.FIRST_HIT_EXPIRE_TICKS + 1);
        assertEquals(1, tally.firstHits());
        assertFalse(tracker.tracks(zombie));
    }

    @Test
    void aBridgeRunCountsFromTheFourthBlock() {
        for (int i = 0; i < 3; i++) tracker.onBlockPlaced(i, 64, 0, false, false, i * 5);
        assertEquals(0, tally.bridgeBlocks());
        tracker.onBlockPlaced(3, 64, 0, false, false, 15);
        assertEquals(4, tally.bridgeBlocks());
        tracker.onBlockPlaced(4, 64, 0, false, false, 20);
        assertEquals(5, tally.bridgeBlocks());
    }

    @Test
    void aSlowOrCrouchedBridgeIsNoRun() {
        for (int i = 0; i < 6; i++) tracker.onBlockPlaced(i, 64, 0, false, false, i * 20);
        for (int i = 0; i < 6; i++) tracker.onBlockPlaced(100 + i, 64, 0, false, true, 200 + i * 4);
        assertEquals(0, tally.bridgeBlocks());
    }

    @Test
    void aBridgeMustBeAdjacentAtOneHeight() {
        for (int i = 0; i < 6; i++) tracker.onBlockPlaced(i * 2, 64, 0, false, false, i * 4);
        assertEquals(0, tally.bridgeBlocks());
    }

    @Test
    void aJumpPillarCountsFromTheThirdBlock() {
        tracker.onBlockPlaced(0, 64, 0, false, false, 0);
        tracker.onBlockPlaced(0, 65, 0, true, false, 10);
        assertEquals(0, tally.jumpBlocks());
        tracker.onBlockPlaced(0, 66, 0, true, false, 20);
        assertEquals(3, tally.jumpBlocks());
    }

    @Test
    void aPillarMustBeStraightUpAndAirborne() {
        tracker.onBlockPlaced(0, 64, 0, true, false, 0);
        tracker.onBlockPlaced(0, 65, 0, false, false, 10);   // standing, not jumping
        tracker.onBlockPlaced(1, 66, 0, true, false, 20);    // not straight up
        assertEquals(0, tally.jumpBlocks());
    }

    @Test
    void idleSecondsAreNotActiveTime() {
        tracker.onAction(0);
        tracker.onAction(20);          // 1 s later: active
        tracker.onAction(20 * 100);    // 99 s idle: counts as 1 s, not 99
        tracker.flush();
        assertEquals(3, tally.actions());
        assertEquals(3.0F, tally.activeSeconds(), 1e-6);
    }

    @Test
    void paceCountsNewCarriagesOnly() {
        tracker.onPaceSample(0, false);
        tracker.onPaceSample(1, false);
        tracker.onPaceSample(2, false);
        tracker.onPaceSample(1, false);   // walking back is not progress
        tracker.onPaceSample(2, false);
        tracker.flush();
        assertEquals(2.0F, tally.paceCarriages(), 1e-6);
        assertEquals(4.0F, tally.paceSeconds(), 1e-6);
    }

    @Test
    void elytraProgressIsAbsorbed() {
        tracker.onPaceSample(0, false);
        tracker.onPaceSample(10, true);    // glided ten carriages
        tracker.onPaceSample(11, false);
        tracker.flush();
        assertEquals(1.0F, tally.paceCarriages(), 1e-6);
        assertEquals(1.0F, tally.paceSeconds(), 1e-6);
    }

    @Test
    void aLongStretchOffTheTrainIsAbsorbedAShortHopIsNot() {
        tracker.onPaceSample(0, false);
        tracker.onPaceSample(ReflexTracker.NO_CARRIAGE, false);   // jumping a gap
        tracker.onPaceSample(1, false);
        for (int i = 0; i <= ReflexTracker.OFF_TRAIN_ABSORB_SAMPLES; i++) {
            tracker.onPaceSample(ReflexTracker.NO_CARRIAGE, false);
        }
        tracker.onPaceSample(20, false);   // re-boarded far ahead
        tracker.flush();
        assertEquals(1.0F, tally.paceCarriages(), 1e-6);
    }

    @Test
    void dodgeJudgement() {
        assertTrue(ReflexTracker.isDodge(1.0, false));
        assertFalse(ReflexTracker.isDodge(1.0, true));
        assertFalse(ReflexTracker.isDodge(5.0, false));
        assertFalse(ReflexTracker.isThreat(5.0, false), "a wild miss is not judged at all");
        assertTrue(ReflexTracker.isThreat(5.0, true));
    }
}
