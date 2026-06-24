package games.brennan.playermob.skin;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec tests for {@link PlayerMobSkinEntry} — the datapack JSON shape after {@code playerName}
 * support was added. Covers the legacy URL form, the new name form, both-present, and the
 * "neither" / blank rejections.
 */
class PlayerMobSkinEntryCodecTest {

    @Test
    void parsesLegacyUrlEntry() {
        PlayerMobSkinEntry entry = parse("""
            { "displayName": "Notch",
              "textureUrl": "http://textures.minecraft.net/texture/abc",
              "model": "wide" }
            """);
        assertTrue(entry.hasUrl());
        assertTrue(entry.playerName().isEmpty());
        PlayerMobSkin skin = entry.toUrlSkin();
        assertEquals("http://textures.minecraft.net/texture/abc", skin.textureUrl());
        assertEquals(SkinModel.WIDE, skin.model());
    }

    @Test
    void urlEntryDefaultsModelToWide() {
        // Preserves the original behaviour: a URL entry with no "model" is wide.
        PlayerMobSkinEntry entry = parse("""
            { "displayName": "X", "textureUrl": "http://x/y" }
            """);
        assertEquals(SkinModel.WIDE, entry.toUrlSkin().model());
    }

    @Test
    void parsesNameEntry() {
        PlayerMobSkinEntry entry = parse("""
            { "displayName": "The Notch", "playerName": "Notch" }
            """);
        assertFalse(entry.hasUrl());
        assertEquals("Notch", entry.playerName().orElseThrow());
        assertTrue(entry.model().isEmpty());
    }

    @Test
    void nameEntryUsesResolvedModelWhenUnset() {
        PlayerMobSkinEntry entry = parse("""
            { "displayName": "Grian", "playerName": "Grian" }
            """);
        // No explicit model → take whatever the resolver reported.
        assertEquals(SkinModel.SLIM, entry.toResolvedSkin("http://x/slim", true).model());
        assertEquals(SkinModel.WIDE, entry.toResolvedSkin("http://x/wide", false).model());
    }

    @Test
    void nameEntryModelOverrideWins() {
        PlayerMobSkinEntry entry = parse("""
            { "displayName": "Grian", "playerName": "Grian", "model": "wide" }
            """);
        // Explicit model overrides the resolver's slim report.
        assertEquals(SkinModel.WIDE, entry.toResolvedSkin("http://x/slim", true).model());
    }

    @Test
    void acceptsBothUrlAndName() {
        PlayerMobSkinEntry entry = parse("""
            { "displayName": "X", "textureUrl": "http://x/y", "playerName": "Notch" }
            """);
        // URL present → treated as a ready URL entry (no resolution needed).
        assertTrue(entry.hasUrl());
    }

    @Test
    void rejectsEntryWithNeitherUrlNorName() {
        var result = PlayerMobSkinEntry.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{ \"displayName\": \"X\" }"));
        assertTrue(result.error().isPresent(),
            "An entry with neither textureUrl nor playerName must be rejected");
    }

    @Test
    void rejectsBlankDisplayName() {
        var result = PlayerMobSkinEntry.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{ \"displayName\": \"\", \"playerName\": \"Notch\" }"));
        assertTrue(result.error().isPresent());
    }

    private static PlayerMobSkinEntry parse(String json) {
        return PlayerMobSkinEntry.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
            .result()
            .orElseThrow(() -> new AssertionError("Failed to parse: " + json));
    }
}
