package games.brennan.playermob.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.compat.RegistryCompat;
import games.brennan.playermob.entity.MobActivity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if >=26 {
/*import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
*///?} else {
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * Draws the small floating panel a PlayerMob is "looking at" while it has a container open or is
 * going through its gear — the screen its raised arm is picking through (see {@link ActivityPose}).
 * A single full-bright textured quad at arm's reach in front of the chest; it rides the body's
 * model space, so it turns with the mob, and scales in and out with the activity blend.
 */
@Environment(EnvType.CLIENT)
//? if >=26 {
/*public final class ActivityPanelLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

    private static final Identifier CONTAINER_TEXTURE = texture("container");
    private static final Identifier INVENTORY_TEXTURE = texture("inventory");
    private static final int FULL_BRIGHT = LightCoordsUtil.FULL_BRIGHT;

    public ActivityPanelLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       AvatarRenderState state, float yRot, float xRot) {
        if (!(state instanceof PlayerMobModel.State mobState)) {
            return;
        }
        Identifier texture = textureFor(mobState.shownActivity);
        float blend = mobState.activityBlend;
        if (texture == null || blend <= 0.0F) {
            return;
        }
        poseStack.pushPose();
        positionPanel(poseStack, blend);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture),
            (pose, consumer) -> drawQuad(pose, consumer));
        poseStack.popPose();
    }

    private static Identifier textureFor(MobActivity activity) {
*///?} else {
public final class ActivityPanelLayer
        extends RenderLayer<PlayerMobEntity, PlayerModel<PlayerMobEntity>> {

    private static final ResourceLocation CONTAINER_TEXTURE = texture("container");
    private static final ResourceLocation INVENTORY_TEXTURE = texture("inventory");
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public ActivityPanelLayer(RenderLayerParent<PlayerMobEntity, PlayerModel<PlayerMobEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       PlayerMobEntity mob, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ResourceLocation texture = textureFor(mob.getShownActivity());
        float blend = mob.getActivityBlend(partialTick);
        if (texture == null || blend <= 0.0F) {
            return;
        }
        poseStack.pushPose();
        positionPanel(poseStack, blend);
        drawQuad(poseStack.last(), buffer.getBuffer(RenderType.entityCutoutNoCull(texture)));
        poseStack.popPose();
    }

    private static ResourceLocation textureFor(MobActivity activity) {
//?}
        return switch (activity) {
            case CONTAINER -> CONTAINER_TEXTURE;
            case INVENTORY -> INVENTORY_TEXTURE;
            case NONE -> null;
        };
    }

    // Model space here is the humanoid model's: origin at the neck, +Y down toward the feet,
    // -Z out in front of the chest, one unit = one block.

    /** Panel centre below the neck — chest height, where the raised hand ends up. */
    private static final float PANEL_DOWN = 0.32F;
    /** Panel centre in front of the body — just past the raised hand. */
    private static final float PANEL_FORWARD = 0.82F;
    /** Half the panel's edge length, in blocks. */
    private static final float PANEL_HALF_SIZE = 0.27F;
    /** Lean of the panel's top edge away from the mob, in degrees. */
    private static final float PANEL_TILT_DEGREES = 12.0F;

    private static void positionPanel(PoseStack poseStack, float blend) {
        poseStack.translate(0.0F, PANEL_DOWN, -PANEL_FORWARD);
        poseStack.mulPose(Axis.XP.rotationDegrees(PANEL_TILT_DEGREES));
        poseStack.scale(blend, blend, blend);
    }

    private static void drawQuad(PoseStack.Pose pose, VertexConsumer consumer) {
        float s = PANEL_HALF_SIZE;
        // +X is the mob's left; U runs left-to-right as the mob sees its own panel.
        vertex(pose, consumer, s, -s, 0.0F, 0.0F);
        vertex(pose, consumer, s, s, 0.0F, 1.0F);
        vertex(pose, consumer, -s, s, 1.0F, 1.0F);
        vertex(pose, consumer, -s, -s, 1.0F, 0.0F);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer,
                               float x, float y, float u, float v) {
        //? if >=1.21.1 {
        consumer.addVertex(pose, x, y, 0.0F)
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(FULL_BRIGHT)
            .setNormal(pose, 0.0F, 0.0F, 1.0F);
        //?} else {
        /*consumer.vertex(pose.pose(), x, y, 0.0F)
            .color(255, 255, 255, 255)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(FULL_BRIGHT)
            .normal(pose.normal(), 0.0F, 0.0F, 1.0F)
            .endVertex();
        *///?}
    }

    //? if >=26 {
    /*private static Identifier texture(String name) {
    *///?} else {
    private static ResourceLocation texture(String name) {
    //?}
        return RegistryCompat.id(PlayerMob.MOD_ID, "textures/entity/activity/" + name + ".png");
    }
}
