package games.brennan.playermob.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Reads a player's own Minecraft skin out of their {@link GameProfile} — the
 * standard base64-encoded {@code textures} property Mojang signs onto every
 * authenticated profile. This is the inverse of {@code PlayerMobSkinTextures},
 * which builds that same JSON to hand a URL skin <em>to</em> the renderer; here
 * we pull the URL (and slim/wide model) <em>out</em> so a reincarnated PlayerMob
 * can wear the dead player's actual skin.
 *
 * <p>Returns empty when the profile has no textures (offline/cracked servers,
 * or a LAN host with an unsigned profile) — callers fall back to a random mob
 * skin. Pure string/JSON work, so the decode is unit-testable without a world.</p>
 */
public final class ProfileSkins {

    private ProfileSkins() {}

    /** A player's resolved skin: the Mojang texture URL and whether it's the slim (Alex) model. */
    public record Skin(String url, boolean slim) {}

    /** The skin baked into {@code profile}'s {@code textures} property, if any. */
    public static Optional<Skin> extract(GameProfile profile) {
        if (profile == null) {
            return Optional.empty();
        }
        for (Property property : profile.getProperties().get("textures")) {
            Optional<Skin> skin = decode(property.value());
            if (skin.isPresent()) {
                return skin;
            }
        }
        return Optional.empty();
    }

    /**
     * Decode one base64 {@code textures} property value to a {@link Skin}. Any
     * malformed / unexpected payload yields empty rather than throwing — external
     * data is never trusted to be well-formed.
     */
    static Optional<Skin> decode(String base64Value) {
        if (base64Value == null || base64Value.isEmpty()) {
            return Optional.empty();
        }
        try {
            String json = new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null) {
                return Optional.empty();
            }
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (skin == null || !skin.has("url")) {
                return Optional.empty();
            }
            String url = skin.get("url").getAsString();
            if (url == null || url.isEmpty()) {
                return Optional.empty();
            }
            boolean slim = false;
            if (skin.has("metadata")) {
                JsonObject metadata = skin.getAsJsonObject("metadata");
                slim = metadata != null
                    && metadata.has("model")
                    && "slim".equalsIgnoreCase(metadata.get("model").getAsString());
            }
            return Optional.of(new Skin(url, slim));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
