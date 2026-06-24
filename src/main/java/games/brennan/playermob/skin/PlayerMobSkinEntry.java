package games.brennan.playermob.skin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * The on-disk shape of a {@code data/&lt;namespace&gt;/playermob_skins/*.json} entry — the format
 * mob-pack authors actually write. It is decoded by {@link PlayerMobSkinReloadListener} and then
 * turned into one or more resolved {@link PlayerMobSkin}s for {@link PlayerMobSkinRegistry}.
 *
 * <p>An entry supplies <b>one of two</b> ways to point at a skin:</p>
 * <ul>
 *   <li><b>{@code textureUrl}</b> — a pre-resolved Mojang CDN texture URL (the original v2 format).
 *       Used verbatim, no network. The {@code model} field (defaulting to {@code wide}) picks the
 *       arm variant, exactly as before.</li>
 *   <li><b>{@code playerName}</b> — a Minecraft username (or a UUID). PlayerMob resolves it to that
 *       player's current skin URL + arm model at load time through {@link PlayerSkinResolver}, the
 *       same way vanilla bakes a skin into a {@code player_head}. Saves authors the manual
 *       username→UUID→texture-hash recipe. {@code model}, if given, overrides the resolved arm
 *       variant; otherwise the resolved one is used.</li>
 * </ul>
 *
 * <p>Exactly the legacy URL-only files still parse unchanged — {@code playerName} is purely additive
 * and {@code textureUrl} stays optional only in the presence of a {@code playerName}. An entry with
 * neither (or with a blank value) is rejected by the codec as a {@link DataResult} error, so it
 * surfaces as a {@code WARN}-level skip rather than killing the whole reload.</p>
 *
 * <p>{@code displayName} stays required and informational (the renderer never shows it) — it
 * documents who the entry portrays and, for a {@code playerName} entry, need not equal the
 * username.</p>
 */
public record PlayerMobSkinEntry(String displayName,
                                 Optional<String> textureUrl,
                                 Optional<String> playerName,
                                 Optional<SkinModel> model) {

    /** Non-blank string codec — a blank value is a parse error rather than a thrown exception. */
    private static final Codec<String> NON_BLANK = Codec.STRING.flatXmap(
        s -> (s == null || s.isBlank())
            ? DataResult.error(() -> "must be non-blank")
            : DataResult.success(s),
        DataResult::success);

    public static final Codec<PlayerMobSkinEntry> CODEC = RecordCodecBuilder.<PlayerMobSkinEntry>create(instance ->
        instance.group(
            NON_BLANK.fieldOf("displayName").forGetter(PlayerMobSkinEntry::displayName),
            NON_BLANK.optionalFieldOf("textureUrl").forGetter(PlayerMobSkinEntry::textureUrl),
            NON_BLANK.optionalFieldOf("playerName").forGetter(PlayerMobSkinEntry::playerName),
            SkinModel.CODEC.optionalFieldOf("model").forGetter(PlayerMobSkinEntry::model)
        ).apply(instance, PlayerMobSkinEntry::new)
    // Cross-field rule: an entry must point at a skin *somehow*. Enforced here (not in the
    // constructor) so a malformed entry is a graceful DataResult.error the listener can skip.
    ).flatXmap(PlayerMobSkinEntry::validate, PlayerMobSkinEntry::validate);

    public PlayerMobSkinEntry {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must be non-blank");
        }
        textureUrl = textureUrl == null ? Optional.empty() : textureUrl;
        playerName = playerName == null ? Optional.empty() : playerName;
        model = model == null ? Optional.empty() : model;
    }

    private static DataResult<PlayerMobSkinEntry> validate(PlayerMobSkinEntry entry) {
        if (entry.textureUrl.isEmpty() && entry.playerName.isEmpty()) {
            return DataResult.error(() -> "skin entry must have either 'textureUrl' or 'playerName'");
        }
        return DataResult.success(entry);
    }

    /** True when this entry ships a ready-to-use texture URL (no resolution needed). */
    public boolean hasUrl() {
        return textureUrl.isPresent();
    }

    /**
     * Build the resolved {@link PlayerMobSkin} for a URL-bearing entry. Only valid when
     * {@link #hasUrl()}; the model defaults to {@link SkinModel#WIDE} when the author omitted it
     * (preserving the original behaviour where a missing {@code model} meant wide arms).
     */
    public PlayerMobSkin toUrlSkin() {
        return new PlayerMobSkin(displayName, textureUrl.orElseThrow(), model.orElse(SkinModel.WIDE));
    }

    /**
     * Combine this name-entry with a resolved {@code (url, slim)} into a {@link PlayerMobSkin}. The
     * author's explicit {@code model} wins if present; otherwise the arm variant Mojang reported for
     * the real player is used.
     */
    public PlayerMobSkin toResolvedSkin(String resolvedUrl, boolean resolvedSlim) {
        SkinModel resolvedModel = model.orElse(resolvedSlim ? SkinModel.SLIM : SkinModel.WIDE);
        return new PlayerMobSkin(displayName, resolvedUrl, resolvedModel);
    }
}
