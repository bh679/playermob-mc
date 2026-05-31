package games.brennan.playermob.client;

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
 * same model used for real players, so the mob is shaped like one.
 *
 * <p><b>Skins come from the vanilla client jar.</b> Each mob picks one of
 * the 9 default Minecraft player skins ({@link #SKIN_NAMES}) shipped at
 * {@code assets/minecraft/textures/entity/player/wide/<name>.png}. We don't
 * bundle our own copies — pointing at the {@code minecraft:} namespace
 * resources keeps the jar small and gives the mob authentic "real player"
 * faces (Steve, Alex, plus the 7 newer defaults added in 1.19.3:
 * Ari, Efe, Kai, Makena, Noor, Sunny, Zuri).</p>
 *
 * <p>The index → skin mapping is stable across builds (alphabetical). Don't
 * reorder {@link #SKIN_NAMES} without bumping a save migration — existing
 * mobs in saved worlds have a stored {@code SkinIndex} that maps to a
 * specific position in this array.</p>
 *
 * <p>Wide model (Steve-style arms) used in v1; future v2 may add slim
 * variant support. Future v2 may also add per-entity GameProfile-backed
 * Mojang skin URLs (see GH issue #1).</p>
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
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerMobEntity entity) {
        int idx = entity.getSkinIndex();
        if (idx < 0 || idx >= SKIN_TEXTURES.length) {
            // Defensive: should never happen — clamp on the entity already
            // guards. Falls back to skin 0 (alex) if it does.
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
