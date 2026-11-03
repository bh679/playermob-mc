package games.brennan.playermob.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import games.brennan.playermob.entity.CarriedGear;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
//? if >=26 {
/*import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
*///?} else {
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//?}

/**
 * Draws the gear a PlayerMob carries in its backpack on its body — weapons crossed on its back,
 * digging tools at its thighs, a shield and a banner on its back, and a few arrows standing out of
 * a quiver — picked by {@link CarriedGear} on the server and synced. Each item rides a body part,
 * so it turns, crouches and swings with the mob.
 */
@Environment(EnvType.CLIENT)
//? if >=26 {
/*public final class CarriedGearLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

    public CarriedGearLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       AvatarRenderState state, float yRot, float xRot) {
        if (!(state instanceof PlayerMobModel.State mobState) || state.isInvisible) {
            return;
        }
        PlayerModel model = getParentModel();
        for (CarriedGear.Spot spot : CarriedGear.Spot.values()) {
            ItemStackRenderState item = mobState.carriedItems[spot.ordinal()];
            if (item.isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            placeAt(poseStack, model, PLACEMENTS[spot.ordinal()]);
            item.submit(poseStack, collector, packedLight, OverlayTexture.NO_OVERLAY, state.outlineColor);
            poseStack.popPose();
        }
        if (!mobState.quiverArrow.isEmpty()) {
            for (Placement arrow : QUIVER_ARROWS) {
                poseStack.pushPose();
                placeAt(poseStack, model, arrow);
                mobState.quiverArrow.submit(poseStack, collector, packedLight, OverlayTexture.NO_OVERLAY,
                    state.outlineColor);
                poseStack.popPose();
            }
        }
    }
*///?} else {
public final class CarriedGearLayer
        extends RenderLayer<PlayerMobEntity, PlayerModel<PlayerMobEntity>> {

    private static final ItemStack ARROW = new ItemStack(Items.ARROW);
    private static final ItemStack SPECTRAL_ARROW = new ItemStack(Items.SPECTRAL_ARROW);

    private final ItemInHandRenderer itemRenderer;

    public CarriedGearLayer(RenderLayerParent<PlayerMobEntity, PlayerModel<PlayerMobEntity>> parent,
                            ItemInHandRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       PlayerMobEntity mob, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (mob.isInvisible()) {
            return;
        }
        CarriedGear.Loadout gear = mob.getCarriedGear();
        PlayerModel<PlayerMobEntity> model = getParentModel();
        for (CarriedGear.Spot spot : CarriedGear.Spot.values()) {
            ItemStack stack = gear.at(spot);
            if (stack.isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            placeAt(poseStack, model, PLACEMENTS[spot.ordinal()]);
            itemRenderer.renderItem(mob, stack, ItemDisplayContext.FIXED, false, poseStack, buffer, packedLight);
            poseStack.popPose();
        }
        ItemStack arrow = switch (gear.quiver()) {
            case ARROWS -> ARROW;
            case SPECTRAL -> SPECTRAL_ARROW;
            case NONE -> ItemStack.EMPTY;
        };
        if (!arrow.isEmpty()) {
            for (Placement placement : QUIVER_ARROWS) {
                poseStack.pushPose();
                placeAt(poseStack, model, placement);
                itemRenderer.renderItem(mob, arrow, ItemDisplayContext.FIXED, false, poseStack, buffer,
                    packedLight);
                poseStack.popPose();
            }
        }
    }
//?}

    /** The body part an item rides. */
    enum Part { BODY, RIGHT_LEG, LEFT_LEG }

    /**
     * Where an item sits, relative to its part's pivot. Offsets are in blocks, in model space:
     * +X the mob's left, +Y down, +Z out of its back. Angles are degrees, applied after the item is
     * turned upright and to face out of the back: {@code tilt} spins it in the plane of the back
     * (sword sprites are drawn hilt bottom-left), {@code yaw} turns it about the vertical.
     */
    record Placement(Part part, float x, float y, float z, float tilt, float yaw, float scale) {}

    // FIXED-context items are half a block across; a back weapon wants to be about body length.
    private static final Placement[] PLACEMENTS = {
        // BACK_RIGHT: hilt over the right shoulder, blade down to the left hip.
        new Placement(Part.BODY, 0.0F, 0.36F, 0.155F, 180.0F, 0.0F, 1.05F),
        // BACK_LEFT: crossed over it, hilt over the left shoulder.
        new Placement(Part.BODY, 0.0F, 0.36F, 0.175F, 90.0F, 0.0F, 1.05F),
        // HIP_RIGHT: flat against the outside of the right thigh, head down.
        new Placement(Part.RIGHT_LEG, -0.15F, 0.16F, 0.0F, 135.0F, 90.0F, 0.8F),
        // HIP_LEFT: the mirror image on the left thigh.
        new Placement(Part.LEFT_LEG, 0.15F, 0.16F, 0.0F, 135.0F, -90.0F, 0.8F),
        // SHIELD: flat on the back, over the slung weapons.
        new Placement(Part.BODY, 0.0F, 0.38F, 0.22F, 0.0F, 0.0F, 1.0F),
        // BANNER: standing up behind the shoulders, over the shield.
        new Placement(Part.BODY, 0.0F, 0.14F, 0.27F, 0.0F, 0.0F, 0.8F),
    };

    // Three arrows fanned up out of a quiver behind the left shoulder.
    private static final Placement[] QUIVER_ARROWS = {
        new Placement(Part.BODY, 0.07F, 0.12F, 0.16F, 45.0F, 0.0F, 0.75F),
        new Placement(Part.BODY, 0.09F, 0.12F, 0.165F, 35.0F, 0.0F, 0.75F),
        new Placement(Part.BODY, 0.05F, 0.12F, 0.17F, 55.0F, 0.0F, 0.75F),
    };

    //? if >=26 {
    /*private static void placeAt(PoseStack poseStack, PlayerModel model, Placement placement) {
    *///?} else {
    private static void placeAt(PoseStack poseStack, PlayerModel<PlayerMobEntity> model, Placement placement) {
    //?}
        ModelPart part = switch (placement.part()) {
            case BODY -> model.body;
            case RIGHT_LEG -> model.rightLeg;
            case LEFT_LEG -> model.leftLeg;
        };
        part.translateAndRotate(poseStack);
        poseStack.translate(placement.x(), placement.y(), placement.z());
        // Model space is upside down; turn the item upright, then to face out of the back.
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F + placement.yaw()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(placement.tilt()));
        poseStack.scale(placement.scale(), placement.scale(), placement.scale());
    }
}
