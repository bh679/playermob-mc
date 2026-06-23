package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.GiftPolicy.GiftTier;
import static games.brennan.playermob.entity.GiftPolicy.cascadeFrom;
import static games.brennan.playermob.entity.GiftPolicy.tierFor;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure-logic oracle for {@link GiftPolicy} — the top gift rung from feeling +
 * friendliness, and the richest-first cascade from it. Primitives + the
 * {@link GiftTier} enum only, so no Minecraft bootstrap is needed.
 */
class GiftPolicyTest {

    @Test
    void surplusBelowTheSpareFeeling() {
        // Loved (the gift path only fires at feeling >= 7) but not warm enough for gear.
        assertEquals(GiftTier.SURPLUS, tierFor(7.0F, 10));
        assertEquals(GiftTier.SURPLUS, tierFor(7.9F, 10));
        assertEquals(GiftTier.SURPLUS, tierFor(0.0F, 0));
    }

    @Test
    void spareInTheWarmBand() {
        assertEquals(GiftTier.SPARE, tierFor(8.0F, 0));    // feeling gate met, low friendliness
        assertEquals(GiftTier.SPARE, tierFor(8.9F, 10));   // feeling just below the upgrade gate
        assertEquals(GiftTier.SPARE, tierFor(9.0F, 7));    // feeling ok but friendliness one short
        assertEquals(GiftTier.SPARE, tierFor(10.0F, 7));
    }

    @Test
    void upgradeNeedsBothThresholds() {
        assertEquals(GiftTier.UPGRADE, tierFor(9.0F, 8));   // exactly both gates
        assertEquals(GiftTier.UPGRADE, tierFor(10.0F, 10));
        assertEquals(GiftTier.SPARE, tierFor(10.0F, 7));    // friendliness one short → not upgrade
        assertEquals(GiftTier.SPARE, tierFor(8.9F, 10));    // feeling one short → not upgrade
    }

    @Test
    void cascadeStepsDownRichestFirst() {
        assertArrayEquals(new GiftTier[] { GiftTier.UPGRADE, GiftTier.SPARE, GiftTier.SURPLUS },
            cascadeFrom(GiftTier.UPGRADE));
        assertArrayEquals(new GiftTier[] { GiftTier.SPARE, GiftTier.SURPLUS },
            cascadeFrom(GiftTier.SPARE));
        assertArrayEquals(new GiftTier[] { GiftTier.SURPLUS },
            cascadeFrom(GiftTier.SURPLUS));
    }

    @Test
    void cascadeIsTotalStartsWithItsTierAndEndsAtSurplus() {
        for (GiftTier top : GiftTier.values()) {
            GiftTier[] rungs = cascadeFrom(top);
            assertNotNull(rungs);
            assertEquals(top, rungs[0], top + " cascade must start with itself");
            assertEquals(GiftTier.SURPLUS, rungs[rungs.length - 1], "cascade must end at SURPLUS");
        }
    }

    @Test
    void tierForIsTotalAndRespectsTheGates() {
        float[] feelings = {0f, 5f, 7f, 7.9f, 8f, 8.9f, 9f, 10f};
        for (float feeling : feelings) {
            for (int fr = 0; fr <= 10; fr++) {
                GiftTier tier = tierFor(feeling, fr);
                assertNotNull(tier);
                if (feeling < GiftPolicy.SPARE_FEELING) {
                    assertEquals(GiftTier.SURPLUS, tier, "feeling " + feeling + " must be surplus");
                }
                if (feeling >= GiftPolicy.UPGRADE_FEELING && fr >= GiftPolicy.UPGRADE_FRIENDLINESS) {
                    assertEquals(GiftTier.UPGRADE, tier, "feeling " + feeling + " fr " + fr + " must be upgrade");
                }
            }
        }
    }
}
