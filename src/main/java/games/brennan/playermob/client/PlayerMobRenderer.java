package games.brennan.playermob.client;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.player.SourceProfileSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Optional;

/**
 * Renders a {@link PlayerMobEntity} using the vanilla {@link PlayerModel} —
 * same model used for real players, so the mob is shaped like one.
 *
 * <p><b>Two skin sources</b> — v2 prefers a Mojang texture URL via
 * {@link PlayerMobSkinTextures} (datapack-fed registry; per-entity real
 * player skins). When the entity has no URL — legacy 0.2.0 mobs in saved
 * worlds, or new mobs spawned while {@code PlayerMobSkinRegistry} is empty —
 * the renderer falls back to one of the 9 bundled vanilla default skins
 * ({@link #SKIN_NAMES}), rendered from
 * {@code assets/minecraft/textures/entity/player/{wide,slim}/<name>.png} — the
 * {@code wide/} or {@code slim/} variant chosen per-mob from
 * {@link PlayerMobEntity#isSkinSlim()} (vanilla ships both for every default
 * name). We don't
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
 * <p><b>Slim &amp; wide arms</b> — the renderer bakes both a wide
 * ({@link ModelLayers#PLAYER}) and a slim ({@link ModelLayers#PLAYER_SLIM})
 * {@link PlayerModel} once, and {@code render(...)} picks per-mob from
 * {@link PlayerMobEntity#isSkinSlim()} by swapping the {@code model} field
 * before {@code super.render(...)}. Render layers read {@code getModel()}
 * each frame, so the armor / held-item layers follow the swap. Armor itself
 * stays the wide armor model — matching how vanilla draws armor on
 * slim-armed players. The bundled-default texture folder ({@code slim/} vs
 * {@code wide/}) follows the same {@code isSkinSlim()} flag, so model and
 * texture always agree.</p>
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
     * Cached ResourceLocations — one per default skin, in each arm-model folder.
     * Built once at class load (no per-frame allocation). Pointers into the
     * vanilla resource pack; no PNG bytes are shipped in the PlayerMob jar.
     * {@code getTextureLocation} reads the wide or slim table per-mob from
     * {@link PlayerMobEntity#isSkinSlim()} — the same flag that drives the body
     * model swap. Vanilla ships both variants for every default name.
     */
    private static final ResourceLocation[] SKIN_TEXTURES_WIDE = buildSkinTextures("wide");
    private static final ResourceLocation[] SKIN_TEXTURES_SLIM = buildSkinTextures("slim");

    /** Shadow disc radius below the entity, matches the player's 0.5. */
    private static final float SHADOW_RADIUS = 0.5F;

    /**
     * Max distance (squared) at which the Creative-only objective readout draws —
     * keeps a scene full of PlayerMobs from filling the screen with floating text.
     */
    private static final double MAX_READOUT_DISTANCE_SQR = 24.0 * 24.0;

    /**
     * The two body models, baked once at construction. {@code render(...)} swaps
     * {@code this.model} between them per-mob based on
     * {@link PlayerMobEntity#isSkinSlim()}: {@code wideModel} is the Steve-style
     * model handed to {@code super(...)} ({@link ModelLayers#PLAYER}),
     * {@code slimModel} is the Alex-style 3-pixel-arm model
     * ({@link ModelLayers#PLAYER_SLIM}).
     */
    private final PlayerModel<PlayerMobEntity> wideModel;
    private final PlayerModel<PlayerMobEntity> slimModel;

    public PlayerMobRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,
              new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), /* slim */ false),
              SHADOW_RADIUS);

        // Bake both arm variants once. The wide model is the instance we just
        // handed super (now this.model / getModel()); the slim model is swapped
        // in per-frame by render() for mobs whose skin was authored slim.
        this.wideModel = this.getModel();
        this.slimModel = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), /* slim */ true);

        // Armor layer — draws helmet/chest/legs/boots when slots are populated.
        // Inner / outer models are the standard (wide) player armor models —
        // vanilla draws this same armor model on slim-armed players too.
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
     * Per-frame model setup that {@link HumanoidMobRenderer} doesn't do (only
     * {@code PlayerRenderer} does, for real players):
     * <ul>
     *   <li>swap {@code this.model} to the slim or wide variant for this mob's skin
     *       (see the class javadoc) — done first so everything below, and the layers,
     *       act on the chosen model;</li>
     *   <li>{@code crouching} — the sneak pose driven by {@link PlayerMobEntity#setCrouching}
     *       (Friendly greeting, Shy hiding);</li>
     *   <li>arm poses — so held items render like a player's; most importantly a raised
     *       shield shows the BLOCK pose (arm up, shield in front) while the mob is blocking
     *       ranged fire, exactly like a player holding right-click. See {@link #applyArmPoses}.</li>
     * </ul>
     */
    @Override
    public void render(PlayerMobEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Pick THIS mob's arm model before anything reads it. Render layers go
        // through getModel() each frame, so the armor / held-item layers follow.
        this.model = isSlimModel(entity) ? slimModel : wideModel;
        PlayerModel<PlayerMobEntity> model = this.getModel();
        model.crouching = entity.isCrouching();
        applyArmPoses(entity, model);
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
        renderObjectiveReadout(entity, partialTick, poseStack, buffer, packedLight);
    }

    /**
     * Draws the mob's current objective(s) as billboarded text just under where
     * its name tag sits — Creative only. Ambient mobs show the single top
     * objective; the mob under the player's crosshair shows the full goal stack.
     * The goal state is computed server-side and synced
     * ({@link PlayerMobEntity#getObjectivesReadout()}); this only formats it.
     */
    private void renderObjectiveReadout(PlayerMobEntity entity, float partialTick,
                                        PoseStack poseStack, MultiBufferSource buffer,
                                        int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isCreative()) {
            return;
        }
        if (this.entityRenderDispatcher.distanceToSqr(entity) > MAX_READOUT_DISTANCE_SQR) {
            return;
        }
        String readout = entity.getObjectivesReadout();
        if (readout == null || readout.isEmpty()) {
            return;
        }

        boolean focused = this.entityRenderDispatcher.crosshairPickEntity == entity;
        String[] lines = readout.split("\n");
        int count = focused ? lines.length : 1;

        Vec3 anchor = entity.getAttachments()
            .getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        if (anchor == null) {
            return;
        }

        Font font = this.getFont();
        int bgColor = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;

        poseStack.pushPose();
        // The billboard transform vanilla uses for name tags: anchor above the
        // head, face the camera, scale down to text size (Y flipped = upright).
        poseStack.translate(anchor.x, anchor.y + 0.5, anchor.z);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();

        for (int i = 0; i < count; i++) {
            String line = lines[i];
            float x = -font.width(line) / 2.0F;
            float y = (i + 1) * (font.lineHeight + 1);  // sit a row below the name
            int color = i == 0 ? 0xFFFFFFFF : 0xFFBFBFBF;
            // Faint see-through pass (with background plate) + solid front pass —
            // the same two-pass look as a vanilla name tag.
            font.drawInBatch(line, x, y, 0x20FFFFFF, false, matrix, buffer,
                Font.DisplayMode.SEE_THROUGH, bgColor, packedLight);
            font.drawInBatch(line, x, y, color, false, matrix, buffer,
                Font.DisplayMode.NORMAL, 0, packedLight);
        }
        poseStack.popPose();
    }

    /**
     * Mirror vanilla's player arm-pose logic onto the model so held items pose the
     * way they do on a real player — a blocking shield rises into the BLOCK pose, a
     * drawn bow into BOW_AND_ARROW, a charging/charged crossbow into CROSSBOW_*, etc.
     * Faithful port of {@code PlayerRenderer.setModelProperties} + {@code getArmPose}.
     * Set every frame because the model instance is shared across all PlayerMobs, and
     * before {@code super.render} runs the model's {@code setupAnim} (which reads these).
     */
    private static void applyArmPoses(PlayerMobEntity entity, PlayerModel<PlayerMobEntity> model) {
        HumanoidModel.ArmPose mainPose = armPose(entity, InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose offPose = armPose(entity, InteractionHand.OFF_HAND);
        // A two-handed main-hand pose (bow/crossbow/spear) frees the off hand to a
        // plain item-or-empty pose, matching the player.
        if (mainPose.isTwoHanded()) {
            offPose = entity.getOffhandItem().isEmpty()
                ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        if (entity.getMainArm() == HumanoidArm.RIGHT) {
            model.rightArmPose = mainPose;
            model.leftArmPose = offPose;
        } else {
            model.rightArmPose = offPose;
            model.leftArmPose = mainPose;
        }
    }

    /** Per-hand arm pose, mirroring {@code PlayerRenderer.getArmPose} for a PlayerMob. */
    private static HumanoidModel.ArmPose armPose(PlayerMobEntity entity, InteractionHand hand) {
        ItemStack stack = entity.getItemInHand(hand);
        if (stack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        if (entity.getUsedItemHand() == hand && entity.getUseItemRemainingTicks() > 0) {
            UseAnim useAnim = stack.getUseAnimation();
            if (useAnim == UseAnim.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (useAnim == UseAnim.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (useAnim == UseAnim.SPEAR) return HumanoidModel.ArmPose.THROW_SPEAR;
            if (useAnim == UseAnim.CROSSBOW) return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            if (useAnim == UseAnim.SPYGLASS) return HumanoidModel.ArmPose.SPYGLASS;
            if (useAnim == UseAnim.TOOT_HORN) return HumanoidModel.ArmPose.TOOT_HORN;
            if (useAnim == UseAnim.BRUSH) return HumanoidModel.ArmPose.BRUSH;
        } else if (!entity.swinging
                && stack.getItem() instanceof CrossbowItem
                && isChargedCrossbow(stack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    /** 1.21.1 charged-crossbow test via the CHARGED_PROJECTILES component (see PlayerMobCrossbowAttackGoal). */
    private static boolean isChargedCrossbow(ItemStack stack) {
        ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
        return charged != null && !charged.isEmpty();
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
        // Reincarnation: a profile-ref carries the source player's skin URL captured at death.
        // Draw it through the normal Mojang-URL path (shared vanilla skin cache). A url-less ref
        // (offline/dev death, or a pre-fix saved ref) falls back to the source player's default.
        Optional<SourceProfileSkin.Ref> source = SourceProfileSkin.decode(url);
        if (source.isPresent()) {
            String captured = source.get().url();
            if (!captured.isEmpty()) {
                return PlayerMobSkinTextures.lookup(captured, entity.isSkinSlim());
            }
            return PlayerMobSkinTextures.playerSkin(source.get().uuid(), source.get().name()).texture();
        }
        if (!url.isEmpty()) {
            return PlayerMobSkinTextures.lookup(url, entity.isSkinSlim());
        }
        // No URL ⇒ legacy 0.2.0 mob, or registry-empty new mob. Render the
        // bundled vanilla default keyed off SkinIndex (v1 behaviour), from the
        // slim/ or wide/ folder matching this mob's arm model — both ship in
        // vanilla for every default name, and the body model already follows
        // the same isSkinSlim() flag (see render()).
        ResourceLocation[] table = entity.isSkinSlim() ? SKIN_TEXTURES_SLIM : SKIN_TEXTURES_WIDE;
        int idx = entity.getSkinIndex();
        if (idx < 0 || idx >= table.length) {
            // Defensive: should never happen — clamp on the entity already
            // guards. Falls back to skin 0 (alex) if it does.
            return table[0];
        }
        return table[idx];
    }

    /**
     * Whether to draw the slim (3-pixel-arm) body model. For a reincarnation skin the arm model
     * was captured at death into the synched slim flag (a pre-fix url-less ref still resolves it
     * from the source-player skin); otherwise it comes from the mob's own synched slim flag.
     */
    private static boolean isSlimModel(PlayerMobEntity entity) {
        Optional<SourceProfileSkin.Ref> source = SourceProfileSkin.decode(entity.getSkinTextureUrl());
        if (source.isPresent()) {
            if (!source.get().url().isEmpty()) {
                return entity.isSkinSlim();
            }
            return PlayerMobSkinTextures.playerSkin(source.get().uuid(), source.get().name()).model()
                == PlayerSkin.Model.SLIM;
        }
        return entity.isSkinSlim();
    }

    /**
     * Builds the per-skin texture table for one arm-model folder ({@code "wide"}
     * or {@code "slim"}). Both folders ship every default name in the vanilla jar.
     */
    private static ResourceLocation[] buildSkinTextures(String folder) {
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
                "textures/entity/player/" + folder + "/" + SKIN_NAMES[i] + ".png");
        }
        return arr;
    }
}
