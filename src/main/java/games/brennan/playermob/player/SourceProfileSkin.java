package games.brennan.playermob.player;

import java.util.Optional;
import java.util.UUID;

/**
 * The "this PlayerMob is a reincarnation of player X" marker, carried in the entity's
 * existing {@code SkinTextureUrl} field with a scheme prefix.
 *
 * <p>It records the source player's identity (uuid + name) and — captured at death from the
 * player's authenticated {@code GameProfile} — their skin texture URL. The client renderer
 * draws that captured URL through the normal Mojang-URL skin path (see
 * {@code PlayerMobSkinTextures#lookup}, which shares vanilla's on-disk skin cache); the
 * uuid/name remain for titling friend-echoes and as a fallback.</p>
 *
 * <p>The URL must be <em>captured</em> rather than resolved live from the uuid: vanilla
 * {@code SkinManager.getInsecureSkin} only reads a {@code textures} property already attached to
 * the profile it's handed — it never looks textures up from a bare uuid — so a uuid-only ref
 * always renders the UUID-derived default skin. We read the textures already present on the
 * dying player's profile (no network download) and store the URL here.</p>
 *
 * <p>Format: {@code playermob-profile:<uuid>;<name>[;<url>]}. The url segment is optional and is
 * absent for offline/dev deaths (no skin to capture) and for pre-fix saved refs, which then fall
 * back to the default skin. Neither a Minecraft username nor a Mojang texture URL can contain
 * {@code ';'}, so the segments are unambiguous. Pure (no Minecraft) — unit-tested.</p>
 */
public final class SourceProfileSkin {

    /** Prefix that distinguishes a reincarnation profile-ref from a real texture URL. */
    public static final String PREFIX = "playermob-profile:";

    private static final char SEP = ';';

    private SourceProfileSkin() {}

    /**
     * A reincarnated mob's source player plus the skin texture URL captured from them at death
     * ({@code ""} when none was captured — offline/dev, or a pre-fix ref).
     */
    public record Ref(UUID uuid, String name, String url) {}

    /** Encode with no captured skin URL — {@link #encode(UUID, String, String)} with an empty url. */
    public static String encode(UUID uuid, String name) {
        return encode(uuid, name, "");
    }

    /**
     * Encode {@code playermob-profile:<uuid>;<name>[;<url>]} for the {@code SkinTextureUrl} field.
     * The url segment is omitted when {@code url} is null/empty, so a url-less capture encodes
     * identically to {@link #encode(UUID, String)}.
     */
    public static String encode(UUID uuid, String name, String url) {
        String base = PREFIX + uuid + SEP + (name == null ? "" : name);
        return (url == null || url.isEmpty()) ? base : base + SEP + url;
    }

    /**
     * Decode a {@code SkinTextureUrl} value to its source player + captured skin url, or empty if
     * it is a real URL / not a profile ref / malformed (external data is never trusted). A ref with
     * no url segment decodes with {@code url == ""}.
     */
    public static Optional<Ref> decode(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String body = value.substring(PREFIX.length());
        int sep = body.indexOf(SEP);
        if (sep < 0) {
            return Optional.empty();
        }
        try {
            UUID uuid = UUID.fromString(body.substring(0, sep));
            String rest = body.substring(sep + 1);
            int sep2 = rest.indexOf(SEP);
            String name = sep2 < 0 ? rest : rest.substring(0, sep2);
            String url = sep2 < 0 ? "" : rest.substring(sep2 + 1);
            return Optional.of(new Ref(uuid, name, url));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
