package games.brennan.playermob.skin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Oracle for {@link SkinDisplayName} — the skin-source → nameplate resolution used to auto-label
 * naturally-spawned PlayerMobs. The local-ref branch is fully pure; the online branch seeds the
 * registry directly (no server / datapack needed).
 */
class SkinDisplayNameTest {

    private static final String URL = "http://textures.minecraft.net/texture/abc123";

    @Test
    void blankOrNullHasNoName() {
        assertEquals(Optional.empty(), SkinDisplayName.resolve(null));
        assertEquals(Optional.empty(), SkinDisplayName.resolve(""));
        assertEquals(Optional.empty(), SkinDisplayName.resolve("   "));
    }

    @Test
    void localRefResolvesToFilename() {
        String ref = LocalSkinRef.encode("Notch");
        assertEquals(Optional.of("Notch"), SkinDisplayName.resolve(ref));
    }

    @Test
    void onlineUrlResolvesToDisplayName() {
        PlayerMobSkinRegistry.replaceAll(List.of(new PlayerMobSkin("Streamer_Bob", URL, SkinModel.WIDE)));
        try {
            assertEquals(Optional.of("Streamer_Bob"), SkinDisplayName.resolve(URL));
        } finally {
            PlayerMobSkinRegistry.replaceAll(List.of());
        }
    }

    @Test
    void unknownUrlHasNoName() {
        PlayerMobSkinRegistry.replaceAll(List.of());
        assertEquals(Optional.empty(), SkinDisplayName.resolve("http://textures.minecraft.net/texture/unknown"));
    }
}
