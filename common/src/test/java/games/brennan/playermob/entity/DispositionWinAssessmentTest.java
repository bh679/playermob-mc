package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.DispositionResolver.applyWinAssessment;
import static games.brennan.playermob.entity.DispositionResolver.canWin;
import static games.brennan.playermob.entity.DispositionResolver.combatPower;
import static games.brennan.playermob.entity.DispositionResolver.onHurt;
import static games.brennan.playermob.entity.DispositionResolver.requiredRatio;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for the Phase C "can I win?" assessment added to
 * {@link DispositionResolver}. Primitives + our own enums only, so no Minecraft
 * bootstrap is needed. Kept separate from {@code DispositionResolverTest} so that
 * Phase A oracle stays byte-for-byte untouched.
 */
class DispositionWinAssessmentTest {

    private static final double EPS = 1e-6;

    // ---- required ratio + canWin ----

    @Test
    void requiredRatioEasesAroundThePivot() {
        assertEquals(1.1, requiredRatio(4), EPS); // timid end of the band needs an edge
        assertEquals(1.0, requiredRatio(5), EPS); // pivot: parity
        assertEquals(0.9, requiredRatio(6), EPS); // braver end fights while slightly outmatched
    }

    @Test
    void canWinIsInclusiveAtTheBar() {
        assertTrue(canWin(5, 10.0, 10.0));        // parity is a win at ff5
        assertFalse(canWin(5, 9.999, 10.0));      // a hair under is a loss
        assertTrue(canWin(6, 9.0, 10.0));         // ff6 wins at 0.9x
        assertFalse(canWin(4, 10.0, 10.0));       // ff4 needs 1.1x
        assertTrue(canWin(4, 11.0, 10.0));
    }

    // ---- combat power ----

    @Test
    void combatPowerWeightsWeaponAboveArmor() {
        assertEquals(26.0, combatPower(20f, 4.0, 0.0), EPS);  // 20 + 4*1.5 + 0
        // +1 weapon score (×1.5) beats +1 armour score (×1.0).
        assertTrue(combatPower(20f, 5.0, 4.0) > combatPower(20f, 4.0, 5.0));
        // More health is strictly more power.
        assertTrue(combatPower(21f, 4.0, 4.0) > combatPower(20f, 4.0, 4.0));
    }

    // ---- applyWinAssessment ----

    @Test
    void applyWinAssessmentOnlyTouchesMidBandFightFlee() {
        // Below the band: passes through untouched (resolve already hard-cut it).
        assertEquals(Reaction.FLEE, applyWinAssessment(Reaction.FLEE, 3, 100, 1));
        // Above the band: untouched.
        assertEquals(Reaction.FIGHT, applyWinAssessment(Reaction.FIGHT, 7, 1, 100));
        // In the band: flips by power.
        assertEquals(Reaction.FLEE, applyWinAssessment(Reaction.FIGHT, 5, 1, 100));  // outmatched → flee
        assertEquals(Reaction.FIGHT, applyWinAssessment(Reaction.FLEE, 5, 100, 1));  // dominant → fight
    }

    @Test
    void applyWinAssessmentPassesNonCombatReactionsThrough() {
        assertEquals(Reaction.GREET, applyWinAssessment(Reaction.GREET, 5, 1, 100));
        assertEquals(Reaction.WATCH, applyWinAssessment(Reaction.WATCH, 5, 1, 100));
        assertEquals(Reaction.IGNORE, applyWinAssessment(Reaction.IGNORE, 5, 1, 100));
    }

    // ---- onHurt(3-arg) ----

    @Test
    void onHurt3ArgDelegatesToHardCutOutsideTheBand() {
        int[] outside = {0, 1, 2, 3, 7, 8, 9, 10};
        for (int ff : outside) {
            assertEquals(onHurt(ff), onHurt(ff, 1.0, 999.0), "ff " + ff + " must ignore power");
            assertEquals(onHurt(ff), onHurt(ff, 999.0, 1.0), "ff " + ff + " must ignore power");
        }
    }

    @Test
    void onHurt3ArgAssessesInsideTheBand() {
        assertEquals(DispositionResolver.HurtResponse.RETALIATE, onHurt(5, 100, 1));
        assertEquals(DispositionResolver.HurtResponse.FLEE, onHurt(5, 1, 100));
    }

    // ---- totality ----

    @Test
    void assessmentIsTotalAndWellFormed() {
        double[][] pairs = {{1, 1}, {1, 100}, {100, 1}, {10, 10}};
        for (int ff = 0; ff <= 10; ff++) {
            for (double[] p : pairs) {
                assertNotNull(onHurt(ff, p[0], p[1]), "onHurt must never return null");
                for (Reaction base : Reaction.values()) {
                    Reaction r = applyWinAssessment(base, ff, p[0], p[1]);
                    assertNotNull(r, "applyWinAssessment must never return null");
                    // It either returns the input or flips to FIGHT/FLEE — never invents another reaction.
                    assertTrue(r == base || r == Reaction.FIGHT || r == Reaction.FLEE,
                        "unexpected " + r + " from base " + base);
                }
            }
        }
    }
}
