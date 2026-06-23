package games.brennan.playermob.skin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A single PlayerMob skin entry — a real Minecraft texture URL plus the
 * model variant it was authored for. Loaded from datapack JSON via
 * {@link PlayerMobSkinReloadListener} into {@link PlayerMobSkinRegistry}.
 *
 * <p><b>Why pre-resolved URLs:</b> Mojang's CDN URLs are content-hashed
 * ({@code http://textures.minecraft.net/texture/&lt;sha256-hex&gt;}) and
 * therefore immutable. Once a username is resolved to a URL we can ship the
 * URL verbatim and never hit the rate-limited sessionserver API at runtime.
 * If a real player changes their skin, the mob keeps showing the snapshot —
 * acceptable trade-off for v2.</p>
 *
 * <p>The {@code displayName} is purely informational; the renderer never
 * shows it. It exists so datapack authors can document which IRL player
 * each entry was sourced from.</p>
 */
public record PlayerMobSkin(String displayName, String textureUrl, SkinModel model) {

    /**
     * String codec that rejects blank values as a {@link DataResult} error
     * rather than throwing — so a single malformed entry in a datapack
     * surfaces as a {@code WARN}-level skip in {@link PlayerMobSkinReloadListener}
     * instead of killing the whole reload.
     */
    private static final Codec<String> NON_BLANK = Codec.STRING.flatXmap(
        s -> (s == null || s.isBlank())
            ? DataResult.error(() -> "must be non-blank")
            : DataResult.success(s),
        DataResult::success);

    public static final Codec<PlayerMobSkin> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            NON_BLANK.fieldOf("displayName").forGetter(PlayerMobSkin::displayName),
            NON_BLANK.fieldOf("textureUrl").forGetter(PlayerMobSkin::textureUrl),
            SkinModel.CODEC.optionalFieldOf("model", SkinModel.WIDE).forGetter(PlayerMobSkin::model)
        ).apply(instance, PlayerMobSkin::new)
    );

    public PlayerMobSkin {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must be non-blank");
        }
        if (textureUrl == null || textureUrl.isBlank()) {
            throw new IllegalArgumentException("textureUrl must be non-blank");
        }
        if (model == null) {
            throw new IllegalArgumentException("model must be non-null");
        }
    }
}
