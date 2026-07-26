package games.brennan.playermob.entity.goal;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-geometry tests for {@link TrainRecoveryGoal#shoreCost} and
 * {@link TrainRecoveryGoal#distToTrackLine} — the scoring that decides which dry bank a swimming
 * PlayerMob makes for after falling off the train into water. The mob is chasing a MOVING train, so
 * the score deliberately trades swim distance against how far the bank sits from the track line: a
 * slightly farther bank beside the rails is worth more, because the APPROACH phase can start
 * working from it the moment the mob lands. Independent of any Minecraft world (the carriage is
 * just an AABB), so it's driven directly with coordinates; the live column scan is covered by the
 * in-game Gate 2 test instead.
 *
 * <p>The train runs along world-X, so the carriage box's Z-span IS the track line and the gap to it
 * is a pure Z term. The box below spans Z −1.5..1.5, i.e. the line is centred on Z=0.</p>
 */
class ShoreCostTest {

    /** A carriage sitting on the line: 3 wide in Z, centred on Z=0. */
    private static final AABB CARRIAGE = new AABB(0.0, 64.0, -1.5, 10.0, 67.0, 1.5);

    @Test
    void allElseEqualAnOffSpanBankBeatsAnOnSpanOne() {
        // Climbing out inside the carriage Z-span lands the mob on the track bed, and
        // tickGetOffTracks then has to sidestep it off before it can do anything — so at equal
        // distance, prefer the bank just beside the bed.
        double onSpan = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 6.0, 0.0, 0.0);
        double offSpan = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 6.0, 2.0, 0.0);
        assertTrue(offSpan < onSpan, "same swim, off-span wins (" + offSpan + " < " + onSpan + ")");
    }

    @Test
    void theOnSpanPenaltyIsANudgeNotAVeto() {
        // THE REGRESSION THIS GUARDS, and it is the opposite of what the first version guarded
        // against. The penalty was 64 — larger than the entire scan radius — so ANY off-span
        // candidate beat every on-span one regardless of distance, and the mob visibly swam past
        // the obvious nearby shore to reach a far one. A close bank must still win comfortably.
        double closeOnSpan = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 2.0, 0.0, 0.0);
        double farOffSpan = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 11.0, 4.5, 0.0);
        assertTrue(closeOnSpan < farOffSpan,
            "a bank 2 blocks away must beat one 11 blocks away even on-span ("
                + closeOnSpan + " < " + farOffSpan + ")");
    }

    @Test
    void nearestWinsBetweenTwoBanksEquallyPlacedRelativeToTheTrack() {
        // The headline requirement: closest shore. With the track term equal, distance decides.
        double near = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 3.0, 3.0, 0.0);
        double far = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 9.0, 3.0, 0.0);
        assertTrue(near < far, "nearest shore wins when both sit the same distance off the track");
    }

    @Test
    void insideTheTrackSpanCostsNoPenalty() {
        assertEquals(0.0, TrainRecoveryGoal.distToTrackLine(CARRIAGE, 0.0), 1.0e-9, "centre of the line");
        assertEquals(0.0, TrainRecoveryGoal.distToTrackLine(CARRIAGE, 1.5), 1.0e-9, "on the +Z face");
        assertEquals(0.0, TrainRecoveryGoal.distToTrackLine(CARRIAGE, -1.5), 1.0e-9, "on the −Z face");
    }

    @Test
    void penaltyIsSymmetricAboutTheTrackSpan() {
        // A bank on the far side of the line scores exactly like one the same distance out on the
        // near side — the mob will happily swim under/past the line to reach the better bank.
        assertEquals(
            TrainRecoveryGoal.distToTrackLine(CARRIAGE, 5.5),
            TrainRecoveryGoal.distToTrackLine(CARRIAGE, -5.5),
            1.0e-9, "equal gaps either side of the line score equally");
        assertEquals(4.0, TrainRecoveryGoal.distToTrackLine(CARRIAGE, 5.5), 1.0e-9, "5.5 − maxZ 1.5");
    }

    @Test
    void aClearlyNearerBankWinsEvenIfItIsFurtherFromTheTrack() {
        // Distance dominates. This DELIBERATELY inverts what an earlier version of this test
        // asserted (that a track-side bank at 8 should beat a wilderness bank at 6) — that
        // co-equal weighting is what made the mob swim past obvious nearby shores, which is the
        // behaviour being fixed. Getting out of the water is the urgent part; being near the rails
        // is a convenience for the phase that follows.
        double nearer = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 0.0, 6.0, 0.0);   // 6 away, 4.5 off track
        double besideRails = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 8.0, 2.0, 0.0); // 8 away, 0.5 off
        assertTrue(nearer < besideRails,
            "the nearer bank must win (" + nearer + " < " + besideRails + ")");
    }

    @Test
    void theWorstCaseThatPromptedThis_crossingALake() {
        // The geometry where the old weighting failed hardest: the line CROSSES water, so the
        // nearest bank is perpendicular to the rails (big gap) while the lake runs long in the
        // track direction (gap ~0). Equal weighting paid the mob to swim 15 blocks along the track
        // rather than 5 to the obvious bank beside it.
        double obviousBank = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 0.0, 5.0, 0.0);   // 5 away, perpendicular
        double farAlongTrack = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 15.0, 2.5, 0.0); // 15 away, along the rails
        assertTrue(obviousBank < farAlongTrack,
            "the near perpendicular bank must beat the distant one along the rails ("
                + obviousBank + " < " + farAlongTrack + ")");
    }

    @Test
    void theBiasIsAWeightNotAFilter() {
        // With NO track-side bank in range, the nearest dry land still wins outright — an off-track
        // candidate is never rejected, only penalised, so a mob in a lake with banks only on one
        // far side still has somewhere to go.
        double near = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 0.0, 6.0, 0.0);
        double far = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 0.0, 9.0, 0.0);
        assertTrue(near < far, "between two equally off-track banks the nearer one wins");
    }

    @Test
    void aLowBankBeatsATallerOneEvenIfTheTallOneIsCloser() {
        // "Sometimes it runs for a shoreline that is too tall." Climbing out onto a 1-block step is
        // trivial; hauling up a 4-block cliff face is slow and often impossible, so height above the
        // waterline is a real cost — worth a couple of blocks of extra swimming per block of rise.
        double tallAndClose = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 4.0, 4.0, 4.0);
        double lowAndFarther = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 7.0, 4.0, 1.0);
        assertTrue(lowAndFarther < tallAndClose,
            "a low bank a bit further out must beat a cliff (" + lowAndFarther + " < " + tallAndClose + ")");
    }

    @Test
    void riseOnlyCountsUpwards() {
        // A bank at or below the waterline carries no rise penalty — and a negative rise must never
        // become a discount that outbids a genuinely better candidate.
        assertEquals(
            TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 5.0, 4.0, 0.0),
            TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 5.0, 4.0, -3.0),
            1.0e-9, "below-waterline rise is clamped to zero, not rewarded");
    }

    @Test
    void theDistanceTermIsPlainHorizontalSwim() {
        // Two candidates at the SAME Z share an identical penalty, so the difference between their
        // scores is exactly the difference in swim distance. Pins the distance half of the formula
        // so a future bias tweak can't silently change what "distance" means.
        double atSeven = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 7.0, 5.0, 0.0);
        double atThree = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 3.0, 5.0, 0.0);
        assertEquals(Math.hypot(7.0, 5.0) - Math.hypot(3.0, 5.0), atSeven - atThree, 1.0e-9);
    }
}
