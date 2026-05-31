package games.brennan.playermob.client;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders a {@link PlayerMobEntity} using the vanilla {@link PlayerModel} —
 * the same model used for real players, so the mob is shaped like one. Skin
 * texture is the static asset at
 * {@code playermob:textures/entity/player_mob.png}.
 *
 * <p>Wide (Steve) arms used in v1. A future version may add per-entity
 * GameProfile-backed skins; this renderer reads a single static texture for now.</p>
 *
 * <p>Annotated {@link Environment} {@code CLIENT} so the class is stripped
 * from server-distribution jars at load time. Safe — only the loader-side
 * client init classes reference it.</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerMobRenderer
        extends HumanoidMobRenderer<PlayerMobEntity, PlayerModel<PlayerMobEntity>> {

    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, "textures/entity/player_mob.png");

    /** Shadow disc radius below the entity, matches the player's 0.5. */
    private static final float SHADOW_RADIUS = 0.5F;

    public PlayerMobRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,
              new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), /* slim */ false),
              SHADOW_RADIUS);
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerMobEntity entity) {
        return TEXTURE;
    }
}
