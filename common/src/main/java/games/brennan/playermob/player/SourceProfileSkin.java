package games.brennan.playermob.player;

import java.util.Optional;
import java.util.UUID;

/**
 * The "this PlayerMob is a reincarnation of player X" marker, carried in the entity's
 * existing {@code SkinTextureUrl} field with a scheme prefix. Rather than capturing a
 * skin URL at death — empty on offline/dev profiles, and stale if the player later
 * reskins — we record the source player's identity and let the client renderer resolve
 * the skin through vanilla {@code SkinManager}, exactly as it resolves any player's
 * skin: their real skin in production, their UUID-derived default skin offline/in dev.
 *
 * <p>Format: {@code playermob-profile:<uuid>;<name>}. A Minecraft username can't contain
 * {@code ';'}, so the first separator is unambiguous. Pure (no Minecraft) — unit-tested.</p>
 */
public final class SourceProfileSkin {

    /** Prefix that distinguishes a reincarnation profile-ref from a real texture URL. */
    public static final String PREFIX = "playermob-profile:";

    private static final char SEP = ';';

    private SourceProfileSkin() {}

    /** A reincarnated mob's source player. */
    public record Ref(UUID uuid, String name) {}

    /** Encode {@code playermob-profile:<uuid>;<name>} for the {@code SkinTextureUrl} field. */
    public static String encode(UUID uuid, String name) {
        return PREFIX + uuid + SEP + (name == null ? "" : name);
    }

    /**
     * Decode a {@code SkinTextureUrl} value to its source player, or empty if it is a
     * real URL / not a profile ref / malformed (external data is never trusted).
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
            return Optional.of(new Ref(uuid, body.substring(sep + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
