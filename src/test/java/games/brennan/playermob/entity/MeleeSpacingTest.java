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
}
