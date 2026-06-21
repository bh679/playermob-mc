package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link FeelingRecord} — the per-individual feeling state
 * and the maths of every Phase B event. No Minecraft world (primitives + our own
 * record), so no registry bootstrap, like {@link DispositionResolverTest}.
 */
class FeelingRecordTest {

    private static final float EPS = 1e-4f;

    @Test
    void neutralIsDefaultAndZeroMagnitude() {
        assertTrue(FeelingRecord.NEUTRAL.isDefault());
        assertEquals(0.0, FeelingRecord.NEUTRAL.magnitude(), 1e-9);
        assertEquals(5.0f, FeelingRecord.NEUTRAL.feeling(), EPS);
        assertEquals(FeelingRecord.NO_CARRIAGE, FeelingRecord.NEUTRAL.lastCarriageIndex());
    }

    // ---- gifts ----

    @Test
    void giftDeltaFloorScaleAndCap() {
        assertEquals(0.5f, FeelingRecord.giftDelta(0, 0), EPS);   // non-gear / no upgrade → floor
        assertEquals(0.5f, FeelingRecord.giftDelta(5, 7), EPS);   // worse than current → floor
        assertEquals(1.0f, FeelingRecord.giftDelta(7, 5), EPS);   // +2 score → 0.5 + 0.5
        assertEquals(2.5f, FeelingRecord.giftDelta(8, 0), EPS);   // +8 score → 0.5 + 2.0
        assertEquals(3.0f, FeelingRecord.giftDelta(99, 0), EPS);  // capped at GIFT_MAX
    }

    // ---- crouch ----

    @Test
    void crouchAddsTenthUntilLifetimeCap() {
        FeelingRecord r = FeelingRecord.NEUTRAL;
        for (int i = 0; i < 100; i++) {
            r = r.afterCrouch();
        }
        assertEquals(7.0f, r.feeling(), 0.15f);                       // +2 over neutral
        assertTrue(r.crouchBudgetUsed() >= 2.0f && r.crouchBudgetUsed() < 2.2f);
        assertSame(r, r.afterCrouch());                              // capped → no-op
    }

    @Test
    void attackLossReopensCrouchHeadroomButChipDoesNot() {
        FeelingRecord chip = FeelingRecord.NEUTRAL.afterAttack(-0.5f);
        assertEquals(4.5f, chip.feeling(), EPS);
        assertEquals(FeelingRecord.CROUCH_CAP_BASE, chip.crouchCap(), EPS); // < 1 loss → no re-earn

        FeelingRecord blow = FeelingRecord.NEUTRAL.afterAttack(-2.0f);
        assertEquals(3.0f, blow.feeling(), EPS);
        assertEquals(3.0f, blow.crouchCap(), EPS);                          // ≥1 loss → +1 headroom
    }

    @Test
    void crouchCapHasACeiling() {
        FeelingRecord r = FeelingRecord.NEUTRAL;
        for (int i = 0; i < 50; i++) {
            r = r.afterAttack(-1.0f);
        }
        assertEquals(FeelingRecord.CROUCH_CAP_MAX, r.crouchCap(), EPS);
    }

    @Test
    void adjustedDoesNotTouchCrouchCap() {
        // The harm-a-loved-one event uses adjusted(−1); it must NOT reward the
        // aggressor with crouch headroom (that is attack-only).
        FeelingRecord r = FeelingRecord.NEUTRAL.adjusted(-1.0f);
        assertEquals(4.0f, r.feeling(), EPS);
        assertEquals(FeelingRecord.CROUCH_CAP_BASE, r.crouchCap(), EPS);
    }

    // ---- defend ----

    @Test
    void defendAddsOneUntilCapTwo() {
        FeelingRecord r = FeelingRecord.NEUTRAL.afterDefend();
        assertEquals(6.0f, r.feeling(), EPS);
        assertEquals(1, r.defendCount());
        r = r.afterDefend();
        assertEquals(7.0f, r.feeling(), EPS);
        assertEquals(2, r.defendCount());
        assertSame(r, r.afterDefend());                             // capped → no-op
    }

    // ---- trait-weighted scaling (friendliness via DispositionResolver.kindnessScale) ----

    @Test
    void scaledCrouchJumpsMorePerBowButKeepsTheCeiling() {
        // A max-friendly mob (scale 2.0) gains 0.2 per bow instead of 0.1 ...
        FeelingRecord one = FeelingRecord.NEUTRAL.afterCrouch(2.0f);
        assertEquals(5.2f, one.feeling(), EPS);
        assertEquals(0.2f, one.crouchBudgetUsed(), EPS);
        // ... but the +2 lifetime ceiling is unchanged — it just reaches it in fewer bows.
        FeelingRecord r = FeelingRecord.NEUTRAL;
        for (int i = 0; i < 100; i++) {
            r = r.afterCrouch(2.0f);
        }
        assertEquals(7.0f, r.feeling(), 0.25f);                      // still +2 over neutral
        assertTrue(r.crouchBudgetUsed() >= 2.0f && r.crouchBudgetUsed() < 2.4f);
        assertSame(r, r.afterCrouch(2.0f));                          // capped → no-op at any scale
    }

    @Test
    void scaledDefendBanksMorePerRescueWithinTheCountCap() {
        // The defend cap is on COUNT (2), so scaling the per-defence feeling genuinely raises
        // the total: a max-friendly mob banks +4 across its two rescues vs the neutral +2.
        FeelingRecord r = FeelingRecord.NEUTRAL.afterDefend(2.0f);
        assertEquals(7.0f, r.feeling(), EPS);
        assertEquals(1, r.defendCount());
        r = r.afterDefend(2.0f);
        assertEquals(9.0f, r.feeling(), EPS);
        assertEquals(2, r.defendCount());
        assertSame(r, r.afterDefend(2.0f));                         // count cap reached → no-op
    }

    @Test
    void unscaledOverloadsMatchNeutralScaleOfOne() {
        assertEquals(FeelingRecord.NEUTRAL.afterCrouch().feeling(),
            FeelingRecord.NEUTRAL.afterCrouch(1.0f).feeling(), EPS);
        assertEquals(FeelingRecord.NEUTRAL.afterDefend().feeling(),
            FeelingRecord.NEUTRAL.afterDefend(1.0f).feeling(), EPS);
    }

    // ---- travel ----

    @Test
    void carriageFirstSightRecordsThenCreditsPerChange() {
        FeelingRecord first = FeelingRecord.NEUTRAL.afterCarriageAdvance(3);
        assertEquals(5.0f, first.feeling(), EPS);                   // first sight: position only
        assertEquals(3, first.lastCarriageIndex());
        assertSame(first, first.afterCarriageAdvance(3));           // same carriage → no-op
        FeelingRecord moved = first.afterCarriageAdvance(2);
        assertEquals(5.2f, moved.feeling(), EPS);                   // changed carriage → +0.2
        assertEquals(2, moved.lastCarriageIndex());
    }

    // ---- clamp / magnitude ----

    @Test
    void clampHoldsFeelingInRange() {
        assertEquals(10.0f, FeelingRecord.NEUTRAL.withFeeling(99).feeling(), EPS);
        assertEquals(0.0f, FeelingRecord.NEUTRAL.withFeeling(-5).feeling(), EPS);
    }

    @Test
    void magnitudeCountsNonFeelingInvestment() {
        // Feeling-neutral, but two defences of history → still "interesting".
        FeelingRecord rich = FeelingRecord.NEUTRAL.afterDefend().afterDefend().adjusted(-2.0f);
        assertEquals(5.0f, rich.feeling(), EPS);
        assertFalse(rich.isDefault());
        assertTrue(rich.magnitude() >= 4.0);
    }
}
