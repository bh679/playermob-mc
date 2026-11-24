package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link MeleeSpacing}: skill sets how near max reach a PlayerMob holds, the
 * band never puts the mob outside its own swing reach, and reach is measured eye to hitbox.
 */
class MeleeSpacingTest {

    @Test
    void holdFractionRunsFromNoviceToExpert() {
        assertEquals(MeleeSpacing.MIN_HOLD_FRACTION, MeleeSpacing.holdFraction(0), 1e-9);
        assertEquals(MeleeSpacing.MAX_HOLD_FRACTION, MeleeSpacing.holdFraction(10), 1e-9);
        assertEquals(0.65, MeleeSpacing.holdFraction(5), 1e-9);
    }

    @Test
    void holdFractionClampsOutOfRangeSkill() {
        assertEquals(MeleeSpacing.holdFraction(0), MeleeSpacing.holdFraction(-3), 1e-9);
        assertEquals(MeleeSpacing.holdFraction(10), MeleeSpacing.holdFraction(42), 1e-9);
    }

    @Test
    void higherSkillHoldsFurtherOut() {
        for (int skill = 1; skill <= 10; skill++) {
            assertTrue(MeleeSpacing.holdDistance(skill) > MeleeSpacing.holdDistance(skill - 1),
                "skill " + skill + " should hold further than skill " + (skill - 1));
        }
    }

    @Test
    void everySkillAdvancesBeforeLeavingSwingReach() {
        for (int skill = 0; skill <= 10; skill++) {
            double advance = MeleeSpacing.advanceBeyond(skill);
            assertTrue(MeleeSpacing.inSwingReach(advance), "skill " + skill + " holds out of its own reach");
            assertTrue(advance < MeleeSpacing.PLAYER_REACH);
            assertTrue(MeleeSpacing.retreatWithin(skill) > 0.0, "skill " + skill + " backs off from contact only");
        }
    }

    @Test
    void moveFollowsTheBand() {
        int skill = 10;
        double hold = MeleeSpacing.holdDistance(skill);
        assertEquals(MeleeSpacing.Move.HOLD, MeleeSpacing.move(skill, hold));
        assertEquals(MeleeSpacing.Move.ADVANCE, MeleeSpacing.move(skill, MeleeSpacing.PLAYER_REACH));
        assertEquals(MeleeSpacing.Move.ADVANCE, MeleeSpacing.move(skill, 8.0));
        assertEquals(MeleeSpacing.Move.RETREAT, MeleeSpacing.move(skill, 0.5));
    }

    @Test
    void noviceCrowdsInWhereExpertBacksOff() {
        double close = 1.4;
        assertEquals(MeleeSpacing.Move.HOLD, MeleeSpacing.move(0, close));
        assertEquals(MeleeSpacing.Move.RETREAT, MeleeSpacing.move(10, close));
    }

    @Test
    void swingReachIsAPlayersReach() {
        assertTrue(MeleeSpacing.inSwingReach(3.0));
        assertFalse(MeleeSpacing.inSwingReach(3.01));
    }

    @Test
    void eyeToBoxMeasuresToTheNearestFace() {
        // Box 1 wide at x 2..3, eye level inside its height: straight-line gap to the near face.
        assertEquals(2.0, MeleeSpacing.eyeToBox(0, 1.5, 0.5, 2, 0, 0, 3, 1.8, 1), 1e-9);
        // Eye inside the box.
        assertEquals(0.0, MeleeSpacing.eyeToBox(2.5, 1, 0.5, 2, 0, 0, 3, 1.8, 1), 1e-9);
        // Diagonal to a corner: 3-4-5.
        assertEquals(5.0, MeleeSpacing.eyeToBox(-3, 1, -4, 0, 0, 0, 1, 1.8, 1), 1e-9);
    }
    // ---- hit-and-run ------------------------------------------------------

    @Test
    void spacingRunsFromNoneToFull() {
        assertEquals(0.0, MeleeSpacing.spacing(0), 1e-9);
        assertEquals(0.5, MeleeSpacing.spacing(5), 1e-9);
        assertEquals(1.0, MeleeSpacing.spacing(10), 1e-9);
        assertEquals(1.0, MeleeSpacing.spacing(99), 1e-9);
    }

    @Test
    void skillZeroBehavesAsBeforeInBothPhases() {
        for (double dist = 0.0; dist <= 6.0; dist += 0.05) {
            MeleeSpacing.Move old = MeleeSpacing.move(0, dist);
            assertEquals(old, MeleeSpacing.move(0, dist, true, MeleeSpacing.PLAYER_REACH), "ready at " + dist);
            assertEquals(old, MeleeSpacing.move(0, dist, false, MeleeSpacing.PLAYER_REACH), "recharging at " + dist);
        }
    }

    @Test
    void expertBacksOutOfAPlayersReachWhileRecharging() {
        double keep = MeleeSpacing.rechargeDistance(10, MeleeSpacing.PLAYER_REACH);
        assertEquals(MeleeSpacing.PLAYER_REACH + MeleeSpacing.OUT_OF_REACH_MARGIN, keep, 1e-9);
        assertTrue(keep - MeleeSpacing.BAND > MeleeSpacing.PLAYER_REACH, "the band's near edge is still out of reach");
        double strike = MeleeSpacing.holdDistance(10);
        assertEquals(MeleeSpacing.Move.RETREAT, MeleeSpacing.move(10, strike, false, MeleeSpacing.PLAYER_REACH));
        assertEquals(MeleeSpacing.Move.HOLD, MeleeSpacing.move(10, strike, true, MeleeSpacing.PLAYER_REACH));
        assertEquals(MeleeSpacing.Move.ADVANCE, MeleeSpacing.move(10, keep, true, MeleeSpacing.PLAYER_REACH));
    }

    @Test
    void rechargeDistanceGrowsWithSkillAndNeverUndercutsHold() {
        for (int skill = 0; skill <= 10; skill++) {
            double keep = MeleeSpacing.rechargeDistance(skill, MeleeSpacing.PLAYER_REACH);
            assertTrue(keep >= MeleeSpacing.holdDistance(skill));
            if (skill > 0) {
                assertTrue(keep > MeleeSpacing.rechargeDistance(skill - 1, MeleeSpacing.PLAYER_REACH));
            }
            // A short-reach mob's reach is inside the hold distance: nothing to back away from.
            assertEquals(MeleeSpacing.holdDistance(skill),
                MeleeSpacing.rechargeDistance(skill, 0.2), 1e-9);
        }
    }

    @Test
    void leadTicksCoversTheWalkWithinBounds() {
        assertEquals(MeleeSpacing.MIN_LEAD_TICKS, MeleeSpacing.leadTicks(0.0, 0.15));
        assertEquals(MeleeSpacing.MIN_LEAD_TICKS, MeleeSpacing.leadTicks(-1.0, 0.15));
        assertEquals(9, MeleeSpacing.leadTicks(1.0, 0.15));   // ceil(6.67) + 2
        assertEquals(MeleeSpacing.MAX_LEAD_TICKS, MeleeSpacing.leadTicks(10.0, 0.15));
        assertEquals(MeleeSpacing.MAX_LEAD_TICKS, MeleeSpacing.leadTicks(1.0, 0.0));
    }
}
