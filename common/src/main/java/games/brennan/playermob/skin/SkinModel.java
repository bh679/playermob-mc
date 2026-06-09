package games.brennan.playermob.skin;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The player-model variant a skin is authored for. Mirrors vanilla
 * {@code net.minecraft.client.resources.PlayerSkin.Model}.
 *
 * <p>Both variants render correctly: the renderer bakes a wide and a slim
 * {@code PlayerModel} and swaps per-mob on {@code PlayerMobEntity.isSkinSlim()},
 * so a {@code SLIM} entry draws with Alex-style 3-pixel arms. The default skin
 * pack mixes both (e.g. Bread_Crisper is slim).</p>
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
