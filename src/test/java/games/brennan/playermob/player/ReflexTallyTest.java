package games.brennan.playermob.player;

import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic oracle for {@link ReflexTally} — the reaction-speed score of one life. */
class ReflexTallyTest {

    private static final double EPS = 1e-6;

    private static ReflexTally strikes(int n, int latency) {
        ReflexTally t = ReflexTally.EMPTY;
        for (int i = 0; i < n; i++) t = t.withStrikeBack(latency);
        return t;
    }

    @Test
    void noEvidenceScoresExactlyNeutral() {
        assertTrue(ReflexTally.EMPTY.isEmpty());
        assertEquals(DispositionTraits.DEFAULT, ReflexTally.EMPTY.reactionSpeed());
        assertEquals(5.0, ReflexTally.EMPTY.rawScore(), EPS);
    }

    @Test
    void strikeBackCurveAnchors() {
        assertEquals(10.0, ReflexTally.strikeBackScore(0), EPS);
        assertEquals(10.0, ReflexTally.strikeBackScore(6), EPS);
        assertEquals(5.0, ReflexTally.strikeBackScore(14), EPS);
        assertEquals(0.0, ReflexTally.strikeBackScore(30), EPS);
        assertEquals(0.0, ReflexTally.strikeBackScore(99), EPS);
        assertEquals(7.5, ReflexTally.strikeBackScore(10), EPS);
    }

    @Test
    void aFewSamplesStayNearNeutralThePriorHolds() {
        // 3 instant strike-backs: (5*5 + 3*10) / 8 = 6.875.
        assertEquals(6.875, strikes(3, 0).rawScore(), EPS);
        assertEquals(7, strikes(3, 0).reactionSpeed());
    }

    @Test
    void fastStrikersScoreHighSlowOnesLow() {
        assertEquals(9, strikes(20, 0).reactionSpeed());
        assertEquals(1, strikes(20, 40).reactionSpeed());
        assertEquals(5, strikes(20, 14).reactionSpeed());
    }

    @Test
    void aRateNotAVolumeLongLivesDoNotDrift() {
        // The same average reflex over 10x the fights scores (almost) the same.
        assertEquals(strikes(200, 14).reactionSpeed(), strikes(20, 14).reactionSpeed());
    }

    @Test
    void aCleanFirstHitOutweighsAPlainOne() {
        double plain = ReflexTally.EMPTY.withFirstHit(false).rawScore();
        double clean = ReflexTally.EMPTY.withFirstHit(true).rawScore();
        assertTrue(clean > plain);
        assertTrue(plain > 5.0, "any first hit beats no evidence");
        assertEquals(1, ReflexTally.EMPTY.withFirstHit(true).cleanFirstHits());
        assertEquals(0, ReflexTally.EMPTY.withFirstHit(true).firstHits());
    }

    @Test
    void firstHitsCountMoreThanAStrikeBack() {
        double strike = ReflexTally.EMPTY.withStrikeBack(0).rawScore();   // score 10, weight 1
        double first = ReflexTally.EMPTY.withFirstHit(false).rawScore();  // score 8, weight 2
        double clean = ReflexTally.EMPTY.withFirstHit(true).rawScore();   // score 10, weight 3
        assertTrue(clean > strike);
        assertTrue(clean > first);
    }

    @Test
    void dodgeScoreIsFiveAtThirtyPercentAndCapsAtTen() {
        ReflexTally t = ReflexTally.EMPTY;
        for (int i = 0; i < 3; i++) t = t.withProjectile(true);
        for (int i = 0; i < 7; i++) t = t.withProjectile(false);
        assertEquals(5.0, t.dodgeScore(), EPS);
        assertEquals(10.0, ReflexTally.EMPTY.withProjectile(true).dodgeScore(), EPS);
    }

    @Test
    void actionRateAnchors() {
        assertEquals(5.0, ReflexTally.EMPTY.withActions(120, 60).actionScore(), EPS);
        assertEquals(10.0, ReflexTally.EMPTY.withActions(300, 60).actionScore(), EPS);
        assertEquals(0.0, ReflexTally.EMPTY.withActions(10, 60).actionScore(), EPS);
    }

    @Test
    void paceAnchors() {
        assertEquals(5.0, ReflexTally.EMPTY.withPace(2, 60).paceScore(), EPS);
        assertEquals(10.0, ReflexTally.EMPTY.withPace(6, 60).paceScore(), EPS);
        assertEquals(0.0, ReflexTally.EMPTY.withPace(0, 60).paceScore(), EPS);
    }

    @Test
    void aLongStandStillOnTheTrainPullsDown() {
        assertTrue(ReflexTally.EMPTY.withPace(0, 600).reactionSpeed() < 5);
    }

    @Test
    void moveBonusCapsAtOneAndAHalf() {
        assertEquals(5.3, ReflexTally.EMPTY.withBridgeBlocks(3).rawScore(), EPS);
        ReflexTally lots = ReflexTally.EMPTY.withBridgeBlocks(100).withJumpBlocks(100);
        assertEquals(6.5, lots.rawScore(), EPS);
    }

    @Test
    void clampedToTheTraitRange() {
        ReflexTally best = strikes(500, 0).withBridgeBlocks(100);
        assertEquals(DispositionTraits.MAX, best.reactionSpeed());
        assertEquals(DispositionTraits.MIN, strikes(500, 99).withActions(0, 6000).reactionSpeed());
    }

    @Test
    void creditsAreImmutable() {
        ReflexTally a = ReflexTally.EMPTY;
        ReflexTally b = a.withStrikeBack(3);
        assertTrue(a.isEmpty());
        assertFalse(b.isEmpty());
    }

    @Test
    void nbtRoundTrip() {
        ReflexTally t = strikes(4, 9).withFirstHit(true).withFirstHit(false).withProjectile(true)
            .withProjectile(false).withActions(50, 30).withPace(3, 90).withBridgeBlocks(5)
            .withJumpBlocks(4);
        ReflexTally back = ReflexTally.load(t.save());
        assertEquals(t.breakdown(), back.breakdown());
        assertEquals(t.reactionSpeed(), back.reactionSpeed());
    }

    @Test
    void anEmptyTagLoadsAsEmpty() {
        assertTrue(ReflexTally.load(new CompoundTag()).isEmpty());
    }
}
