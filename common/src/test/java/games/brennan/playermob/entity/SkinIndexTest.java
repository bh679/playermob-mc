package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compile-time + classpath checks for the bundled-skin system. We can't
 * instantiate {@link PlayerMobEntity} in a pure JUnit context (no bootstrapped
 * registries), so behavioural coverage of skin rolling + clamping lives in
 * the in-game Gate 2 smoke test. What we CAN verify cheaply here:
 *
 * <ol>
 *   <li>{@link PlayerMobEntity#SKIN_COUNT} matches the number of bundled PNGs.</li>
 *   <li>Each expected skin PNG actually ships in the resource pack.</li>
 *   <li>The constant lives on a server-loadable class (not the
 *       {@code @Environment(CLIENT)} renderer) — otherwise this test would
 *       fail to compile.</li>
 * </ol>
 */
class SkinIndexTest {

    /** Update this constant in lockstep with PlayerMobEntity.SKIN_COUNT + PNG file count. */
    private static final int EXPECTED_SKIN_COUNT = 8;

    @Test
    void skinCountConstantMatchesBundledFiles() {
        // If this fails, either the constant or the PNGs are out of sync —
        // bump the constant and add/remove PNGs accordingly.
        assertEquals(EXPECTED_SKIN_COUNT, PlayerMobEntity.SKIN_COUNT,
            "PlayerMobEntity.SKIN_COUNT must match the number of bundled skin files");
    }

    @Test
    void everyExpectedSkinPngIsOnClasspath() {
        // Resource path inside the common/src/main/resources tree.
        for (int i = 0; i < EXPECTED_SKIN_COUNT; i++) {
            String path = "/assets/playermob/textures/entity/skins/skin_" + i + ".png";
            URL url = getClass().getResource(path);
            assertNotNull(url, "Missing bundled skin file: " + path);
        }
    }

    @Test
    void noUnexpectedExtraSkinPngExists() {
        // Catch the case where someone added skin_8.png but forgot to bump
        // SKIN_COUNT — the new skin would never be rolled.
        URL extra = getClass().getResource("/assets/playermob/textures/entity/skins/skin_"
            + EXPECTED_SKIN_COUNT + ".png");
        assertTrue(extra == null,
            "Found skin_" + EXPECTED_SKIN_COUNT + ".png but SKIN_COUNT is " + EXPECTED_SKIN_COUNT
            + " — bump the constant or remove the file");
    }
}
