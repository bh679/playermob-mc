package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.GiftPolicy.GiftTier;
import static games.brennan.playermob.entity.GiftPolicy.tierFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure-logic oracle for {@link GiftPolicy#tierFor} — the gift-quality tier from a
 * mob's feeling toward the recipient and its friendliness. Primitives + the
 * {@link GiftTier} enum only, so no Minecraft bootstrap is needed.
 */
class GiftPolicyTest {

    @Test
    void trinketBelowTheStapleFeeling() {
        // Loved (the gift path only fires at feeling >= 7) but not yet warm enough for food.
        assertEquals(GiftTier.TRINKET, tierFor(7.0F, 10));
        assertEquals(GiftTier.TRINKET, tierFor(7.9F, 10));
        assertEquals(GiftTier.TRINKET, tierFor(0.0F, 0));
    }

    @Test
    void stapleInTheWarmBand() {
        assertEquals(GiftTier.STAPLE, tierFor(8.0F, 0));    // feeling gate met, low friendliness
        assertEquals(GiftTier.STAPLE, tierFor(8.9F, 10));   // feeling just below the gear gate
        assertEquals(GiftTier.STAPLE, tierFor(9.0F, 7));    // feeling ok but friendliness one short
        assertEquals(GiftTier.STAPLE, tierFor(10.0F, 7));
    }

    @Test
    void gearNeedsBothThresholds() {
        assertEquals(GiftTier.GEAR, tierFor(9.0F, 8));      // exactly both gates
        assertEquals(GiftTier.GEAR, tierFor(10.0F, 10));
        assertEquals(GiftTier.STAPLE, tierFor(10.0F, 7));   // friendliness one short → not gear
        assertEquals(GiftTier.STAPLE, tierFor(8.9F, 10));   // feeling one short → not gear
    }

    @Test
    void tierForIsTotalAndRespectsTheGates() {
        float[] feelings = {0f, 5f, 7f, 7.9f, 8f, 8.9f, 9f, 10f};
        for (float feeling : feelings) {
            for (int fr = 0; fr <= 10; fr++) {
                GiftTier tier = tierFor(feeling, fr);
                assertNotNull(tier);
                if (feeling < GiftPolicy.STAPLE_FEELING) {
                    assertEquals(GiftTier.TRINKET, tier, "feeling " + feeling + " must be a trinket");
                }
                if (feeling >= GiftPolicy.GEAR_FEELING && fr >= GiftPolicy.GEAR_FRIENDLINESS) {
                    assertEquals(GiftTier.GEAR, tier, "feeling " + feeling + " fr " + fr + " must be gear");
                }
            }
        }
    }
}
