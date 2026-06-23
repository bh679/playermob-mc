package games.brennan.playermob.client;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.player.SourceProfileSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
//? if >=26 {
/*import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import games.brennan.playermob.compat.CrossbowCompat;
import games.brennan.playermob.compat.RegistryCompat;
import games.brennan.playermob.compat.SkinCompat;
import net.minecraft.world.InteractionHand;
//? if >=1.21.1 {
import net.minecraft.world.entity.EntityAttachment;
//?}
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
//? if >=26 {
/*import net.minecraft.world.item.ItemUseAnimation;
*///?} else {
import net.minecraft.world.item.UseAnim;
//?}
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Optional;
//? if >=26 {
/*import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
*///?}

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
//? if >=26 {
/*public final class PlayerMobRenderer
        extends HumanoidMobRenderer<PlayerMobEntity, AvatarRenderState, PlayerModel> {
*///?} else {
public final class PlayerMobRenderer
        extends HumanoidMobRenderer<PlayerMobEntity, PlayerModel<PlayerMobEntity>> {
//?}

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
    //? if >=26 {
    /*private static final Identifier[] SKIN_TEXTURES_WIDE = buildSkinTextures("wide");
    private static final Identifier[] SKIN_TEXTURES_SLIM = buildSkinTextures("slim");
    *///?} else {
    private static final ResourceLocation[] SKIN_TEXTURES_WIDE = buildSkinTextures("wide");
    private static final ResourceLocation[] SKIN_TEXTURES_SLIM = buildSkinTextures("slim");
    //?}

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
    //? if >=26 {
    /*private final PlayerModel wideModel;
    private final PlayerModel slimModel;

    // Objective-readout snapshot, keyed by render-state identity. 26.x's render-state system
    // forbids carrying custom fields on AvatarRenderState (PlayerModel + the armor/held-item
    // layers are invariant over it), so the Creative readout is stashed per-state here in
    // extractRenderState and read back in submitNameDisplay. States are pooled per entity, so
    // this map stays bounded to the visible PlayerMob count.
    private final Map<AvatarRenderState, ReadoutSnapshot> readouts = new ConcurrentHashMap<>();

    // A frame's snapshot of a mob's Creative objective readout (the billboarded debug text).
    private record ReadoutSnapshot(String text, boolean focused) {}
    *///?} else {
    private final PlayerModel<PlayerMobEntity> wideModel;
    private final PlayerModel<PlayerMobEntity> slimModel;
    //?}

    //? if >=26 {
    /*public PlayerMobRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,
              new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), false), // wide
              SHADOW_RADIUS);

        // Bake both arm variants once. The wide model is the instance we just handed super
        // (now this.model / getModel()); the slim model is swapped in per-frame by submit().
        this.wideModel = this.getModel();
        this.slimModel = new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true); // slim

        // Armor layer — 26.x builds it from a baked ArmorModelSet (inner+outer humanoid armor)
        // plus the shared EquipmentLayerRenderer. PLAYER_ARMOR is the wide player armor set —
        // vanilla draws that same armor on slim-armed players too.
        this.addLayer(new HumanoidArmorLayer<>(
            this,
            net.minecraft.client.renderer.entity.ArmorModelSet.bake(
                ModelLayers.PLAYER_ARMOR, ctx.getModelSet(), HumanoidModel::new),
            ctx.getEquipmentRenderer()));

        // Held-item layer — draws whatever the mob holds in mainhand + offhand.
        // PlayerModel implements ArmedModel so the layer knows where to anchor.
        this.addLayer(new ItemInHandLayer<>(this));
    }
    *///?} else {
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
    //?}

    //? if >=26 {
    /*// ---- 26.x EntityRenderState pipeline -------------------------------------------------
    // 1.21.2+ split entity rendering into a render-STATE system: per-frame entity data is
    // snapshotted in extractRenderState (the entity is gone from the draw call); drawing happens
    // in submit() reading the state. We mirror what the <26 render() did: pick the slim/wide model,
    // set crouch + arm poses, resolve the skin texture, and submit the Creative objective readout.

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(PlayerMobEntity entity, AvatarRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        // Humanoid fields (crouch, item-in-hand states, swim, etc.) — vanilla's shared extractor.
        HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTick, this.itemModelResolver);
        state.isCrouching = entity.isCrouching();
        // AvatarRenderState.skin drives the model's overlay-part visibility (hat/jacket/sleeves)
        // in PlayerModel.setupAnim; give it the default so all overlays show and nothing NPEs.
        // The actual rendered texture flows through getTextureLocation(state), not this field.
        state.skin = SKIN_FALLBACK;
        // Arm poses now live on the render state (ArmedEntityRenderState), not the model.
        applyArmPoses(entity, state);
        // Snapshot the resolved skin + arm model so the entity-free draw path can read them.
        rememberSlim(state, isSlimModel(entity));
        rememberTexture(state, resolveSkin(entity));
        // Creative objective readout, stashed per-state (see the readouts map).
        Minecraft mc = Minecraft.getInstance();
        boolean creative = mc.player != null && mc.player.isCreative();
        String readout = creative ? entity.getObjectivesReadout() : null;
        if (readout == null || readout.isEmpty()
                || state.distanceToCameraSq > MAX_READOUT_DISTANCE_SQR) {
            readouts.remove(state);
        } else {
            boolean focused = this.entityRenderDispatcher.crosshairPickEntity == entity;
            readouts.put(state, new ReadoutSnapshot(readout, focused));
        }
    }

    @Override
    public void submit(AvatarRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        // Pick this mob's arm model before the model + layers draw. getModel() backs the layers.
        this.model = isSlim(state) ? slimModel : wideModel;
        super.submit(state, poseStack, collector, camera);
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        Identifier texture = textureOf(state);
        return texture != null ? texture : SkinCompat.defaultTexture();
    }

    // Draws the name tag plus, for a Creative observer, the billboarded objective readout
    // beneath it — the 26.x equivalent of the old renderObjectiveReadout(). The ambient mob
    // shows the top objective; the crosshair-focused mob shows the whole goal stack.
    @Override
    protected void submitNameDisplay(AvatarRenderState state, PoseStack poseStack,
                                     SubmitNodeCollector collector, CameraRenderState camera) {
        super.submitNameDisplay(state, poseStack, collector, camera);
        ReadoutSnapshot snapshot = readouts.get(state);
        if (snapshot == null) {
            return;
        }
        Vec3 anchor = state.nameTagAttachment;
        if (anchor == null) {
            return;
        }
        Font font = this.getFont();
        Minecraft mc = Minecraft.getInstance();
        int bgColor = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        String[] lines = snapshot.text().split("\n");
        int count = snapshot.focused() ? lines.length : 1;

        poseStack.pushPose();
        poseStack.translate(anchor.x, anchor.y + 0.5, anchor.z);
        poseStack.mulPose(camera.orientation);
        poseStack.scale(0.025F, -0.025F, 0.025F);
        for (int i = 0; i < count; i++) {
            String line = lines[i];
            float x = -font.width(line) / 2.0F;
            float y = (i + 1) * (font.lineHeight + 1);
            int color = i == 0 ? 0xFFFFFFFF : 0xFFBFBFBF;
            net.minecraft.util.FormattedCharSequence seq =
                net.minecraft.util.FormattedCharSequence.forward(line, net.minecraft.network.chat.Style.EMPTY);
            // See-through backdrop pass, then the solid front pass — the vanilla name-tag look.
            collector.submitText(poseStack, x, y, seq, false,
                Font.DisplayMode.SEE_THROUGH, state.lightCoords, bgColor, 0x20FFFFFF, 0);
            collector.submitText(poseStack, x, y, seq, false,
                Font.DisplayMode.NORMAL, state.lightCoords, 0, color, 0);
        }
        poseStack.popPose();
    }

    // --- per-state side fields (AvatarRenderState can't carry custom fields; see readouts note) ---
    private static final net.minecraft.world.entity.player.PlayerSkin SKIN_FALLBACK =
        net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin();
    private final Map<AvatarRenderState, Identifier> stateTextures = new ConcurrentHashMap<>();
    private final Map<AvatarRenderState, Boolean> stateSlim = new ConcurrentHashMap<>();
    private void rememberTexture(AvatarRenderState s, Identifier t) { stateTextures.put(s, t); }
    private void rememberSlim(AvatarRenderState s, boolean slim) { stateSlim.put(s, slim); }
    private Identifier textureOf(AvatarRenderState s) { return stateTextures.get(s); }
    private boolean isSlim(AvatarRenderState s) { return Boolean.TRUE.equals(stateSlim.get(s)); }
    *///?} else {
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
    //?}

    /**
     * Draws the mob's current objective(s) as billboarded text just under where
     * its name tag sits — Creative only. Ambient mobs show the single top
     * objective; the mob under the player's crosshair shows the full goal stack.
     * The goal state is computed server-side and synced
     * ({@link PlayerMobEntity#getObjectivesReadout()}); this only formats it.
     */
    //? if <26 {
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

        //? if >=1.21.1 {
        Vec3 anchor = entity.getAttachments()
            .getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        if (anchor == null) {
            return;
        }
        //?} else {
        /*// 1.20.1 has no entity-attachment system. The poseStack here is already at
        // the entity's render origin (this runs inside the entity render call), so
        // the name-tag anchor is entity-LOCAL: straight up by getNameTagOffsetY()
        // (= bbHeight + 0.5), exactly the offset vanilla EntityRenderer.renderNameTag
        // translates by. x/z are 0 (the 1.21.1 NAME_TAG attachment resolves the same).
        Vec3 anchor = new Vec3(0.0, entity.getNameTagOffsetY(), 0.0);
        *///?}

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
    //?}

    /**
     * Mirror vanilla's player arm-pose logic so held items pose the way they do on a real
     * player — a blocking shield rises into the BLOCK pose, a drawn bow into BOW_AND_ARROW, a
     * charging/charged crossbow into CROSSBOW_*, etc. Faithful port of
     * {@code PlayerRenderer.setModelProperties} + {@code getArmPose}. Pre-26 the poses live on
     * the (shared) model and are set each frame; 26.x moved them onto the per-frame render state.
     */
    //? if >=26 {
    /*private static void applyArmPoses(PlayerMobEntity entity, AvatarRenderState state) {
        HumanoidModel.ArmPose mainPose = armPose(entity, InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose offPose = armPose(entity, InteractionHand.OFF_HAND);
        if (mainPose.isTwoHanded()) {
            offPose = entity.getOffhandItem().isEmpty()
                ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        if (entity.getMainArm() == HumanoidArm.RIGHT) {
            state.rightArmPose = mainPose;
            state.leftArmPose = offPose;
        } else {
            state.rightArmPose = offPose;
            state.leftArmPose = mainPose;
        }
    }
    *///?} else {
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
    //?}

    /** Per-hand arm pose, mirroring {@code PlayerRenderer.getArmPose} for a PlayerMob. */
    private static HumanoidModel.ArmPose armPose(PlayerMobEntity entity, InteractionHand hand) {
        ItemStack stack = entity.getItemInHand(hand);
        if (stack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        if (entity.getUsedItemHand() == hand && entity.getUseItemRemainingTicks() > 0) {
            // 26.x renamed UseAnim → ItemUseAnimation (same constant names); getUseAnimation()
            // returns the renamed type, so the enum reference is guarded.
            //? if >=26 {
            /*ItemUseAnimation useAnim = stack.getUseAnimation();
            if (useAnim == ItemUseAnimation.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (useAnim == ItemUseAnimation.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (useAnim == ItemUseAnimation.SPEAR) return HumanoidModel.ArmPose.SPEAR;
            if (useAnim == ItemUseAnimation.CROSSBOW) return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            if (useAnim == ItemUseAnimation.SPYGLASS) return HumanoidModel.ArmPose.SPYGLASS;
            if (useAnim == ItemUseAnimation.TOOT_HORN) return HumanoidModel.ArmPose.TOOT_HORN;
            if (useAnim == ItemUseAnimation.BRUSH) return HumanoidModel.ArmPose.BRUSH;
            *///?} else {
            UseAnim useAnim = stack.getUseAnimation();
            if (useAnim == UseAnim.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (useAnim == UseAnim.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (useAnim == UseAnim.SPEAR) return HumanoidModel.ArmPose.THROW_SPEAR;
            if (useAnim == UseAnim.CROSSBOW) return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            if (useAnim == UseAnim.SPYGLASS) return HumanoidModel.ArmPose.SPYGLASS;
            if (useAnim == UseAnim.TOOT_HORN) return HumanoidModel.ArmPose.TOOT_HORN;
            if (useAnim == UseAnim.BRUSH) return HumanoidModel.ArmPose.BRUSH;
            //?}
        } else if (!entity.swinging
                && stack.getItem() instanceof CrossbowItem
                && isChargedCrossbow(stack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    /** Charged-crossbow test — CHARGED_PROJECTILES component on 1.21.1, the NBT flag on 1.20.1 (see CrossbowCompat). */
    private static boolean isChargedCrossbow(ItemStack stack) {
        return CrossbowCompat.isCharged(stack);
    }

    //? if <26 {
    @Override
    public ResourceLocation getTextureLocation(PlayerMobEntity entity) {
        return resolveSkin(entity);
    }
    //?}

    /**
     * Resolve a PlayerMob's skin to a renderable texture — shared by
     * {@link #getTextureLocation} and the menu UI (so a relationship row can draw
     * a target PlayerMob's face). v2 prefers the Mojang URL skin (via
     * {@link PlayerMobSkinTextures}, which returns {@code DefaultPlayerSkin} while
     * the async fetch is in flight, then flips to the real texture once cached);
     * otherwise the bundled vanilla default keyed off SkinIndex (v1 behaviour),
     * clamped defensively to skin 0.
     */
    //? if >=26 {
    /*public static Identifier resolveSkin(PlayerMobEntity entity) {
    *///?} else {
    public static ResourceLocation resolveSkin(PlayerMobEntity entity) {
    //?}
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
        var table = entity.isSkinSlim() ? SKIN_TEXTURES_SLIM : SKIN_TEXTURES_WIDE;
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
            return PlayerMobSkinTextures.playerSkin(source.get().uuid(), source.get().name()).slim();
        }
        return entity.isSkinSlim();
    }

    /**
     * Builds the per-skin texture table for one arm-model folder ({@code "wide"}
     * or {@code "slim"}). Both folders ship every default name in the vanilla jar.
     */
    //? if >=26 {
    /*private static Identifier[] buildSkinTextures(String folder) {
    *///?} else {
    private static ResourceLocation[] buildSkinTextures(String folder) {
    //?}
        if (SKIN_NAMES.length != PlayerMobEntity.SKIN_COUNT) {
            throw new IllegalStateException(
                "SKIN_NAMES.length (" + SKIN_NAMES.length
                + ") must equal PlayerMobEntity.SKIN_COUNT ("
                + PlayerMobEntity.SKIN_COUNT + ")");
        }
        //? if >=26 {
        /*Identifier[] arr = new Identifier[SKIN_NAMES.length];
        *///?} else {
        ResourceLocation[] arr = new ResourceLocation[SKIN_NAMES.length];
        //?}
        for (int i = 0; i < SKIN_NAMES.length; i++) {
            arr[i] = RegistryCompat.id(
                "minecraft",
                "textures/entity/player/" + folder + "/" + SKIN_NAMES[i] + ".png");
        }
        return arr;
    }
}
