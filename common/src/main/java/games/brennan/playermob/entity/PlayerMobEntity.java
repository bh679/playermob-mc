package games.brennan.playermob.entity;

import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.goal.CollectFloorItemsGoal;
import games.brennan.playermob.entity.goal.EatFoodGoal;
import games.brennan.playermob.entity.goal.FleeFromCategoryGoal;
import games.brennan.playermob.entity.goal.FriendlyGreetGoal;
import games.brennan.playermob.entity.goal.HarvestCropsGoal;
import games.brennan.playermob.entity.goal.HuntForFoodGoal;
import games.brennan.playermob.entity.goal.PlayerMobDoorGoal;
import games.brennan.playermob.entity.goal.RaidArmorStandsGoal;
import games.brennan.playermob.entity.goal.RaidContainersGoal;
import games.brennan.playermob.entity.goal.SkepticalWatchGoal;
import games.brennan.playermob.entity.goal.WeaponAwareAttackGoal;
import games.brennan.playermob.skin.PlayerMobSkin;
import games.brennan.playermob.skin.PlayerMobSkinRegistry;
import games.brennan.playermob.skin.SkinModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PlayerMob entity. Player-shaped (rendered with vanilla PlayerModel via
 * the client renderer) but driven by a vanilla-mob {@link PathfinderMob} AI brain.
 *
 * <p><b>Combat</b> is weapon-aware — see {@link WeaponAwareAttackGoal}. The
 * mob supports crossbows, bows, and any melee weapon (or fists) depending on
 * what's in its main hand at any given tick. Implements both
 * {@link CrossbowAttackMob} (which extends {@link RangedAttackMob}) for
 * vanilla ranged-goal compatibility.</p>
 *
 * <p><b>Personalities</b> ({@link PersonalityProfile}) drive how the mob
 * treats each {@link TargetCategory} of entity (players, hostile mobs, animals,
 * villagers) — Aggressive, Friendly, Passive, Skeptical, or Shy. The
 * target-selector attacks anything the mob is {@link Personality#AGGRESSIVE}
 * toward; {@link FleeFromCategoryGoal}, {@link SkepticalWatchGoal}, and
 * {@link FriendlyGreetGoal} cover the rest and self-gate on the live
 * personality (a runtime flip via {@link #hurt} re-wires nothing). Server-side
 * only; the crouch gesture rides the vanilla-synced pose ({@link #setCrouching}).</p>
 *
 * <p><b>Skins</b> — Each mob rolls its look in {@link #finalizeSpawn}. It
 * always rolls a bundled-vanilla index in {@code [0, SKIN_COUNT)}, then with
 * probability {@link #URL_SKIN_CHANCE} (~30%) overrides it with a Mojang skin
 * texture URL drawn from the datapack-extensible {@link PlayerMobSkinRegistry}.
 * So ~70% of mobs wear a vanilla default and ~30% wear a recognisable
 * real-player skin. The client renderer ({@code PlayerMobRenderer}, not
 * imported here to keep this class server-loadable) prefers the URL when set
 * and falls back to the bundled index otherwise. Both fields persist across
 * save/load; the URL field is purely additive on top of the v1 (0.2.0) save
 * format.</p>
 *
 * <p><b>Inventory raiding (v1.5)</b> — Implements {@link InventoryCarrier}
 * so the mob has a backpack. {@link RaidContainersGoal} +
 * {@link RaidArmorStandsGoal} drive scan/path/swap behaviour. A
 * "recently explored" cooldown map keeps the mob from looping the same
 * chest. On death, the mob drops everything like a player would — the
 * backpack via {@link #dropCustomDeathLoot} and all equipped gear via the
 * guaranteed {@link #getEquipmentDropChance} override.</p>
 *
 * <p><b>Doors</b> — the navigation has {@code setCanOpenDoors(true)} so the mob
 * paths through closed wooden doors, and {@link PlayerMobDoorGoal} opens them on
 * approach. Each mob rolls a {@link #closesDoors} personality at spawn: half
 * close the door behind themselves, half leave it open.</p>
 *
 * <p><b>Spawning</b> — spawn egg + {@code /summon playermob:player_mob}
 * only. No natural spawns, no raid hooks.</p>
 */
public class PlayerMobEntity extends PathfinderMob implements CrossbowAttackMob, InventoryCarrier {

    // ---- DataTracker ------------------------------------------------------

    private static final EntityDataAccessor<Boolean> DATA_IS_CHARGING_CROSSBOW =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Integer> DATA_SKIN_INDEX =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.INT);

    /**
     * Mojang skin texture URL ({@code http://textures.minecraft.net/texture/&lt;hash&gt;}).
     * Empty string ⇒ no URL skin assigned — the renderer falls back to the
     * legacy bundled-vanilla texture indexed by {@link #DATA_SKIN_INDEX}.
     */
    private static final EntityDataAccessor<String> DATA_SKIN_TEXTURE_URL =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.STRING);

    /**
     * Slim-arms flag (true = 3-pixel arms, like the Alex model). v2 always
     * renders wide regardless; the flag is plumbed for v3 model-swap support.
     */
    private static final EntityDataAccessor<Boolean> DATA_SKIN_SLIM =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.BOOLEAN);

    // ---- Constants --------------------------------------------------------

    /**
     * Number of player skins the renderer chooses from. Defined here (not
     * on the client-only renderer) so server-side {@code finalizeSpawn} can
     * roll an index without triggering load of the {@code @Environment(CLIENT)}
     * renderer class — would otherwise NoClassDefFoundError on dedicated
     * server boot.
     *
     * <p>Maps to the 9 default Minecraft player skins shipped in the vanilla
     * client jar at {@code assets/minecraft/textures/entity/player/wide/<name>.png}
     * — see {@code PlayerMobRenderer.SKIN_NAMES} for the canonical order.
     * SkinIndexTest verifies the corresponding vanilla PNGs exist on the
     * classpath.</p>
     */
    public static final int SKIN_COUNT = 9;

    /**
     * Probability a freshly-spawned mob uses a Mojang URL skin from the
     * datapack-fed {@link PlayerMobSkinRegistry} instead of one of the
     * {@link #SKIN_COUNT} bundled vanilla default skins.
     *
     * <p>{@code 0.30f} ⇒ ~30% real-player skins, ~70% vanilla defaults. The
     * vanilla defaults stay the common case so the mob reads as "a player"
     * at a glance, with recognisable real skins as the occasional standout.
     * Only consulted when the registry is non-empty — an empty registry
     * always falls back to a bundled skin regardless of this roll.</p>
     */
    private static final float URL_SKIN_CHANCE = 0.30f;

    /** Backpack size — matches Pillager (5) plus a little extra. */
    private static final int INVENTORY_SIZE = 8;

    /** Recently-explored entries expire after this many ticks (60 seconds). */
    private static final long RECENTLY_EXPLORED_TTL_TICKS = 1200L;

    /**
     * Chest {@code triggerEvent} ID for "viewer count changed" — drives the
     * lid animation. See {@link ChestBlockEntity#triggerEvent}.
     */
    private static final int CHEST_VIEWERS_EVENT = 1;

    private static final String TAG_SKIN_INDEX = "SkinIndex";
    private static final String TAG_SKIN_TEXTURE_URL = "SkinTextureUrl";
    private static final String TAG_SKIN_SLIM = "SkinSlim";
    private static final String TAG_CLOSES_DOORS = "ClosesDoors";
    private static final String TAG_EXPLORED_BLOCKS = "ExploredBlocks";
    private static final String TAG_EXPLORED_ENTITIES = "ExploredEntities";
    private static final String TAG_POS = "Pos";
    private static final String TAG_UUID = "UUID";
    private static final String TAG_TICK = "Tick";

    // ---- Fields -----------------------------------------------------------

    /**
     * Per-category personalities (who the mob attacks / greets / flees /
     * watches). Server-side only. Rolled at spawn, set by spawn eggs /
     * {@code /summon}, persisted to NBT, and flipped by {@link #hurt}.
     */
    private final PersonalityProfile personalities = new PersonalityProfile();

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    /**
     * BlockPos packed long → last-visited tickCount. Sweep on each scan;
     * raid goal calls {@link #isBlockExplored} + {@link #markBlockExplored}.
     */
    private final Map<Long, Long> recentlyExploredBlocks = new HashMap<>();

    /**
     * ArmorStand UUID → last-visited tickCount. Same sweep semantics as the
     * block map.
     */
    private final Map<UUID, Long> recentlyExploredEntities = new HashMap<>();

    /**
     * Position of the container this mob currently has visually "open" (chest
     * lid animated, or barrel block-state set to OPEN). {@code null} when the
     * mob is not actively looting a container.
     *
     * <p>Tracked here (not in the raid goal) so the entity's {@link #die}
     * override can force-close the container when the mob is killed mid-raid
     * — the goal's {@code stop()} doesn't reliably fire on death because the
     * entity may be removed before the goal selector ticks again.</p>
     */
    private BlockPos openContainerPos;

    /**
     * Per-mob "tidiness" personality, rolled 50/50 at spawn (see
     * {@link #finalizeSpawn}) and persisted. {@code true} ⇒ this mob closes
     * wooden doors behind itself (villager-like); {@code false} ⇒ it leaves
     * them open (raider-like). Read every tick by {@link PlayerMobDoorGoal}.
     *
     * <p>Server-only AI state — never rendered — so it's a plain field with NBT
     * persistence rather than a synched DataTracker entry.</p>
     */
    private boolean closesDoors;

    public PlayerMobEntity(EntityType<? extends PlayerMobEntity> type, Level level) {
        super(type, level);
        // Preserve combat-kill XP parity. Monster's constructor sets xpReward=5;
        // PathfinderMob's does not, so without this killing a PlayerMob would
        // drop 0 XP. The base-class swap (Monster -> PathfinderMob) is purely to
        // shed the Enemy marker so iron golems ignore it — XP-on-kill is not part
        // of that intended change, so restore it explicitly.
        this.xpReward = 5;
        // Enable vanilla's passive proximity pickup (Mob.aiStep). The active
        // CollectFloorItemsGoal does the seeking; this catches items underfoot.
        this.setCanPickUpLoot(true);
        // Route pathfinding through closed wooden doors (passing through *open*
        // doors is already on by default). PlayerMobDoorGoal does the opening;
        // without this flag DoorInteractGoal.canUse() never fires.
        if (this.getNavigation() instanceof GroundPathNavigation groundNav) {
            groundNav.setCanOpenDoors(true);
        }
    }

    /**
     * Default attributes. Lower HP than a Pillager (Pillager: 24) because
     * PlayerMob spawns unarmoured by default; users equip armour via
     * {@code /item replace entity ... armor.chest with minecraft:iron_chestplate}.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.30)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_IS_CHARGING_CROSSBOW, false);
        builder.define(DATA_SKIN_INDEX, 0);
        builder.define(DATA_SKIN_TEXTURE_URL, "");
        builder.define(DATA_SKIN_SLIM, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Personality social goals — priority 1 so they preempt raiding/strolling
        // when their disposition applies. Each self-gates on the live personality;
        // Skeptical/Friendly also gate on "no target" so they yield to combat.
        this.goalSelector.addGoal(1, new FleeFromCategoryGoal(this, /* range */ 10.0F, /* walk */ 1.0, /* sprint */ 1.3));
        this.goalSelector.addGoal(1, new SkepticalWatchGoal(this, /* watchRange */ 10.0, /* closeRange */ 4.0));
        this.goalSelector.addGoal(1, new FriendlyGreetGoal(this, /* range */ 10.0, /* approachSpeed */ 0.9));
        // Open (and, for "tidy" mobs, close) wooden doors on the path. Declares
        // no flags, so it runs alongside whatever movement goal owns the walk.
        this.goalSelector.addGoal(1, new PlayerMobDoorGoal(this));
        this.goalSelector.addGoal(2, new WeaponAwareAttackGoal(this, 1.0, 8.0f));
        // EatFoodGoal added BEFORE the raid goals at the same priority so
        // its canUse() is evaluated first — a low-HP mob with food prefers
        // eating over walking to the next chest.
        this.goalSelector.addGoal(3, new EatFoodGoal(this));
        this.goalSelector.addGoal(3, new RaidContainersGoal(this, /* speed */ 0.9, /* radius */ 12));
        this.goalSelector.addGoal(3, new RaidArmorStandsGoal(this, /* speed */ 0.9, /* radius */ 12.0));
        this.goalSelector.addGoal(3, new CollectFloorItemsGoal(this, /* speed */ 0.9, /* radius */ 8.0));
        // Low-priority idle forage drive: only farms ripe crops when there's
        // nothing more urgent (combat 2, raid/eat/collect 3) to do. Hunting is
        // NOT here — it runs as a target goal so the priority-2 attack goal does
        // the killing (see below).
        this.goalSelector.addGoal(6, new HarvestCropsGoal(this, /* speed */ 0.9, /* radius */ 8));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, LivingEntity.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
            this,
            LivingEntity.class,
            10,
            true,
            false,
            candidate -> personalityToward(candidate) == Personality.AGGRESSIVE
                && TrainConfinement.allowsTarget(this, candidate)));
        // Hunt food animals only while hungry, and below the hostile-targeting
        // goal (2) so defending against a zombie always beats chasing a cow.
        this.targetSelector.addGoal(3, new HuntForFoodGoal(this));
    }

    /**
     * When standing on a Dungeon Train carriage, never hold an attack target that
     * has left the train. Acquisition is already filtered (the priority-2 target
     * goal and {@link HuntForFoodGoal}); this central sweep additionally drops a
     * target that <em>wanders</em> off, covering every target source (hostile,
     * retaliation, hunt) in one place — clearing it also makes the priority-2
     * {@code WeaponAwareAttackGoal} stand down. No-op unless a train mod is
     * installed and the mob is actually on a train: {@link
     * TrainConfinement#allowsTarget} short-circuits to {@code true} otherwise.
     */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity target = getTarget();
        if (target != null && !TrainConfinement.allowsTarget(this, target)) {
            setTarget(null);
        }
    }

    /**
     * Roll the random skin at spawn so all clients see the same value.
     * Server-side; syncs to clients via SynchedEntityData.
     *
     * <p>Always rolls a bundled-vanilla index first — it's both the ~70%
     * common case (see {@link #URL_SKIN_CHANCE}) and the downgrade-safe
     * payload for 0.2.0 clients. Then, with probability
     * {@link #URL_SKIN_CHANCE}, overrides it with a Mojang URL skin from
     * {@link PlayerMobSkinRegistry}. The other ~70% keep the bundled
     * vanilla skin (URL left blank ⇒ renderer uses the index path). An
     * empty registry always falls through to the bundled skin.</p>
     */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor world,
                                        DifficultyInstance difficulty,
                                        MobSpawnType reason,
                                        SpawnGroupData data) {
        setSkinIndex(world.getRandom().nextInt(SKIN_COUNT));
        if (world.getRandom().nextFloat() < URL_SKIN_CHANCE) {
            PlayerMobSkinRegistry.pickRandom(world.getRandom()).ifPresent(skin -> {
                setSkinTextureUrl(skin.textureUrl());
                setSkinSlim(skin.model() == SkinModel.SLIM);
            });
        }
        // Randomise any personality categories not pinned by a spawn egg's
        // entity_data or /summon NBT (player-facing eggs leave PLAYERS set).
        personalities.rollUnsetRandom(world.getRandom());
        // Roll the door-closing personality (~50% close behind, ~50% leave open).
        this.closesDoors = world.getRandom().nextBoolean();
        return super.finalizeSpawn(world, difficulty, reason, data);
    }

    // ---- Despawn / persistence -------------------------------------------

    /**
     * PlayerMobs never despawn naturally. Returning {@code true} makes
     * {@code Mob.checkDespawn()} treat every PlayerMob as persistent — skipping
     * both the &gt;128-block instant despawn and the 32–128-block idle random
     * despawn, as if the mob were name-tagged — without writing any NBT, so it
     * applies to summoned, spawn-egg, and already-saved mobs alike.
     *
     * <p>Does not affect the Peaceful-difficulty check: like all monsters,
     * PlayerMobs are still removed when difficulty is set to Peaceful.</p>
     */
    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    // ---- Personality accessors + behaviour helpers ------------------------

    /** The mob's personality toward a live entity, or {@code null} if uncategorised. */
    public Personality personalityToward(LivingEntity entity) {
        return personalities.personalityToward(entity);
    }

    /**
     * Nearest living entity within {@code range} that the mob holds the given
     * {@code personality} toward. Used by the social goals to find their subject.
     */
    public LivingEntity nearestWithPersonality(Personality personality, double range) {
        AABB box = getBoundingBox().inflate(range);
        LivingEntity closest = null;
        double closestSq = range * range;
        List<LivingEntity> candidates = level().getEntitiesOfClass(
            LivingEntity.class, box,
            e -> e != this && e.isAlive() && personalities.personalityToward(e) == personality);
        for (LivingEntity e : candidates) {
            double distSq = distanceToSqr(e);
            if (distSq < closestSq) {
                closestSq = distSq;
                closest = e;
            }
        }
        return closest;
    }

    /** True if the main hand holds a recognised weapon (drives the provoked fight/flee choice). */
    public boolean isArmed() {
        return isWeapon(getMainHandItem());
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
            || stack.getItem() instanceof AxeItem
            || stack.getItem() instanceof TridentItem
            || stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem
            || stack.getItem() instanceof MaceItem;
    }

    /**
     * Skeptical "ready a weapon": if the main hand is empty, move the first
     * weapon found in the backpack into it. No-op if already armed or none found.
     */
    public void drawWeaponFromBackpack() {
        if (!getMainHandItem().isEmpty()) return;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isWeapon(stack)) {
                setItemSlot(EquipmentSlot.MAINHAND, inventory.removeItemNoUpdate(i));
                return;
            }
        }
    }

    /** Skeptical "raise shield": start using a shield held in either hand. */
    public void raiseShieldIfHeld() {
        if (isUsingItem()) return;
        if (getOffhandItem().is(Items.SHIELD)) {
            startUsingItem(InteractionHand.OFF_HAND);
        } else if (getMainHandItem().is(Items.SHIELD)) {
            startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    /** Stop holding up a shield (reverse of {@link #raiseShieldIfHeld}). */
    public void lowerShield() {
        if (isUsingItem() && getUseItem().is(Items.SHIELD)) {
            stopUsingItem();
        }
    }

    /**
     * Crouch gesture for the Friendly greeting and Shy hiding. The
     * {@code PlayerModel} renders the sneak pose from {@link #isCrouching()}
     * (i.e. {@link #getPose()} {@code == CROUCHING}), not the sneak flag — so we
     * set the pose. Both are set so flag-driven bits stay consistent. Visual-only
     * (no per-pose dimensions); {@code setPose} no-ops when unchanged.
     */
    public void setCrouching(boolean crouching) {
        setShiftKeyDown(crouching);
        setPose(crouching ? Pose.CROUCHING : Pose.STANDING);
    }

    /**
     * Snap the body (and head) to face {@code target}. Vanilla shield blocking
     * only deflects hits from the facing direction, so a Skeptical mob holding
     * its shield up must square up to the threat for the block to count.
     */
    public void faceBodyToward(LivingEntity target) {
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
    }

    /**
     * Friendly "gift": toss an item arcing toward {@code target} from eye height
     * like a player's Q-drop. Prefers a looted backpack item; offers a small
     * default gift if the backpack is empty so the gesture always lands.
     */
    public boolean giveItemTo(LivingEntity target) {
        ItemStack gift = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                gift = inventory.removeItemNoUpdate(i);
                break;
            }
        }
        if (gift.isEmpty()) {
            gift = defaultGift();
        }
        double fromX = getX();
        double fromY = getEyeY() - 0.1;
        double fromZ = getZ();
        ItemEntity thrown = new ItemEntity(level(), fromX, fromY, fromZ, gift);
        double dx = target.getX() - fromX;
        double dy = (target.getY() + target.getBbHeight() * 0.5) - fromY;
        double dz = target.getZ() - fromZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        Vec3 velocity = new Vec3(dx, dy + horizontal * 0.15, dz).normalize().scale(0.45);
        thrown.setDeltaMovement(velocity);
        thrown.setPickUpDelay(10);
        level().addFreshEntity(thrown);
        return true;
    }

    /** A small, friendly token gift for when the backpack has nothing looted yet. */
    private ItemStack defaultGift() {
        return switch (getRandom().nextInt(5)) {
            case 0 -> new ItemStack(Items.POPPY);
            case 1 -> new ItemStack(Items.DANDELION);
            case 2 -> new ItemStack(Items.BREAD);
            case 3 -> new ItemStack(Items.APPLE);
            default -> new ItemStack(Items.COOKIE);
        };
    }

    /** Whether world-griefing (and thus chest raiding) is permitted here. */
    public boolean canRaid() {
        return level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    /**
     * True if a lootable chest/barrel not recently raided sits within {@code radius}.
     * Lets the Shy flee goal brave a sneak-raid instead of fleeing — once looted the
     * chest is marked explored, so this returns false and the mob flees.
     */
    public boolean hasRaidableContainerNearby(int radius) {
        BlockPos origin = blockPosition();
        long now = tickCount;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockEntity be = level().getBlockEntity(cursor);
                    if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)
                            && !isBlockExplored(cursor, now)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- Skin accessors ---------------------------------------------------

    /**
     * Returns the bundled-skin index. Clamped on read so a corrupt save can't
     * crash the renderer.
     */
    public int getSkinIndex() {
        int raw = this.entityData.get(DATA_SKIN_INDEX);
        if (raw < 0 || raw >= SKIN_COUNT) {
            return 0;
        }
        return raw;
    }

    public void setSkinIndex(int index) {
        // Clamp to valid range — defensive against /summon SkinIndex:99.
        int clamped = (index < 0 || index >= SKIN_COUNT) ? 0 : index;
        this.entityData.set(DATA_SKIN_INDEX, clamped);
    }

    /**
     * Mojang skin texture URL, or empty string if this mob has no URL skin
     * assigned. {@code null} is coerced to {@code ""} on set.
     */
    public String getSkinTextureUrl() {
        String raw = this.entityData.get(DATA_SKIN_TEXTURE_URL);
        return raw == null ? "" : raw;
    }

    public void setSkinTextureUrl(String url) {
        this.entityData.set(DATA_SKIN_TEXTURE_URL, url == null ? "" : url);
    }

    /**
     * True if the assigned URL skin was authored for the slim-arms model.
     * v2 always renders wide regardless — see renderer for details.
     */
    public boolean isSkinSlim() {
        return this.entityData.get(DATA_SKIN_SLIM);
    }

    public void setSkinSlim(boolean slim) {
        this.entityData.set(DATA_SKIN_SLIM, slim);
    }

    // ---- Door behaviour ---------------------------------------------------

    /**
     * Whether this mob closes wooden doors behind itself. Read each tick by
     * {@link PlayerMobDoorGoal}; rolled at spawn and persisted. See
     * {@link #closesDoors}.
     */
    public boolean closesDoors() {
        return this.closesDoors;
    }

    // ---- InventoryCarrier ------------------------------------------------

    @Override
    public SimpleContainer getInventory() {
        return this.inventory;
    }

    // ---- Interaction (Creative inventory access) -------------------------

    /**
     * Right-click handler. In Creative, an empty-handed main-hand right-click
     * opens the mob's inventory screen (equipment + backpack) — the mob
     * equivalent of pressing E. Survival players, or anyone holding an item,
     * fall through to vanilla behaviour so normal interactions are unchanged.
     *
     * <p>The menu must be opened server-side (the backpack isn't synced to
     * clients otherwise); the loader-specific open call lives behind
     * {@link PlayerMobRegistry#MENU_OPENER}. {@code sidedSuccess} swings the
     * player's arm on the client and consumes the interaction on the server.</p>
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND
                && player.getAbilities().instabuild
                && player.getItemInHand(hand).isEmpty()) {
            if (player instanceof ServerPlayer serverPlayer
                    && PlayerMobRegistry.MENU_OPENER != null) {
                PlayerMobRegistry.MENU_OPENER.open(serverPlayer, this);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    // ---- Equipment swap helpers (called from raid goals) -----------------

    /**
     * Replace vanilla's comparator with the enchantment-aware
     * {@link EquipmentEvaluator#shouldReplace} so an iron sword with
     * Sharpness V can win against a plain diamond sword. Vanilla's
     * implementation in {@link net.minecraft.world.entity.Mob} short-circuits
     * on base damage and never sees the enchantment difference.
     *
     * <p>Overriding here propagates the heuristic to vanilla pickup paths
     * ({@code Mob.pickUpItem}, the ground-item pickup checks) in addition to
     * the raid goals' explicit {@link #wouldTakeFromContainer} calls.</p>
     */
    @Override
    protected boolean canReplaceCurrentItem(ItemStack candidate, ItemStack existing) {
        return EquipmentEvaluator.shouldReplace(candidate, existing);
    }

    /**
     * Try to take a candidate item from a container slot. Two paths:
     *
     * <ul>
     *   <li><b>Food</b> — copy as many items as fit into the mob's inventory
     *       (where the {@link EatFoodGoal} will find them when HP drops).
     *       Container slot shrinks; nothing is dropped on the ground.</li>
     *   <li><b>Equipment</b> — if the candidate beats the mob's current
     *       equivalent slot per {@link EquipmentEvaluator#shouldReplace},
     *       swap it in. The displaced piece goes back into the container
     *       (overflow to the ground if the container's full).</li>
     * </ul>
     *
     * <p>Lives on the entity rather than the static {@link EquipmentEvaluator}
     * because the equipment path needs {@code Mob.getEquipmentSlotForItem}
     * (visible to subclasses) and {@code setItemSlot} (mutates the mob).</p>
     */
    public boolean tryTakeFromContainer(Container source, int slotIdx) {
        if (source == null) return false;
        ItemStack candidate = source.getItem(slotIdx);
        if (candidate.isEmpty()) return false;

        // Food first — same candidate may both be a food AND fit a slot
        // (e.g. enchanted golden apple) but the food/heal path is the more
        // useful behaviour. The slot the equipment path would route this to
        // would be MAINHAND, which would drop the mob's weapon mid-raid.
        if (candidate.get(DataComponents.FOOD) != null) {
            return EquipmentEvaluator.tryCollectFood(source, slotIdx, this.inventory);
        }

        EquipmentSlot slot = getEquipmentSlotForItem(candidate);
        ItemStack current = getItemBySlot(slot);
        if (!canReplaceCurrentItem(candidate, current)) return false;

        setItemSlot(slot, candidate.copy());
        source.setItem(slotIdx, ItemStack.EMPTY);
        source.setChanged();
        if (!current.isEmpty()) {
            ItemStack leftover = EquipmentEvaluator.addToContainer(source, current.copy());
            if (!leftover.isEmpty()) spawnAtLocation(leftover);
        }
        return true;
    }

    /**
     * Try to swap a candidate item from an armor stand's slot into the mob's
     * matching equipment slot. The stand keeps the displaced piece in the
     * same slot (true swap).
     */
    public boolean tryReplaceFromArmorStand(ArmorStand stand, EquipmentSlot fromSlot) {
        ItemStack candidate = stand.getItemBySlot(fromSlot);
        if (candidate.isEmpty()) return false;
        EquipmentSlot mobSlot = getEquipmentSlotForItem(candidate);
        ItemStack current = getItemBySlot(mobSlot);
        if (!canReplaceCurrentItem(candidate, current)) return false;

        setItemSlot(mobSlot, candidate.copy());
        stand.setItemSlot(fromSlot, current);
        return true;
    }

    /**
     * Pre-check variant of {@link #tryTakeFromContainer} — answers "would the
     * mob take this slot if asked?" without mutating anything. Used by the
     * raid goal to skip worthless slots without burning the per-swap delay
     * budget.
     */
    public boolean wouldTakeFromContainer(Container source, int slotIdx) {
        if (source == null) return false;
        ItemStack candidate = source.getItem(slotIdx);
        if (candidate.isEmpty()) return false;
        if (candidate.get(DataComponents.FOOD) != null) {
            return EquipmentEvaluator.canCollectFood(source, slotIdx, this.inventory);
        }
        EquipmentSlot slot = getEquipmentSlotForItem(candidate);
        ItemStack current = getItemBySlot(slot);
        return canReplaceCurrentItem(candidate, current);
    }

    /** Pre-check variant of {@link #tryReplaceFromArmorStand}. */
    public boolean wouldReplaceFromArmorStand(ArmorStand stand, EquipmentSlot fromSlot) {
        ItemStack candidate = stand.getItemBySlot(fromSlot);
        if (candidate.isEmpty()) return false;
        EquipmentSlot mobSlot = getEquipmentSlotForItem(candidate);
        ItemStack current = getItemBySlot(mobSlot);
        return canReplaceCurrentItem(candidate, current);
    }

    // ---- Floor item pickup (CollectFloorItemsGoal + vanilla aiStep) -------

    /**
     * Want-filter for floor items — used by both {@link CollectFloorItemsGoal}'s
     * scan and vanilla's passive {@code Mob.aiStep} pickup. The branch order
     * mirrors {@link #tryPickUpFloorItem}: a toolkit weapon/tool (wanted only if
     * it beats our current best of its category), then armor/shield upgrades,
     * then hoardable ammo / valuables / consumables, then building blocks.
     */
    @Override
    public boolean wantsToPickUp(ItemStack stack) {
        ItemPickupPolicy.WeaponCategory cat = ItemPickupPolicy.weaponCategory(stack);
        if (cat != null) {
            Located best = bestOfCategory(cat);
            return best == null || ItemPickupPolicy.compareQuality(stack, best.stack()) > 0;
        }
        return wouldEquipArmor(stack)
            || ItemPickupPolicy.isAmmo(stack)
            || ItemPickupPolicy.isValuable(stack)
            || ItemPickupPolicy.isConsumable(stack)
            || (ItemPickupPolicy.isBuildingBlock(stack)
                && ItemPickupPolicy.wantsBuildingBlock(this.inventory, stack));
    }

    /**
     * Vanilla passive-pickup entry point — fires from {@code Mob.aiStep} when
     * {@code canPickUpLoot} + {@code mobGriefing} hold. Routes through the same
     * logic the active goal uses.
     */
    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        tryPickUpFloorItem(itemEntity);
    }

    /**
     * Take an item off the ground: keep the best of each weapon/tool category
     * (dropping worse duplicates), equip armor/shield upgrades, hoard ammo /
     * valuables / consumables, and collect building blocks up to a one-stack
     * cap. Public so {@link CollectFloorItemsGoal} can drive it directly rather
     * than relying on the {@code CanPickUpLoot} flag.
     *
     * @return true if anything was taken
     */
    public boolean tryPickUpFloorItem(ItemEntity itemEntity) {
        if (itemEntity == null || !itemEntity.isAlive() || itemEntity.isRemoved()) return false;
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty() || itemEntity.hasPickUpDelay()) return false;

        ItemPickupPolicy.WeaponCategory cat = ItemPickupPolicy.weaponCategory(stack);
        if (cat != null) {
            return finishPickup(itemEntity, stack, reconcileWeaponPickup(cat, stack));
        }
        if (wouldEquipArmor(stack)) {
            return finishPickup(itemEntity, stack, equipArmorUpgrade(stack));
        }
        if (ItemPickupPolicy.isAmmo(stack)
                || ItemPickupPolicy.isValuable(stack)
                || ItemPickupPolicy.isConsumable(stack)) {
            // InventoryCarrier.pickUpItem handles its own want-check, take, and discard.
            InventoryCarrier.pickUpItem(this, this, itemEntity);
            return true;
        }
        if (ItemPickupPolicy.isBuildingBlock(stack)) {
            return finishPickup(itemEntity, stack, pickUpBlockCapped(stack));
        }
        return false;
    }

    /**
     * Shared tail for the equip + block paths: play the pickup animation,
     * shrink the ground stack by what was taken, and discard it when empty.
     * Mirrors the count handling in {@code InventoryCarrier.pickUpItem}.
     */
    private boolean finishPickup(ItemEntity itemEntity, ItemStack stack, int moved) {
        if (moved <= 0) return false;
        onItemPickup(itemEntity);
        take(itemEntity, moved);
        stack.shrink(moved);
        if (stack.isEmpty()) {
            itemEntity.discard();
        }
        return true;
    }

    /**
     * True if {@code stack} is armor/shield the mob would upgrade into. The
     * {@link ItemPickupPolicy#isArmorOrShield} guard is essential: vanilla
     * {@code canReplaceCurrentItem} returns true for <em>any</em> item over an
     * empty slot. Weapons route through {@link #reconcileWeaponPickup} instead;
     * this path is armor + shields only. Lives here (not on the static policy)
     * because {@code canReplaceCurrentItem} is {@code protected} on Mob.
     */
    private boolean wouldEquipArmor(ItemStack stack) {
        if (!ItemPickupPolicy.isArmorOrShield(stack)) return false;
        EquipmentSlot slot = getEquipmentSlotForItem(stack);
        return canReplaceCurrentItem(stack, getItemBySlot(slot));
    }

    /**
     * Equip one of {@code found} (armor/shield) as an upgrade, stashing the
     * displaced piece into the backpack (or dropping it if the backpack is
     * full). Mirrors {@link #tryReplaceFromContainer}'s displaced-item handling.
     *
     * @return 1 if equipped, 0 if it turned out not to be an upgrade
     */
    private int equipArmorUpgrade(ItemStack found) {
        EquipmentSlot slot = getEquipmentSlotForItem(found);
        ItemStack current = getItemBySlot(slot);
        if (!canReplaceCurrentItem(found, current)) return 0;

        ItemStack toEquip = found.copy();
        toEquip.setCount(1); // equipment slots hold a single piece
        setItemSlot(slot, toEquip);
        if (!current.isEmpty()) {
            ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, current);
            if (!leftover.isEmpty()) spawnAtLocation(leftover);
        }
        return 1;
    }

    // ---- Weapon toolkit (best-of-each + combat switching) ----------------

    /** A located toolkit item: {@code slot == -1} means the main hand, else a backpack slot index. */
    private record Located(ItemStack stack, int slot) {}

    /** Distance² thresholds for the combat weapon switch; the band between them prevents flicker. */
    private static final double SWITCH_TO_MELEE_SQR = 16.0;  // within 4 blocks → draw melee
    private static final double SWITCH_TO_RANGED_SQR = 64.0; // beyond 8 blocks → draw ranged

    /** Best item of {@code cat} across main hand + backpack, or null if the mob holds none. */
    private Located bestOfCategory(ItemPickupPolicy.WeaponCategory cat) {
        Located best = null;
        ItemStack main = getMainHandItem();
        if (ItemPickupPolicy.weaponCategory(main) == cat) {
            best = new Located(main, -1);
        }
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (ItemPickupPolicy.weaponCategory(stack) == cat
                    && (best == null || ItemPickupPolicy.compareQuality(stack, best.stack()) > 0)) {
                best = new Located(stack, i);
            }
        }
        return best;
    }

    /** Best melee weapon — the harder-hitting of the best sword and best axe. */
    private Located bestMelee() {
        Located sword = bestOfCategory(ItemPickupPolicy.WeaponCategory.SWORD);
        Located axe = bestOfCategory(ItemPickupPolicy.WeaponCategory.AXE);
        if (sword == null) return axe;
        if (axe == null) return sword;
        return ItemPickupPolicy.meleeAttackDamage(axe.stack())
             > ItemPickupPolicy.meleeAttackDamage(sword.stack()) ? axe : sword;
    }

    /**
     * Keep only the best of {@code cat}: if {@code picked} beats the current
     * best, drop the displaced one and stash the newcomer; otherwise leave it
     * on the ground. Then make sure the mob isn't left empty-handed (idle → best
     * melee; the attack goal re-picks the situational weapon during combat).
     *
     * @return 1 if taken, 0 if {@code picked} wasn't an upgrade
     */
    private int reconcileWeaponPickup(ItemPickupPolicy.WeaponCategory cat, ItemStack picked) {
        Located best = bestOfCategory(cat);
        if (best != null && ItemPickupPolicy.compareQuality(picked, best.stack()) <= 0) {
            return 0; // not strictly better — leave it on the ground
        }
        if (best != null) {
            removeLocated(best);
            spawnAtLocation(best.stack()); // drop the worse duplicate
        }
        ItemStack one = picked.copy();
        one.setCount(1);
        ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, one);
        if (!leftover.isEmpty()) spawnAtLocation(leftover);

        if (getTarget() == null || getMainHandItem().isEmpty()) {
            equipBestMeleeInHand();
        }
        return 1;
    }

    /** Clear a located item from wherever it lives (main hand or backpack slot). */
    private void removeLocated(Located loc) {
        if (loc.slot() == -1) {
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        } else {
            this.inventory.setItem(loc.slot(), ItemStack.EMPTY);
        }
    }

    /**
     * Put the best melee weapon in the main hand (used when idle and after
     * combat). No-op if the mob has no melee weapon or it's already in hand.
     */
    public void equipBestMeleeInHand() {
        Located melee = bestMelee();
        if (melee == null || melee.slot() == -1) return;
        ensureInMainhand(melee);
    }

    /**
     * Combat weapon switch: draw the best ranged weapon when {@code target} is
     * far and the best melee when it's close, with a hysteresis band in between
     * so the mob doesn't flicker at the boundary. Pickaxes are never drawn for
     * combat. Called every combat tick by {@link WeaponAwareAttackGoal} — a
     * cheap no-op when the right weapon is already in hand.
     */
    public void equipBestWeaponForTarget(LivingEntity target) {
        if (target == null) return;
        double distSq = distanceToSqr(target);
        Located ranged = bestOfCategory(ItemPickupPolicy.WeaponCategory.RANGED);
        Located melee = bestMelee();

        Located desired;
        if (ranged != null && distSq > SWITCH_TO_RANGED_SQR) {
            desired = ranged;
        } else if (melee != null && distSq < SWITCH_TO_MELEE_SQR) {
            desired = melee;
        } else {
            // Hysteresis band (or only one type owned): keep the current combat
            // weapon if we have one, else fall back to whatever we do own.
            ItemPickupPolicy.WeaponCategory current = ItemPickupPolicy.weaponCategory(getMainHandItem());
            if (current == ItemPickupPolicy.WeaponCategory.SWORD
                    || current == ItemPickupPolicy.WeaponCategory.AXE
                    || current == ItemPickupPolicy.WeaponCategory.RANGED) {
                return;
            }
            desired = (melee != null) ? melee : ranged;
        }
        if (desired != null) ensureInMainhand(desired);
    }

    /** Swap {@code desired} into the main hand, returning the displaced item to the backpack. */
    private void ensureInMainhand(Located desired) {
        if (desired.slot() == -1) return; // already wielded
        ItemStack current = getMainHandItem();
        this.inventory.setItem(desired.slot(), ItemStack.EMPTY);
        setItemSlot(EquipmentSlot.MAINHAND, desired.stack());
        if (!current.isEmpty()) {
            ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, current);
            if (!leftover.isEmpty()) spawnAtLocation(leftover);
        }
    }

    /**
     * Collect building blocks up to {@link ItemPickupPolicy#BUILDING_BLOCK_CAP}.
     * Under the cap, take only enough to reach it. At the cap, "trade up": drop
     * the smallest carried block stack and take the (strictly larger) found pile.
     *
     * @return how many blocks were taken from the ground stack
     */
    private int pickUpBlockCapped(ItemStack found) {
        int carried = ItemPickupPolicy.countBuildingBlocks(this.inventory);
        if (carried >= ItemPickupPolicy.BUILDING_BLOCK_CAP) {
            int smallestSlot = ItemPickupPolicy.smallestBuildingBlockSlot(this.inventory);
            if (smallestSlot < 0) return 0;
            ItemStack smallest = this.inventory.getItem(smallestSlot);
            if (found.getCount() <= smallest.getCount()) return 0;
            // Trade up: free the smallest stack, then fall through to the add below.
            this.inventory.setItem(smallestSlot, ItemStack.EMPTY);
            spawnAtLocation(smallest);
            carried = ItemPickupPolicy.countBuildingBlocks(this.inventory);
        }
        int room = ItemPickupPolicy.BUILDING_BLOCK_CAP - carried;
        if (room <= 0) return 0;
        ItemStack toAdd = found.copy();
        toAdd.setCount(Math.min(found.getCount(), room));
        int requested = toAdd.getCount();
        ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, toAdd);
        return requested - leftover.getCount();
    }

    // ---- Food helpers (called from EatFoodGoal + the forage goals) -------

    /**
     * Returns the inventory slot of the highest-nutrition food currently
     * carried, or {@code -1} if no edible item is present. "Edible" means the
     * item has a non-null {@link DataComponents#FOOD} component — same
     * definition vanilla uses for items players can right-click to eat.
     */
    public int findBestFoodSlot() {
        int bestSlot = -1;
        int bestNutrition = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            if (food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * Whether the mob currently wants to go acquire food — the single trigger
     * for the forage drive ({@link HarvestCropsGoal} harvests crops,
     * {@link HuntForFoodGoal} hunts animals, and the existing raid goal loots
     * chests). Health-scaled: a mob with no food always wants some; a hurt mob
     * keeps topping up to a healing buffer; a full-health mob with food is
     * content. See {@link ForagePolicy#wantsFood}.
     */
    public boolean wantsFood() {
        boolean hasFood = findBestFoodSlot() >= 0;
        return ForagePolicy.wantsFood(hasFood, carriedFoodNutrition(), getHealth(), getMaxHealth());
    }

    /** Total nutrition (food points) across every food stack in the backpack. */
    private int carriedFoodNutrition() {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) total += food.nutrition() * stack.getCount();
        }
        return total;
    }

    /** Radius (blocks) the mob looks for already-dropped food before it hunts more. */
    private static final double FOOD_SCAN_RADIUS = 10.0;

    /**
     * True when the mob already has an immediate way to deal with its hunger and
     * shouldn't go hunt <em>more</em> food: either edible items are lying on the
     * ground nearby (the {@link CollectFloorItemsGoal} will fetch them) or it's
     * carrying food while hurt enough that {@link EatFoodGoal} will eat it.
     *
     * <p>{@link HuntForFoodGoal} consults this so that after a kill the mob
     * stands down and lets the collect → eat loop run, instead of immediately
     * chaining to the next animal — which, being combat at priority 2, would
     * preempt the priority-3 collect/eat goals and leave the meat on the ground
     * (the mob would stay hungry forever). The ground scan deliberately ignores
     * the brief post-drop pickup delay so a just-killed animal's meat counts
     * right away and hunting doesn't re-fire in that window.</p>
     */
    public boolean hasImmediateFoodSource() {
        if (findBestFoodSlot() >= 0
                && getHealth() < getMaxHealth() * EatFoodGoal.HUNGER_THRESHOLD) {
            return true;
        }
        AABB box = getBoundingBox().inflate(FOOD_SCAN_RADIUS);
        return !level().getEntitiesOfClass(ItemEntity.class, box,
            e -> e.isAlive() && ForagePolicy.isEdible(e.getItem())).isEmpty();
    }

    /**
     * Spawn server-broadcast item-puff particles near the mob's mouth —
     * the visual feedback for "this mob is eating right now". Client-side
     * {@link Level#addParticle} won't reach other players, so we use
     * {@link ServerLevel#sendParticles} which replicates to everyone in range.
     *
     * <p>Vanilla's {@code LivingEntity.triggerItemUseEffects} is gated on
     * {@code isUsingItem()} and runs client-side per tick from data-tracked
     * use state — we don't drive {@code startUsingItem} so the vanilla path
     * never fires for our eating cycle.</p>
     */
    public void spawnEatingParticles(ItemStack food) {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        // Snapshot with copy(): ItemParticleOption keeps a live reference and the
        // packet encodes later on the Netty IO thread. EatFoodGoal shrinks the
        // offhand stack to empty on the final eat tick — the very tick the last
        // particle fires — and an empty ItemStack fails to encode ("Empty
        // ItemStack not allowed"), which can disconnect an integrated-server
        // client. Copying decouples the particle from that mutation.
        ItemParticleOption particle = new ItemParticleOption(ParticleTypes.ITEM, food.copy());
        serverLevel.sendParticles(
            particle,
            getX(),
            getEyeY() - 0.2,
            getZ(),
            /* count */ 4,
            /* xDist */ 0.15,
            /* yDist */ 0.1,
            /* zDist */ 0.15,
            /* speed */ 0.05);
    }

    // ---- Recently-explored cooldown maps ---------------------------------

    /**
     * Returns true if this BlockPos is still within the cooldown window so
     * the raid goal should skip it. {@code now} is the entity's current
     * tickCount; passing it in keeps this method side-effect-free.
     */
    public boolean isBlockExplored(BlockPos pos, long now) {
        Long last = recentlyExploredBlocks.get(pos.asLong());
        return last != null && (now - last) < RECENTLY_EXPLORED_TTL_TICKS;
    }

    /** Record a successful (or aborted) raid attempt at this BlockPos. */
    public void markBlockExplored(BlockPos pos) {
        recentlyExploredBlocks.put(pos.asLong(), (long) this.tickCount);
        sweepExpiredEntries();
    }

    /** Same as {@link #isBlockExplored} but for entity UUIDs (armor stands). */
    public boolean isEntityExplored(UUID id, long now) {
        Long last = recentlyExploredEntities.get(id);
        return last != null && (now - last) < RECENTLY_EXPLORED_TTL_TICKS;
    }

    public void markEntityExplored(UUID id) {
        recentlyExploredEntities.put(id, (long) this.tickCount);
        sweepExpiredEntries();
    }

    /**
     * Drop any expired entries from both cooldown maps. Called opportunistically
     * from {@code markX} so the maps don't grow unboundedly.
     */
    private void sweepExpiredEntries() {
        long now = this.tickCount;
        Iterator<Map.Entry<Long, Long>> b = recentlyExploredBlocks.entrySet().iterator();
        while (b.hasNext()) {
            if (now - b.next().getValue() >= RECENTLY_EXPLORED_TTL_TICKS) b.remove();
        }
        Iterator<Map.Entry<UUID, Long>> e = recentlyExploredEntities.entrySet().iterator();
        while (e.hasNext()) {
            if (now - e.next().getValue() >= RECENTLY_EXPLORED_TTL_TICKS) e.remove();
        }
    }

    // ---- Save / load -----------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        personalities.save(tag);
        tag.putInt(TAG_SKIN_INDEX, getSkinIndex());
        tag.putBoolean(TAG_CLOSES_DOORS, this.closesDoors);
        // URL skin tags are purely additive on top of v1 (SkinIndex). Only
        // write the URL key when set, so 0.2.0-loaded mobs that never had a
        // URL assigned don't round-trip an empty string back into the save.
        String url = getSkinTextureUrl();
        if (!url.isEmpty()) {
            tag.putString(TAG_SKIN_TEXTURE_URL, url);
            tag.putBoolean(TAG_SKIN_SLIM, isSkinSlim());
        }
        // Inventory persistence — InventoryCarrier helper handles slot encoding.
        // registryAccess() is a HolderLookup.Provider on Entity in 1.21.1+.
        writeInventoryToTag(tag, this.registryAccess());

        // Recently-explored maps — ListTag of {pos:long, tick:long} compounds.
        ListTag blocks = new ListTag();
        for (Map.Entry<Long, Long> e : recentlyExploredBlocks.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(TAG_POS, e.getKey());
            entry.putLong(TAG_TICK, e.getValue());
            blocks.add(entry);
        }
        tag.put(TAG_EXPLORED_BLOCKS, blocks);

        ListTag entities = new ListTag();
        for (Map.Entry<UUID, Long> e : recentlyExploredEntities.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.putLong(TAG_TICK, e.getValue());
            entities.add(entry);
        }
        tag.put(TAG_EXPLORED_ENTITIES, entities);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Per-category personalities; missing tags keep category defaults.
        personalities.load(tag);
        setSkinIndex(tag.getInt(TAG_SKIN_INDEX));
        // Missing key (pre-door-feature saves) ⇒ false ⇒ leave-open. Additive.
        this.closesDoors = tag.getBoolean(TAG_CLOSES_DOORS);
        // Backward compat: 0.2.0 saves have no SkinTextureUrl tag. Missing key
        // ⇒ URL stays the default "" ⇒ renderer uses the legacy bundled-
        // vanilla path keyed off SkinIndex. New v2 mobs round-trip the URL.
        if (tag.contains(TAG_SKIN_TEXTURE_URL, Tag.TAG_STRING)) {
            setSkinTextureUrl(tag.getString(TAG_SKIN_TEXTURE_URL));
            setSkinSlim(tag.getBoolean(TAG_SKIN_SLIM));
        }
        readInventoryFromTag(tag, this.registryAccess());

        recentlyExploredBlocks.clear();
        if (tag.contains(TAG_EXPLORED_BLOCKS, Tag.TAG_LIST)) {
            ListTag blocks = tag.getList(TAG_EXPLORED_BLOCKS, Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag entry = blocks.getCompound(i);
                recentlyExploredBlocks.put(entry.getLong(TAG_POS), entry.getLong(TAG_TICK));
            }
        }
        recentlyExploredEntities.clear();
        if (tag.contains(TAG_EXPLORED_ENTITIES, Tag.TAG_LIST)) {
            ListTag entities = tag.getList(TAG_EXPLORED_ENTITIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < entities.size(); i++) {
                CompoundTag entry = entities.getCompound(i);
                if (entry.hasUUID(TAG_UUID)) {
                    recentlyExploredEntities.put(entry.getUUID(TAG_UUID), entry.getLong(TAG_TICK));
                }
            }
        }
    }

    // ---- Death drops -----------------------------------------------------

    /**
     * Drop chance that flags a slot as a guaranteed, full-durability death
     * drop — the same {@code 2.0F} sentinel vanilla uses in
     * {@code Mob.setGuaranteedDrop}. Any value {@code > 1.0F} makes vanilla's
     * {@code Mob.dropCustomDeathLoot} treat the slot as a guaranteed drop.
     */
    private static final float GUARANTEED_EQUIPMENT_DROP_CHANCE = 2.0F;

    /**
     * On death, drop the entire backpack inventory. Combined with the
     * guaranteed {@link #getEquipmentDropChance} override below — which makes
     * {@code super.dropCustomDeathLoot} drop every equipped slot too — the mob
     * drops everything it was carrying, just like a player. Mirrors
     * {@code Pillager.dropCustomDeathLoot} for the backpack half.
     */
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(stack);
            }
        }
        this.inventory.clearContent();
    }

    // ---- Provocation reaction --------------------------------------------

    /**
     * When struck by a categorisable entity the mob isn't already reacting to,
     * flip its personality toward that category: stand and fight ({@link
     * Personality#AGGRESSIVE}) if armed, else flee ({@link Personality#SHY},
     * clamped to Aggressive where Shy is disallowed). On a flip to Shy we drop
     * the retaliation target so {@link FleeFromCategoryGoal} takes over.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && !level().isClientSide && source.getEntity() instanceof LivingEntity attacker) {
            TargetCategory category = TargetCategory.classify(attacker);
            if (category != null) {
                Personality reaction = personalities.provoke(category, isArmed());
                if (reaction == Personality.SHY) {
                    setTarget(null);
                    setLastHurtByMob(null);
                }
            }
        }
        return result;
    }

    /**
     * Make every equipment slot — held weapon, off-hand, and all four armor
     * pieces — drop on death, at full durability, regardless of what killed
     * the mob. A real player drops all their gear on death; PlayerMob now
     * matches.
     *
     * <p>Returning a guaranteed value ({@code > 1.0F}) makes vanilla's
     * {@code Mob.dropCustomDeathLoot} bypass the "recently hit by a player"
     * gate, always pass the random roll, and skip the durability-damage step.
     * Curse of Vanishing items are still destroyed (vanilla's
     * {@code PREVENT_EQUIPMENT_DROP} check upstream) — also exactly like a
     * player.</p>
     *
     * <p>Surgical by design: only the drop-chance <em>method</em> is
     * overridden. The backing {@code handDropChances}/{@code armorDropChances}
     * fields are left at their vanilla defaults, so the XP-reward and
     * item-pickup logic that reads those fields directly is unaffected.</p>
     */
    @Override
    protected float getEquipmentDropChance(EquipmentSlot slot) {
        return GUARANTEED_EQUIPMENT_DROP_CHANCE;
    }

    /**
     * Death hook with two responsibilities:
     *
     * <ol>
     *   <li>Force-close any container the mob had open. {@link RaidContainersGoal#stop}
     *       also closes it, but when the mob dies mid-raid the goal selector stops
     *       ticking before it gets the chance — this is the safety net so
     *       chests/barrels don't stay visually stuck open.</li>
     *   <li>Announce the death in chat like a player's. The entity's display
     *       name is "Player", so the vanilla combat tracker yields messages
     *       that read like a real player death ("Player was slain by Zombie",
     *       "Player drowned", …).</li>
     * </ol>
     */
    @Override
    public void die(DamageSource source) {
        closeOpenedContainer();
        // Capture the death message BEFORE super.die(): super calls
        // CombatTracker.recheckStatus(), which clears the combat entries, so
        // the attacker-aware message ("… was slain by Zombie") is only
        // available now. Vanilla ServerPlayer.die() reads it before super for
        // exactly this reason — reading after yields the generic "… died".
        Component deathMessage = captureDeathMessage();
        super.die(source);
        // Broadcast only after super confirms the kill (this.dead flips true):
        // a cancelled death (Forge/NeoForge LivingDeathEvent) or a re-entrant
        // die() leaves it false, so we stay silent and never double-announce.
        if (deathMessage != null && this.dead && this.level() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(deathMessage, false);
        }
    }

    /**
     * The player-style death message to broadcast, or {@code null} if this
     * death must not be announced — client-side, already dead/removed, or the
     * {@code showDeathMessages} gamerule is off (just like a real player).
     *
     * <p>Must be called BEFORE {@code super.die()} while the combat tracker
     * still holds the fatal blow — see {@link #die}.</p>
     */
    private Component captureDeathMessage() {
        if (this.dead || this.isRemoved() || !(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        if (!serverLevel.getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) {
            return null;
        }
        return this.getCombatTracker().getDeathMessage();
    }

    // ---- Container open/close (called from RaidContainersGoal) -----------

    /**
     * Animate the container at {@code pos} as open and play the matching
     * sound (chest lid via {@link Level#blockEvent}, barrel via the
     * {@link BarrelBlock#OPEN} block-state property). Tracks the position
     * on the entity so {@link #die} can force-close it if needed.
     */
    public void openContainer(BlockPos pos) {
        Level level = level();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        BlockState state = level.getBlockState(pos);

        if (be instanceof ChestBlockEntity) {
            level.blockEvent(pos, state.getBlock(), CHEST_VIEWERS_EVENT, 1);
            playContainerSound(pos, SoundEvents.CHEST_OPEN);
        } else if (be instanceof BarrelBlockEntity) {
            if (state.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(pos, state.setValue(BarrelBlock.OPEN, true), 3);
            }
            playContainerSound(pos, SoundEvents.BARREL_OPEN);
        } else if (be instanceof ShulkerBoxBlockEntity) {
            // Vanilla ShulkerBoxBlockEntity.triggerEvent(1, n) drives the lid
            // animation. Bypassing startOpen(Player) here because that method
            // is Player-typed and our mob isn't a Player.
            level.blockEvent(pos, state.getBlock(), ShulkerBoxBlockEntity.EVENT_SET_OPEN_COUNT, 1);
            playContainerSound(pos, SoundEvents.SHULKER_BOX_OPEN);
        } else {
            return; // unknown container type; nothing to track
        }
        this.openContainerPos = pos.immutable();
    }

    /**
     * Reverse {@link #openContainer}. Cheap no-op if no container is
     * currently flagged open — safe to call from cleanup paths.
     */
    public void closeOpenedContainer() {
        if (openContainerPos == null) return;
        BlockPos pos = openContainerPos;
        openContainerPos = null;

        Level level = level();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return; // container destroyed mid-raid; nothing to animate
        BlockState state = level.getBlockState(pos);

        if (be instanceof ChestBlockEntity) {
            level.blockEvent(pos, state.getBlock(), CHEST_VIEWERS_EVENT, 0);
            playContainerSound(pos, SoundEvents.CHEST_CLOSE);
        } else if (be instanceof BarrelBlockEntity) {
            if (state.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(pos, state.setValue(BarrelBlock.OPEN, false), 3);
            }
            playContainerSound(pos, SoundEvents.BARREL_CLOSE);
        } else if (be instanceof ShulkerBoxBlockEntity) {
            level.blockEvent(pos, state.getBlock(), ShulkerBoxBlockEntity.EVENT_SET_OPEN_COUNT, 0);
            playContainerSound(pos, SoundEvents.SHULKER_BOX_CLOSE);
        }
    }

    private void playContainerSound(BlockPos pos, SoundEvent sound) {
        level().playSound(
            /* exclude */ null,
            pos,
            sound,
            SoundSource.BLOCKS,
            /* volume */ 0.5F,
            /* pitch */ 0.9F + getRandom().nextFloat() * 0.1F);
    }

    // ---- CrossbowAttackMob -----------------------------------------------

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

    // ---- RangedAttackMob (bow path) --------------------------------------

    /**
     * Called by {@link net.minecraft.world.entity.ai.goal.RangedBowAttackGoal}
     * when the mob is holding a bow. Crossbows route through
     * {@link CrossbowAttackMob#performCrossbowAttack(LivingEntity, float)}.
     * Mirrors {@link net.minecraft.world.entity.monster.AbstractSkeleton#performRangedAttack}.
     */
    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        ItemStack mainhand = this.getMainHandItem();
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

        arrow.shoot(dx, dy + dist * 0.2, dz, 1.6F,
                    (float) (14 - this.level().getDifficulty().getId() * 4));

        // Player's bow-release sound (vanilla BowItem uses ARROW_SHOOT), not the
        // skeleton's. Routes through getSoundSource() → Players category.
        this.playSound(SoundEvents.ARROW_SHOOT, 1.0F,
                       1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    // ---- Sounds (player-like — mirrors vanilla Player exactly) -----------

    /** Players have no idle sound — stay silent like a real player. */
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    /**
     * Data-driven hurt sound, identical to {@code Player.getHurtSound}: the
     * damage type's own effect sound (PLAYER_HURT / _DROWN / _ON_FIRE /
     * _FREEZE / _SWEET_BERRY_BUSH).
     */
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return source.type().effects().sound();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    /**
     * Match a real player's sound category so hurt/death/bow sounds play under
     * the Players volume slider, not Hostile Creatures. Mirrors
     * {@code Player.getSoundSource()}.
     */
    @Override
    public SoundSource getSoundSource() {
        return SoundSource.PLAYERS;
    }

    /**
     * Deliberately extends {@link PathfinderMob}, NOT {@code Monster} — so this
     * entity does <em>not</em> implement the {@link Enemy} marker interface.
     * Iron golems (and other {@code Enemy}-seeking mobs) therefore ignore it on
     * sight instead of attacking it like a real pillager. As a side effect,
     * PlayerMobs no longer treat one another as targets, since the
     * {@link Stance} predicate keys off {@code instanceof Enemy}. Combat against
     * genuine hostile mobs is unaffected — those are still {@code Enemy}
     * instances and remain valid targets.
     */
}
