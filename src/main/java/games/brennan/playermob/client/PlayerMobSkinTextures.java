package games.brennan.playermob.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import games.brennan.playermob.compat.PlayerSkinInfo;
import games.brennan.playermob.compat.SkinCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side helper that resolves a Mojang skin texture URL to a
 * {@link ResourceLocation} the renderer can draw, using vanilla
 * {@link net.minecraft.client.resources.SkinManager} so we share the on-disk
 * skin cache with vanilla.
 *
 * <p><b>How it works</b>: SkinManager normally consumes a real player's
 * {@link GameProfile} containing a Yggdrasil-signed {@code textures}
 * Property. We synthesize that Property locally — a base64-encoded JSON
 * blob with a {@code SKIN.url} field — and pass the GameProfile through
 * {@code SkinManager.getInsecureSkin(...)} (which skips signature
 * validation, as the name suggests, and is the same call vanilla makes
 * client-side for unsigned profiles).</p>
 *
 * <p>The GameProfile's UUID is derived deterministically from the texture
 * URL (via {@link UUID#nameUUIDFromBytes}) so two PlayerMobs sharing a skin
 * share the same vanilla cache entry — no duplicate downloads.</p>
 *
 * <p>While the SkinManager is fetching async, {@code .texture()} returns the
 * default skin for the profile's UUID — that's the graceful fallback when
 * the Mojang CDN is unreachable. We don't add any failure handling of our
 * own; vanilla's pipeline is already battle-tested.</p>
 *
 * <p>{@link Environment} {@code CLIENT} only — the GameProfile/SkinManager
 * stack is client-side. The entity's URL field is set server-side and
 * synced via SynchedEntityData; only the renderer (also client-only) reads
 * this helper.</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerMobSkinTextures {

    /**
     * Cache of texture URL → resolved (or fallback) ResourceLocation.
     * Polled from {@code Minecraft.getSkinManager()} on every renderer
     * frame so the in-flight default flips to the real texture as soon as
     * the async fetch completes — no event subscription needed.
     */
    //? if >=26 {
    /*private static final ConcurrentHashMap<String, Identifier> CACHE = new ConcurrentHashMap<>();
    *///?} else {
    private static final ConcurrentHashMap<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();
    //?}

    /**
     * Cached fake GameProfiles per URL so we don't rebuild the Property
     * (and re-base64 the JSON) on every render frame.
     */
    private static final ConcurrentHashMap<String, GameProfile> PROFILES = new ConcurrentHashMap<>();

    /**
     * Cached real-player {@link GameProfile}s per {@code uuid;name} spec, for the
     * reincarnation skin path. Keyed so two mobs reincarnating the same player share
     * one profile (and one vanilla skin-cache entry).
     */
    private static final ConcurrentHashMap<String, GameProfile> PLAYER_PROFILES = new ConcurrentHashMap<>();

    private PlayerMobSkinTextures() {}

    /**
     * Resolve a real player's skin (texture + model) through vanilla
     * {@link net.minecraft.client.resources.SkinManager} — the same path the client
     * uses to render that player. Yields the player's actual skin in production, or
     * their UUID-derived {@code DefaultPlayerSkin} offline/in dev (the very skin the
     * player themselves renders with), so a reincarnated PlayerMob always matches.
     * Never returns {@code null} — {@code getInsecureSkin} falls back internally.
     */
    public static PlayerSkinInfo playerSkin(UUID uuid, String name) {
        GameProfile profile = PLAYER_PROFILES.computeIfAbsent(uuid + ";" + name,
            key -> new GameProfile(uuid, (name == null || name.isEmpty()) ? "PlayerMob" : name));
        return SkinCompat.insecureSkin(profile);
    }

    /**
     * Resolve a texture URL to a renderable ResourceLocation. Never returns
     * {@code null}; falls back to the generic default skin
     * if the SkinManager hands us nothing.
     *
     * @param textureUrl the Mojang CDN texture URL (or any URL the client
     *                   can HTTP-GET a 64×64 PNG from).
     * @param slim       true if the underlying skin is slim-arms. Drives the
     *                   texture's {@code metadata.model} here; the renderer
     *                   separately swaps to the slim body model per-mob.
     */
    //? if >=26 {
    /*public static Identifier lookup(String textureUrl, boolean slim) {
    *///?} else {
    public static ResourceLocation lookup(String textureUrl, boolean slim) {
    //?}
        if (textureUrl == null || textureUrl.isEmpty()) {
            return SkinCompat.defaultTexture();
        }
        // Each render frame: ask the SkinManager. It returns the default
        // (matching the profile's UUID) while the fetch is in flight, then
        // flips to the resolved texture once cached. We update our own
        // cache to short-circuit subsequent frames after success.
        GameProfile profile = PROFILES.computeIfAbsent(textureUrl,
            url -> buildFakeProfile(url, slim));
        var resolved = SkinCompat.insecureSkin(profile).texture();
        if (resolved != null) {
            CACHE.put(textureUrl, resolved);
            return resolved;
        }
        return CACHE.getOrDefault(textureUrl, SkinCompat.defaultTexture());
    }

    /**
     * Build a fake GameProfile that SkinManager treats as an unsigned
     * player profile. The "textures" property is the standard base64-encoded
     * JSON Mojang's authlib emits.
     */
    private static GameProfile buildFakeProfile(String textureUrl, boolean slim) {
        // Stable UUID per URL ⇒ two mobs with the same skin share the cache.
        UUID uuid = UUID.nameUUIDFromBytes(("playermob:" + textureUrl).getBytes(StandardCharsets.UTF_8));
        String json = textureJson(textureUrl, slim);
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        // (Third Property arg is the signature — null for our unsigned synthetic profile.)
        Property texture = new Property("textures", base64, /* signature */ null);
        //? if >=26 {
        /*// authlib 9 (MC 26.x): GameProfile.properties() is immutable (rejects put()), and
        // PropertyMap has no no-arg ctor — so build the backing multimap WITH the texture, then
        // hand it to the 3-arg GameProfile constructor instead of mutating after construction.
        com.google.common.collect.Multimap<String, Property> backing = com.google.common.collect.LinkedHashMultimap.create();
        backing.put("textures", texture);
        return new GameProfile(uuid, "PlayerMob", new com.mojang.authlib.properties.PropertyMap(backing));
        *///?} else {
        GameProfile profile = new GameProfile(uuid, "PlayerMob");
        profile.getProperties().put("textures", texture);
        return profile;
        //?}
    }

    /**
     * Build the textures-property JSON payload. SLIM uses
     * {@code metadata.model = "slim"} to match vanilla's convention.
     */
    private static String textureJson(String url, boolean slim) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"textures\":{\"SKIN\":{\"url\":\"").append(escape(url)).append("\"");
        if (slim) {
            sb.append(",\"metadata\":{\"model\":\"slim\"}");
        }
        sb.append("}}}");
        return sb.toString();
    }

    private static String escape(String s) {
        // Texture URLs are simple ASCII (Mojang CDN paths); only " and \
        // need escaping for a valid JSON string.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
