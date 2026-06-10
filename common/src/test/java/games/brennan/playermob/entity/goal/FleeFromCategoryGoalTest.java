package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.goal.FleeFromCategoryGoal.BoundaryLeap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-logic tests for {@link FleeFromCategoryGoal#chooseAwayLeap}, the threat-relative pick of
 * which carriage-group gap a cornered fleeing mob leaps across on a Dungeon Train (issue #64).
 * Dungeon Train runs along world-X, so "away from the threat" reduces to maximising
 * {@code |landingX − threatX|} — and a leap that lands no farther than the mob already is (i.e.
 * toward the threat) is rejected. The live train geometry and the hop itself are covered by the
 * in-game Gate 2 smoke test.
 */
class FleeFromCategoryGoalTest {

    @Test
    void picksTheGapLeadingAwayFromTheThreat() {
        // Threat behind the mob (threat 80 < mob 100); the only gap lands at 130, farther away.
        BoundaryLeap away = new BoundaryLeap(+1, 130.0);
        assertEquals(away, FleeFromCategoryGoal.chooseAwayLeap(100.0, 80.0, List.of(away)));
    }

    @Test
    void rejectsAGapThatLeadsTowardTheThreat() {
        // Threat at 80, mob at 100 (dist 20). The only gap lands at 90 (dist 10) — nearer the
        // threat than the mob already is, so leaping there would close the gap, not escape it.
        BoundaryLeap toward = new BoundaryLeap(-1, 90.0);
        assertNull(FleeFromCategoryGoal.chooseAwayLeap(100.0, 80.0, List.of(toward)));
    }

    @Test
    void choosesTheFartherOfTwoValidLeaps() {
        // Perpendicular threat (same X as the mob): both boundary leaps lead away; the one whose
        // landing is farther from the threat on world-X wins.
        BoundaryLeap near = new BoundaryLeap(-1, 88.0);  // |88 - 100|  = 12
        BoundaryLeap far = new BoundaryLeap(+1, 130.0);  // |130 - 100| = 30
        assertEquals(far, FleeFromCategoryGoal.chooseAwayLeap(100.0, 100.0, List.of(near, far)));
    }

    @Test
    void perpendicularThreatAcceptsABoundaryLeap() {
        // threatX == mobX → |mobX − threatX| = 0, so any non-zero landing distance counts as away.
        BoundaryLeap leap = new BoundaryLeap(+1, 112.0);
        assertEquals(leap, FleeFromCategoryGoal.chooseAwayLeap(100.0, 100.0, List.of(leap)));
    }

    @Test
    void noCandidatesYieldsNull() {
        assertNull(FleeFromCategoryGoal.chooseAwayLeap(100.0, 80.0, List.of()));
    }
}
