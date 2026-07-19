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
    void aBankInsideTheTrackSpanIsPenalisedAsATrap() {
        // THE REGRESSION THIS GUARDS: the span originally scored ZERO, making it the scorer's
        // optimum. But onTracks() is a pure Z-span test, so a mob that climbs out there reads as
        // "standing on the tracks", gets handed to tickGetOffTracks, and — where the line crosses
        // water — is walked straight back into the lake, swims to the same bank, and loops forever.
        // An off-span bank must win even when it is substantially farther away.
        double onSpan = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 1.0, 0.0);    // 1 block away, on the line
        double offSpan = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 20.0, 4.0);  // 20 blocks away, off it
        assertTrue(offSpan < onSpan,
            "a distant off-span bank must beat a close on-span one (" + offSpan + " < " + onSpan + ")");
    }

    @Test
    void theOnSpanPenaltyIsFiniteSoATrappedMobStillHasSomewhereToGo() {
        // A weight, not a ban: if the ONLY dry land is inside the span, it must still be selectable
        // (finite cost) rather than leaving the mob to drown for want of an alternative.
        assertTrue(Double.isFinite(TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 1.0, 0.0)));
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
    void nearerBankOffTrackLosesToFartherBankBesideTheRails() {
        // Mob afloat on the line at the origin. Candidate A is nearer (6 blocks) but way out in the
        // wilderness; candidate B is farther (8 blocks) but just beside the rails. B must win —
        // that's the whole point of the bias.
        //
        // Note B sits just OUTSIDE the carriage Z-span (2.0 vs maxZ 1.5), not on it: a bank inside
        // the span is the onTracks() trap and is penalised, not rewarded. "Beside the rails" means
        // alongside them, not between them.
        double offTrack = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 0.0, 6.0);
        double besideRails = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 8.0, 2.0);
        assertTrue(besideRails < offTrack,
            "a track-side bank at 8 must beat a wilderness bank at 6 (" + besideRails + " < " + offTrack + ")");
    }

    @Test
    void theBiasIsAWeightNotAFilter() {
        // With NO track-side bank in range, the nearest dry land still wins outright — an off-track
        // candidate is never rejected, only penalised, so a mob in a lake with banks only on one
        // far side still has somewhere to go.
        double near = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 0.0, 6.0);
        double far = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 0.0, 9.0);
        assertTrue(near < far, "between two equally off-track banks the nearer one wins");
    }

    @Test
    void theDistanceTermIsPlainHorizontalSwim() {
        // Two candidates at the SAME Z share an identical penalty, so the difference between their
        // scores is exactly the difference in swim distance. Pins the distance half of the formula
        // so a future bias tweak can't silently change what "distance" means.
        double atSeven = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 7.0, 5.0);
        double atThree = TrainRecoveryGoal.shoreCost(CARRIAGE, 0.0, 0.0, 3.0, 5.0);
        assertEquals(Math.hypot(7.0, 5.0) - Math.hypot(3.0, 5.0), atSeven - atThree, 1.0e-9);
    }
}
