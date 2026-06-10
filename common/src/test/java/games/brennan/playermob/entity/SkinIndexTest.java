package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Compile-time + classpath checks for the skin system. We can't instantiate
 * {@link PlayerMobEntity} in a pure JUnit context (no bootstrapped registries),
 * so behavioural coverage of skin rolling + clamping lives in the in-game
 * Gate 2 smoke test. What we CAN verify cheaply here:
 *
 * <ol>
 *   <li>{@link PlayerMobEntity#SKIN_COUNT} matches the number of vanilla
 *       default player skins (9 in MC 1.19.3+).</li>
 *   <li>Each referenced vanilla skin texture ships in the merged minecraft
 *       client jar — in both the {@code wide/} and {@code slim/} folders, since
 *       the renderer picks the folder per-mob from the SkinSlim flag.</li>
 *   <li>The constant lives on a server-loadable class (not the
 *       {@code @Environment(CLIENT)} renderer) — otherwise this test would
 *       fail to compile.</li>
 * </ol>
 *
 * <p>By pointing at vanilla skins (Steve / Alex / Ari / Efe / Kai / Makena /
 * Noor / Sunny / Zuri) the mod ships zero skin bytes itself; if Mojang ever
 * removes one of these textures in a future MC version, this test catches it.</p>
 */
class SkinIndexTest {

    /** Default-skin set added by Mojang in 1.19.3 (8 newer skins + the original Steve). */
    private static final int EXPECTED_VANILLA_SKIN_COUNT = 9;

    /** Must mirror the order in {@code PlayerMobRenderer.SKIN_NAMES}. */
    private static final String[] EXPECTED_SKIN_NAMES = {
        "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"
    };

    @Test
    void skinCountConstantMatchesVanillaDefaultCount() {
        assertEquals(EXPECTED_VANILLA_SKIN_COUNT, PlayerMobEntity.SKIN_COUNT,
            "PlayerMobEntity.SKIN_COUNT must equal the vanilla default-skin count");
    }

    @Test
    void everyExpectedVanillaSkinTextureIsOnClasspath() {
        // The merged minecraft client jar is on the common-module test
        // classpath via loom, so getResource("/assets/minecraft/...") resolves.
        // The renderer references BOTH arm-model folders — each bundled mob picks
        // slim/ or wide/ from its SkinSlim flag — so both must ship for every name.
        for (String folder : new String[] {"wide", "slim"}) {
            for (String name : EXPECTED_SKIN_NAMES) {
                String path = "/assets/minecraft/textures/entity/player/"
                    + folder + "/" + name + ".png";
                URL url = getClass().getResource(path);
                assertNotNull(url, "Missing vanilla skin texture on classpath: " + path);
            }
        }
    }
}
