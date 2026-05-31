package games.brennan.playermob.entity;

import games.brennan.playermob.entity.goal.WeaponAwareAttackGoal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The PlayerMob entity. Player-shaped (rendered with vanilla PlayerModel via
 * the client renderer) but driven by a vanilla-mob {@link Monster} AI brain.
 *
 * <p>Combat is weapon-aware — see {@link WeaponAwareAttackGoal}. The mob
 * supports crossbows, bows, and any melee weapon (or fists) depending on
 * what's in its main hand at any given tick. To enable both crossbow and bow
 * paths, this class implements both {@link CrossbowAttackMob} (which extends
 * {@link RangedAttackMob}).</p>
 *
 * <p>Targets are driven by {@link Stance} (v1 ships exactly one). The
 * goal-selector predicate forwards to {@code stance.permitsTargeting(this, candidate)}
 * so new stances drop in by adding enum constants — no goal rewiring.</p>
 *
 * <p>Spawn: spawn egg + {@code /summon playermob:player_mob} only for v1.
 * No natural spawn, no raid hooks.</p>
 */
public class PlayerMobEntity extends Monster implements CrossbowAttackMob {

    private static final EntityDataAccessor<Integer> DATA_STANCE =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_IS_CHARGING_CROSSBOW =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.BOOLEAN);

    public PlayerMobEntity(EntityType<? extends PlayerMobEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Default attributes. Lower HP than a Pillager (Pillager: 24) because
     * PlayerMob spawns unarmoured by default; users equip armour via
     * {@code /item replace entity ... armor.chest with minecraft:iron_chestplate}.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.30)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STANCE, Stance.HOSTILE_TO_HOSTILE_MOBS.ordinal());
        builder.define(DATA_IS_CHARGING_CROSSBOW, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new WeaponAwareAttackGoal(this, 1.0, 8.0f));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, LivingEntity.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Stance-aware mob targeting. Predicate is re-evaluated each tick the
        // goal scans for targets, so changing stance updates targeting live.
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
            this,
            LivingEntity.class,
            10,               // randomInterval (ticks between scans)
            true,             // mustSee
            false,            // mustReach
            candidate -> getStance().permitsTargeting(this, candidate)));
    }

    // -- Stance accessors ---------------------------------------------------

    public Stance getStance() {
        return Stance.byOrdinal(this.entityData.get(DATA_STANCE));
    }

    public void setStance(Stance stance) {
        this.entityData.set(DATA_STANCE, stance.ordinal());
    }

    // -- Save / load --------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Stance", getStance().ordinal());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Stance.byOrdinal guards against missing/invalid stored values.
        setStance(Stance.byOrdinal(tag.getInt("Stance")));
    }

    // -- CrossbowAttackMob --------------------------------------------------

    /**
     * Returns whether the mob is currently charging a crossbow. Mirrors
     * {@code Pillager.isChargingCrossbow()} — NOT an interface override
     * (CrossbowAttackMob declares {@code setChargingCrossbow} only). The
     * renderer reads this to swap to the charging pose.
     */
    public boolean isChargingCrossbow() {
        return this.entityData.get(DATA_IS_CHARGING_CROSSBOW);
    }

    @Override
    public void setChargingCrossbow(boolean charging) {
        this.entityData.set(DATA_IS_CHARGING_CROSSBOW, charging);
    }

    @Override
    public void onCrossbowAttackPerformed() {
        // Reset the "no-action timeout" so the despawn-when-idle clock
        // restarts after firing. Mirrors Pillager.
        this.noActionTime = 0;
    }

    // -- RangedAttackMob (bow path) -----------------------------------------

    /**
     * Called by {@link net.minecraft.world.entity.ai.goal.RangedBowAttackGoal}
     * when the mob is holding a bow. For crossbows we go through
     * {@link CrossbowAttackMob#performCrossbowAttack(net.minecraft.world.entity.LivingEntity, float)}
     * via {@link CrossbowAttackGoal} → so this method services the bow path
     * only. Mirrors {@link net.minecraft.world.entity.monster.AbstractSkeleton#performRangedAttack}.
     */
    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        ItemStack mainhand = this.getMainHandItem();
        // Defensive: if a crossbow somehow lands here, route through the
        // crossbow flow rather than fizzling.
        if (mainhand.getItem() instanceof CrossbowItem) {
            this.performCrossbowAttack(this, 1.6F);
            return;
        }
        ItemStack arrowStack = this.getProjectile(mainhand);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, arrowStack, velocity, mainhand);

        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Arc compensation: lift Y by ~20% of horizontal distance.
        arrow.shoot(dx, dy + dist * 0.2, dz, 1.6F,
                    (float) (14 - this.level().getDifficulty().getId() * 4));

        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F,
                       1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    // -- Death sounds (player-like, mirrors villager-style "person dies" feel) -

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PILLAGER_DEATH;
    }

    // -- Type marker --------------------------------------------------------

    /**
     * Returning Enemy from {@code instanceof} checks is already provided
     * by the {@link Monster} superclass — this entity is automatically a
     * valid target for {@link NearestAttackableTargetGoal} filters that
     * test for {@link Enemy}.
     */
}
