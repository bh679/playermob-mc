package games.brennan.playermob.client;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders a {@link PlayerMobEntity} using the vanilla {@link PlayerModel} —
 * same model used for real players, so the mob is shaped like one.
 *
 * <p><b>Two skin sources</b> — v2 prefers a Mojang texture URL via
 * {@link PlayerMobSkinTextures} (datapack-fed registry; per-entity real
 * player skins). When the entity has no URL — legacy 0.2.0 mobs in saved
 * worlds, or new mobs spawned while {@code PlayerMobSkinRegistry} is empty —
 * the renderer falls back to one of the 9 bundled vanilla default skins
 * ({@link #SKIN_NAMES}) shipped at
 * {@code assets/minecraft/textures/entity/player/wide/<name>.png}. We don't
 * ship any PNG bytes ourselves; we either point at vanilla's resources or
 * stream from Mojang's CDN via vanilla's {@code SkinManager} pipeline (so
 * the on-disk skin cache is shared with vanilla).</p>
 *
 * <p><b>Render layers added explicitly:</b> {@link HumanoidMobRenderer} does
 * NOT auto-add armor / held-item layers (unlike {@code PlayerRenderer}). If
 * we don't add them here, equipped armor and held weapons stay invisible
 * even though the entity's slots are correctly populated server-side. So
 * we add:</p>
 * <ul>
 *   <li>{@link HumanoidArmorLayer} — draws helmet/chestplate/leggings/boots</li>
 *   <li>{@link ItemInHandLayer} — draws main-hand + off-hand items (sword, crossbow, etc.)</li>
 * </ul>
 *
 * <p>The index → skin mapping is stable across builds (alphabetical). Don't
 * reorder {@link #SKIN_NAMES} without bumping a save migration — existing
 * mobs in saved worlds have a stored {@code SkinIndex} that maps to a
 * specific position in this array.</p>
 *
 * <p>Wide model (Steve-style arms) only — even when the URL skin's
 * {@code SkinSlim} flag is set, the renderer uses the wide
 * {@link PlayerModel} (slim arms will look slightly thick). Per-entity
 * slim/wide model swapping is a v3 follow-up — needs override of
 * {@code render(...)} to hot-swap the model field, which the layer
 * renderers' captured references make non-trivial.</p>
 *
 * <p>{@link Environment} {@code CLIENT}-only — stripped from dedicated
 * server jars at load time. Server-side code that needs the skin count
 * reads {@link PlayerMobEntity#SKIN_COUNT} instead.</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerMobRenderer
        extends HumanoidMobRenderer<PlayerMobEntity, PlayerModel<PlayerMobEntity>> {

    /**
     * Canonical order of the 9 vanilla default player skins. Index assigned
     * at spawn maps to a position here. <b>Do not reorder</b> without a
     * save-migration story — stored SkinIndex values reference array
     * positions, not names.
     */
    private static final String[] SKIN_NAMES = {
        "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"
    };

    /**
     * Cached ResourceLocations — one per default skin. Built once at class
     * load (no per-frame allocation). Pointers into the vanilla resource
     * pack; no PNG bytes are shipped in the PlayerMob jar.
     */
    private static final ResourceLocation[] SKIN_TEXTURES = buildSkinTextures();

    /** Shadow disc radius below the entity, matches the player's 0.5. */
    private static final float SHADOW_RADIUS = 0.5F;

    public PlayerMobRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,
              new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), /* slim */ false),
              SHADOW_RADIUS);

        // Armor layer — draws helmet/chest/legs/boots when slots are populated.
        // Inner / outer models are the standard player armor models (wide
        // variant matches our wide PlayerModel above).
        this.addLayer(new HumanoidArmorLayer<>(
            this,
            new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()));

        // Held-item layer — draws whatever the mob holds in mainhand + offhand.
        // PlayerModel implements ArmedModel so the layer knows where to anchor.
        this.addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
    }

    /**
     * {@link HumanoidMobRenderer} never sets the model's {@code crouching} flag
     * — only {@code PlayerRenderer} does, for real players — so the sneak pose
     * driven by {@link PlayerMobEntity#setCrouching} (Friendly greeting, Shy
     * hiding) would otherwise never render. Mirror the entity's crouch state
     * onto the model each frame, exactly as PlayerRenderer does.
     */
    @Override
    public void render(PlayerMobEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.getModel().crouching = entity.isCrouching();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerMobEntity entity) {
        return resolveSkin(entity);
    }

    /**
     * Resolve a PlayerMob's skin to a renderable texture — shared by
     * {@link #getTextureLocation} and the menu UI (so a relationship row can draw
     * a target PlayerMob's face). v2 prefers the Mojang URL skin (via
     * {@link PlayerMobSkinTextures}, which returns {@code DefaultPlayerSkin} while
     * the async fetch is in flight, then flips to the real texture once cached);
     * otherwise the bundled vanilla default keyed off SkinIndex (v1 behaviour),
     * clamped defensively to skin 0.
     */
    public static ResourceLocation resolveSkin(PlayerMobEntity entity) {
        String url = entity.getSkinTextureUrl();
        if (!url.isEmpty()) {
            return PlayerMobSkinTextures.lookup(url, entity.isSkinSlim());
        }
        int idx = entity.getSkinIndex();
        if (idx < 0 || idx >= SKIN_TEXTURES.length) {
            return SKIN_TEXTURES[0];
        }
        return SKIN_TEXTURES[idx];
    }

    private static ResourceLocation[] buildSkinTextures() {
        if (SKIN_NAMES.length != PlayerMobEntity.SKIN_COUNT) {
            throw new IllegalStateException(
                "SKIN_NAMES.length (" + SKIN_NAMES.length
                + ") must equal PlayerMobEntity.SKIN_COUNT ("
                + PlayerMobEntity.SKIN_COUNT + ")");
        }
        ResourceLocation[] arr = new ResourceLocation[SKIN_NAMES.length];
        for (int i = 0; i < SKIN_NAMES.length; i++) {
            arr[i] = ResourceLocation.fromNamespaceAndPath(
                "minecraft",
                "textures/entity/player/wide/" + SKIN_NAMES[i] + ".png");
        }
        return arr;
    }
}
