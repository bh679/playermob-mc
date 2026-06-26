package games.brennan.playermob.skin;

import java.util.Optional;

/**
 * The "this PlayerMob wears the local skin file {@code <name>}" marker, carried in the entity's
 * existing {@code SkinTextureUrl} field with a scheme prefix — the same overloading trick used by
 * {@link games.brennan.playermob.player.SourceProfileSkin} for reincarnation echoes.
 *
 * <p>A local skin is one a player dropped into {@code config/playermob/skins/} (see
 * {@link LocalSkinFolder}). Because the value rides the existing synced/persisted URL field, no new
 * {@code EntityDataAccessor} or NBT key is needed and the save format is unchanged. The client
 * renderer decodes the ref and loads the PNG off disk via {@code LocalSkinTextures}; an older mod
 * version (or a client missing the file) decodes nothing useful, treats it as an unreachable URL, and
 * falls back to a bundled skin — graceful degradation.</p>
 *
 * <p>Format: {@code playermob-local:<name>}, where {@code name} is a sanitized base filename (no
 * extension, no path separators). Sanitization rejects path traversal so a synced ref can never
 * escape the skins folder. Pure (no Minecraft) — unit-tested.</p>
 */
public final class LocalSkinRef {

    /** Prefix that distinguishes a local-folder skin ref from a real texture URL or a profile ref. */
    public static final String PREFIX = "playermob-local:";

    private LocalSkinRef() {}

    /**
     * Encode {@code playermob-local:<name>} for the {@code SkinTextureUrl} field, or {@code ""} when
     * {@code name} is not a safe simple filename (so a bad name never produces a usable ref —
     * the caller's empty-URL path then falls through to the bundled skin).
     */
    public static String encode(String name) {
        String safe = sanitize(name);
        return safe == null ? "" : PREFIX + safe;
    }

    /**
     * Decode a {@code SkinTextureUrl} value to its local skin name, or empty if it is a real URL /
     * profile ref / not a local ref / carries an unsafe name (external data is never trusted).
     */
    public static Optional<String> decode(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sanitize(value.substring(PREFIX.length())));
    }

    /**
     * Return {@code name} if it is a safe simple filename — non-blank, no path separators, not a
     * {@code .}/{@code ..} traversal, no control characters — else {@code null}. A defensive guard so a
     * value synced from the server (or read from disk) can only ever name a file directly inside the
     * skins folder.
     */
    public static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
            return null;
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")
            || trimmed.contains(":") || trimmed.contains(";")) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) < ' ') {
                return null;
            }
        }
        return trimmed;
    }
}
