package games.brennan.playermob.player;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure decode oracle for {@link ProfileSkins} — exercises the base64 {@code textures}
 * JSON parse without a GameProfile or world. Mirrors the shape Mojang signs and the
 * shape {@code PlayerMobSkinTextures} synthesises.
 */
class ProfileSkinsTest {

    private static String b64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodesWideSkinUrl() {
        String value = b64("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\"}}}");
        Optional<ProfileSkins.Skin> skin = ProfileSkins.decode(value);
        assertTrue(skin.isPresent());
        assertEquals("http://textures.minecraft.net/texture/abc123", skin.get().url());
        assertFalse(skin.get().slim());
    }

    @Test
    void decodesSlimModelMetadata() {
        String value = b64("{\"textures\":{\"SKIN\":{\"url\":\"http://x/y\",\"metadata\":{\"model\":\"slim\"}}}}");
        Optional<ProfileSkins.Skin> skin = ProfileSkins.decode(value);
        assertTrue(skin.isPresent());
        assertTrue(skin.get().slim());
    }

    @Test
    void emptyOrNullValueYieldsEmpty() {
        assertTrue(ProfileSkins.decode("").isEmpty());
        assertTrue(ProfileSkins.decode(null).isEmpty());
    }

    @Test
    void garbageYieldsEmptyNotThrow() {
        assertTrue(ProfileSkins.decode("!!!not base64!!!").isEmpty());
        assertTrue(ProfileSkins.decode(b64("not json")).isEmpty());
        assertTrue(ProfileSkins.decode(b64("{\"textures\":{}}")).isEmpty());        // no SKIN
        assertTrue(ProfileSkins.decode(b64("{\"textures\":{\"SKIN\":{}}}")).isEmpty()); // no url
    }
}
