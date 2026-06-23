package games.brennan.playermob.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link PlayerMobSkin#CODEC}. Verifies the JSON
 * shape datapacks ship matches what the reload listener can parse.
 */
class PlayerMobSkinCodecTest {

    private static final String NOTCH_JSON = """
        {
          "displayName": "Notch",
          "textureUrl": "http://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680",
          "model": "wide"
        }
        """;

    private static final String SLIM_JSON = """
        {
          "displayName": "Grian",
          "textureUrl": "http://textures.minecraft.net/texture/fd82a31be355fae3842af8a66a208df602230a0fc748c7453e491e08377eb4c2",
          "model": "slim"
        }
        """;

    private static final String NO_MODEL_JSON = """
        {
          "displayName": "DefaultModelGuy",
          "textureUrl": "http://textures.minecraft.net/texture/aaaa"
        }
        """;

    @Test
    void parsesWideSkin() {
        PlayerMobSkin skin = parseOrThrow(NOTCH_JSON);
        assertEquals("Notch", skin.displayName());
        assertEquals("http://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680", skin.textureUrl());
        assertEquals(SkinModel.WIDE, skin.model());
    }

    @Test
    void parsesSlimSkin() {
        PlayerMobSkin skin = parseOrThrow(SLIM_JSON);
        assertEquals(SkinModel.SLIM, skin.model());
    }

    @Test
    void modelDefaultsToWideWhenAbsent() {
        // Datapack authors ought to be able to omit "model" — wide is the
        // most common case (matches vanilla's Steve model).
        PlayerMobSkin skin = parseOrThrow(NO_MODEL_JSON);
        assertEquals(SkinModel.WIDE, skin.model(),
            "Missing 'model' field should default to WIDE");
    }

    @Test
    void roundTripsThroughCodec() {
        PlayerMobSkin original = parseOrThrow(NOTCH_JSON);
        JsonElement encoded = PlayerMobSkin.CODEC.encodeStart(JsonOps.INSTANCE, original)
            .result().orElseThrow();
        assertNotNull(encoded);
        PlayerMobSkin decoded = PlayerMobSkin.CODEC.parse(JsonOps.INSTANCE, encoded)
            .result().orElseThrow();
        assertEquals(original, decoded, "Codec round-trip should be lossless");
    }

    @Test
    void rejectsBlankDisplayName() {
        // The record's canonical constructor throws — this surfaces as a
        // codec parse failure (DataResult.error).
        String badJson = """
            { "displayName": "", "textureUrl": "http://x/y" }
            """;
        var result = PlayerMobSkin.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(badJson));
        assertTrue(result.error().isPresent(),
            "Blank displayName should be rejected by the codec");
    }

    @Test
    void rejectsBlankTextureUrl() {
        // Constructor invariant — same path as the blank name test above.
        assertThrows(IllegalArgumentException.class, () ->
            new PlayerMobSkin("Name", "", SkinModel.WIDE));
    }

    private static PlayerMobSkin parseOrThrow(String json) {
        return PlayerMobSkin.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
            .result()
            .orElseThrow(() -> new AssertionError("Failed to parse: " + json));
    }
}
