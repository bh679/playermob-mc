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
 * same model used for real players, so the mob is shaped like one. Skin
 * texture is chosen per-entity from {@link #SKIN_TEXTURES}, indexed by
 * {@code entity.getSkinIndex()} — that index is rolled once on
 * {@link PlayerMobEntity#finalizeSpawn} and persists across save/load.
 *
 * <p>The texture array is built once at class load and reused — keeps
 * per-frame work to a single bounds-safe array lookup.</p>
 *
 * <p>Wide (Steve) arms used in v1. Future v2: per-entity GameProfile-backed
 * Mojang skin URLs (see GH issue #1).</p>
 *
 * <p>Annotated {@link Environment} {@code CLIENT} so the class is stripped
 * from server-distribution jars at load time. Safe — only the loader-side
 * client init classes reference it.</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerMobRenderer
        extends HumanoidMobRenderer<PlayerMobEntity, PlayerModel<PlayerMobEntity>> {

    /**
     * Cached ResourceLocations — one per bundled skin. Built once at class
     * load (no per-frame allocation). Size driven by
     * {@link PlayerMobEntity#SKIN_COUNT} (defined on the entity class so
     * server-side {@code finalizeSpawn} can read it without loading this
     * client-only renderer).
     */
    private static final ResourceLocation[] SKIN_TEXTURES = buildSkinTextures();

    /** Shadow disc radius below the entity, matches the player's 0.5. */
    private static final float SHADOW_RADIUS = 0.5F;

    public PlayerMobRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,
              new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), /* slim */ false),
              SHADOW_RADIUS);
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerMobEntity entity) {
        int idx = entity.getSkinIndex();
        if (idx < 0 || idx >= SKIN_TEXTURES.length) {
            // Defensive: should never happen — Stance.byOrdinal-style guard on
            // the entity already clamps. Falls back to skin 0 if it does.
            return SKIN_TEXTURES[0];
        }
        return SKIN_TEXTURES[idx];
    }

    private static ResourceLocation[] buildSkinTextures() {
        ResourceLocation[] arr = new ResourceLocation[PlayerMobEntity.SKIN_COUNT];
        for (int i = 0; i < PlayerMobEntity.SKIN_COUNT; i++) {
            arr[i] = ResourceLocation.fromNamespaceAndPath(
                PlayerMob.MOD_ID,
                "textures/entity/skins/skin_" + i + ".png");
        }
        return arr;
    }
}
