package games.brennan.playermob.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;
//? if >=26 {
/*import games.brennan.playermob.entity.MobActivity;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
*///?} else {
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
//?}

/**
 * The vanilla player model plus one extra: after vanilla has posed the body, blend in the
 * "busy at a screen" pose ({@link ActivityPose}) while the mob has a container open or is going
 * through its gear. Everything else — walk cycle, attack swing, bow / shield arm poses — is
 * vanilla's, untouched when the activity blend is zero.
 */
@Environment(EnvType.CLIENT)
//? if >=26 {
/*public final class PlayerMobModel extends PlayerModel {

    // Render state for a PlayerMob: the vanilla avatar state plus the activity snapshot taken in
    // extractRenderState. PlayerModel and the armor / held-item layers only see the supertype;
    // this model and ActivityPanelLayer read the extras back by downcast.
    public static final class State extends AvatarRenderState {
        public float activityBlend;
        public MobActivity shownActivity = MobActivity.NONE;
    }

    public PlayerMobModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(AvatarRenderState state) {
        super.setupAnim(state);
        if (state instanceof State mobState && mobState.activityBlend > 0.0F) {
            applyActivityPose(mobState.activityBlend, state.ageInTicks,
                state.mainArm == HumanoidArm.RIGHT);
        }
    }
*///?} else {
public final class PlayerMobModel<T extends LivingEntity> extends PlayerModel<T> {

    public PlayerMobModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        if (!(entity instanceof PlayerMobEntity mob)) {
            return;
        }
        // ageInTicks is tickCount + partialTick — recover the partial for a smooth ease.
        float partialTick = Math.max(0.0F, Math.min(1.0F, ageInTicks - mob.tickCount));
        float blend = mob.getActivityBlend(partialTick);
        if (blend <= 0.0F) {
            return;
        }
        applyActivityPose(blend, ageInTicks, mob.getMainArm() == HumanoidArm.RIGHT);
        // Vanilla already copied the overlay parts from the base parts; re-copy what we moved.
        this.hat.copyFrom(this.head);
        this.rightSleeve.copyFrom(this.rightArm);
        this.leftSleeve.copyFrom(this.leftArm);
    }
//?}

    private void applyActivityPose(float blend, float ageInTicks, boolean rightHanded) {
        ModelPart mainArm = rightHanded ? this.rightArm : this.leftArm;
        ModelPart offArm = rightHanded ? this.leftArm : this.rightArm;
        this.head.xRot = ActivityPose.headPitch(blend, this.head.xRot);
        this.head.yRot = ActivityPose.headYaw(blend, this.head.yRot);
        mainArm.xRot = ActivityPose.mainArmPitch(blend, mainArm.xRot, ageInTicks);
        mainArm.yRot = ActivityPose.mainArmYaw(blend, mainArm.yRot, ageInTicks, rightHanded);
        mainArm.zRot = ActivityPose.lerp(blend, mainArm.zRot, 0.0F);
        offArm.xRot = ActivityPose.offArmPitch(blend, offArm.xRot);
        offArm.yRot = ActivityPose.offArmYaw(blend, offArm.yRot, !rightHanded);
    }
}
