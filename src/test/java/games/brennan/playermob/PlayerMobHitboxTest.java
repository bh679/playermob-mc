package games.brennan.playermob;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins PlayerMob's hitbox to a vanilla player's. A taller box (it was 1.95) wedges a mob standing
 * on carpet in front of a 2-high doorway that a player walks straight through.
 */
class PlayerMobHitboxTest {

    /** Collision height of a carpet (and moss carpet): one sixteenth of a block. */
    private static final double CARPET_HEIGHT = 1.0 / 16.0;

    @Test
    void matchesVanillaPlayerSize() {
        assertEquals(0.6F, PlayerMobRegistry.HITBOX_WIDTH);
        assertEquals(1.8F, PlayerMobRegistry.HITBOX_HEIGHT);
        assertEquals(1.62F, PlayerMobRegistry.EYE_HEIGHT);
    }

    @Test
    void standingOnCarpetFitsUnderATwoHighGap() {
        assertTrue(CARPET_HEIGHT + PlayerMobRegistry.HITBOX_HEIGHT < 2.0,
            "a PlayerMob on carpet must clear a 2-block-high doorway");
    }

    @Test
    void eyesSitInsideTheHitbox() {
        assertTrue(PlayerMobRegistry.EYE_HEIGHT < PlayerMobRegistry.HITBOX_HEIGHT);
    }
}
