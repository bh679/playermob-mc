package games.brennan.playermob.skin;

import java.util.Optional;

/**
 * Resolves the human-readable source name of a skin from the value carried in a PlayerMob's
 * {@code SkinTextureUrl} field — used to auto-label naturally-spawned mobs (see
 * {@link games.brennan.playermob.entity.NaturalSpawnCompanion} and
 * {@link games.brennan.playermob.PlayerMobConfig#autoNameNaturalSpawns()}).
 *
 * <p>Two sources carry a name:</p>
 * <ul>
 *   <li><b>Local-folder skin</b> ({@code playermob-local:<name>}) → the base filename, decoded via
 *       {@link LocalSkinRef#decode(String)}.</li>
 *   <li><b>Online / registry skin</b> (a real texture URL) → the entry's {@link PlayerMobSkin#displayName()},
 *       looked up via {@link PlayerMobSkinRegistry#findByTextureUrl(String)}.</li>
 * </ul>
 *
 * <p>A bundled default skin (the {@code SkinTextureUrl} field is empty — the look comes from the
 * {@code SkinIndex} instead) has no source name, so this returns {@link Optional#empty()} and the caller
 * leaves the mob unnamed. The local-ref branch is pure and unit-tested; the registry branch depends on the
 * loaded skin registry (not populated under Bootstrap tests).</p>
 */
public final class SkinDisplayName {

    private SkinDisplayName() {}

    /**
     * The source name for the skin identified by {@code skinTextureUrl}, or empty when the value is a
     * bundled skin (blank), an unrecognised URL, or otherwise carries no name.
     */
    public static Optional<String> resolve(String skinTextureUrl) {
        if (skinTextureUrl == null || skinTextureUrl.isBlank()) {
            return Optional.empty();
        }
        Optional<String> local = LocalSkinRef.decode(skinTextureUrl);
        if (local.isPresent()) {
            return local;
        }
        return PlayerMobSkinRegistry.findByTextureUrl(skinTextureUrl)
            .map(PlayerMobSkin::displayName);
    }
}
