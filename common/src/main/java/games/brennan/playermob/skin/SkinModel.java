package games.brennan.playermob.skin;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The player-model variant a skin is authored for. Mirrors vanilla
 * {@code net.minecraft.client.resources.PlayerSkin.Model}.
 *
 * <p>v2 ships <b>WIDE only</b> in the default skin pack — the renderer
 * currently always uses the wide {@code PlayerModel}, so SLIM entries are
 * accepted by the JSON codec but render with slightly thick arms. Slim-model
 * swap support is a v3 follow-up.</p>
 */
public enum SkinModel implements StringRepresentable {
    WIDE("wide"),
    SLIM("slim");

    public static final Codec<SkinModel> CODEC = StringRepresentable.fromEnum(SkinModel::values);

    private final String name;

    SkinModel(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
