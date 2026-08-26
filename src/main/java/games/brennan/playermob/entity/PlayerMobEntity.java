package games.brennan.playermob.entity;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.compat.PlayerMobSocialHooks;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.goal.AdvanceCarriageGoal;
import games.brennan.playermob.entity.goal.AttackOrder;
import games.brennan.playermob.entity.goal.BlockArrowsGoal;
import games.brennan.playermob.entity.goal.CollectFloorItemsGoal;
import games.brennan.playermob.entity.goal.CommandedActionGoal;
import games.brennan.playermob.entity.goal.CrossGroupGapGoal;
import games.brennan.playermob.entity.goal.DefendLovedOneGoal;
import games.brennan.playermob.entity.goal.DigThroughGoal;
import games.brennan.playermob.entity.goal.DoorOperationGoal;
import games.brennan.playermob.entity.goal.EatFoodGoal;
import games.brennan.playermob.entity.goal.FireBucketGoal;
import games.brennan.playermob.entity.goal.FleeFromCategoryGoal;
import games.brennan.playermob.entity.goal.FollowLovedOneGoal;
import games.brennan.playermob.entity.goal.FriendlyGreetGoal;
import games.brennan.playermob.entity.goal.HarvestCropsGoal;
import games.brennan.playermob.entity.goal.Order;
import games.brennan.playermob.entity.goal.HuntForFoodGoal;
import games.brennan.playermob.entity.goal.PlayerMobDoorGoal;
import games.brennan.playermob.entity.goal.RaidArmorStandsGoal;
import games.brennan.playermob.entity.goal.RaidContainersGoal;
import games.brennan.playermob.entity.goal.SeekAmmoGoal;
import games.brennan.playermob.entity.goal.SkepticalWatchGoal;
import games.brennan.playermob.entity.goal.StayNearGoal;
import games.brennan.playermob.entity.goal.EndCrystalCombatGoal;
import games.brennan.playermob.entity.goal.TntCombatGoal;
import games.brennan.playermob.entity.goal.TrainRecoveryGoal;
import games.brennan.playermob.entity.goal.WeaponAwareAttackGoal;
import games.brennan.playermob.player.PlayerLifeRecord;
import games.brennan.playermob.player.PlayerLifeStore;
import games.brennan.playermob.player.GlobalLifeStore;
import games.brennan.playermob.player.PlayerReincarnation;
import games.brennan.playermob.skin.LocalSkinFolder;
import games.brennan.playermob.skin.LocalSkinRef;
import games.brennan.playermob.skin.SkinDisplayName;
import games.brennan.playermob.skin.PlayerMobSkin;
import games.brennan.playermob.skin.PlayerMobSkinRegistry;
import games.brennan.playermob.skin.SkinModel;
import games.brennan.playermob.skin.SkinNameApplier;
import games.brennan.playermob.skin.SkinSourceSelector;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.ItemDataCompat;
import games.brennan.playermob.compat.ItemKindCompat;
import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.compat.PetSnapshots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
//? if >=26 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?} else {
import net.minecraft.world.entity.MobSpawnType;
//?}
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
//? if >=26 {
/*import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
*///?} else {
import net.minecraft.world.entity.projectile.AbstractArrow;
//?}
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.21.1 {
import net.minecraft.world.item.MaceItem;
//?}
import net.minecraft.world.item.ProjectileWeaponItem;
//? if <26 {
import net.minecraft.world.item.SwordItem;
//?}
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
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
 * <p><b>Disposition</b> is numeric: two locked traits ({@link DispositionTraits} —
 * fight/flight and friendliness) plus an evolving per-individual
 * {@link FeelingLedger} feeling toward each player and PlayerMob.
 * {@link DispositionResolver} collapses these into a {@link Reaction} per target
 * each tick; the target-selector attacks anything it resolves {@link Reaction#FIGHT},
 * and {@link FleeFromCategoryGoal}, {@link SkepticalWatchGoal}, and
 * {@link FriendlyGreetGoal} cover FLEE/WATCH/GREET. Low-friendliness mobs react
 * only inside a feeling-scaled personal-space bubble. Being attacked cools the
 * feeling toward the attacker (see {@link #hurt}). Server-side only; the crouch
 * gesture rides the vanilla-synced pose ({@link #setCrouching}).</p>
 *
 * <p><b>Skins</b> — Each mob rolls its look in {@link #finalizeSpawn}. It
 * always rolls a bundled-vanilla index in {@code [0, SKIN_COUNT)}, then with
 * probability {@link PlayerMobConfig#customSkinChance()} (default ~40%) overrides
 * it with a Mojang skin texture URL drawn from the datapack-extensible
 * {@link PlayerMobSkinRegistry}. So by default ~60% of mobs wear a vanilla default
 * and ~40% wear a recognisable real-player skin. The client renderer ({@code PlayerMobRenderer}, not
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
     * Slim-arms flag (true = 3-pixel arms, like the Alex model). Synced to the
     * client, where the renderer swaps to the slim body model when set.
     */
    private static final EntityDataAccessor<Boolean> DATA_SKIN_SLIM =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.BOOLEAN);

    // Disposition mirror — server-only traits/feelings pushed to the client for the
    // menu UI (see pushDispositionToClient). Not persisted (the NBT path handles that).
    private static final EntityDataAccessor<Integer> DATA_FIGHT_FLIGHT =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_FRIENDLINESS =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_REACTION_SPEED =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.INT);

    /** Encoded feeling ledger ({@code "uuid=feeling;…"}) for the client menu UI. */
    private static final EntityDataAccessor<String> DATA_FEELINGS =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.STRING);

    /**
     * Newline-joined readout of the goals currently running in {@link #goalSelector}
     * ("Objective — phase" per line, highest priority first, or "Idle"). Built
     * server-side by {@link ObjectiveReadout} and synced so the client can draw it
     * under the mob's name and in the right-click menu (Creative only). Network-only
     * — never written to save NBT, so it adds no save-format change.
     */
    private static final EntityDataAccessor<String> DATA_OBJECTIVES =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.STRING);

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

    /** Backpack size — matches Pillager (5) plus a little extra. */
    private static final int INVENTORY_SIZE = 8;

    /** Recently-explored entries expire after this many ticks (60 seconds). */
    private static final long RECENTLY_EXPLORED_TTL_TICKS = 1200L;

    /**
     * Feeling lost per point of damage taken from a player/PlayerMob. A ~4-damage
     * blow cools feeling by 2 (neutral 5 → "hate" 3); chip damage cools slowly.
     */
    private static final float DMG_TO_FEELING = 0.5F;

    /**
     * Ticks between the Phase B social scan (encounter / crouch / travel / harm /
     * defend). ~4×/second — frequent enough to catch a deliberate crouch yet well
     * inside the ~100-tick decay window of {@code getLastHurtByMob} the defend/harm
     * checks rely on.
     */
    private static final int SOCIAL_SCAN_INTERVAL = 5;

    /** Radius of the social scan — the disposition resolver's widest reaction range. */
    private static final double SOCIAL_SCAN_RANGE = DispositionResolver.MAX_RANGE;

    /**
     * Half-angle (degrees) of the cone in which a croucher counts as "looking at"
     * the mob, so a crouch only greets the mob it's aimed at — a 90° frontal arc.
     */
    private static final float CROUCH_LOOK_CONE_DEGREES = 45.0F;

    /**
     * How long after leaving a Dungeon Train a PlayerMob still counts as "fell
     * off" and will try to climb back on — see {@link TrainRecoveryGoal}. 200
     * ticks (10s) is generous enough to start recovery after a fall, tight enough
     * that a mob doesn't board a train it merely brushed past long ago.
     */
    public static final int RECOVERY_WINDOW_TICKS = 200;

    /**
     * Chest {@code triggerEvent} ID for "viewer count changed" — drives the
     * lid animation. See {@link ChestBlockEntity#triggerEvent}.
     */
    private static final int CHEST_VIEWERS_EVENT = 1;

    private static final String TAG_SKIN_INDEX = "SkinIndex";
    private static final String TAG_SKIN_TEXTURE_URL = "SkinTextureUrl";
    private static final String TAG_SKIN_SLIM = "SkinSlim";
    private static final String TAG_SKIN_PLAYER_NAME = "SkinPlayerName";
    private static final String TAG_CLOSES_DOORS = "ClosesDoors";
    private static final String TAG_NATURAL_ORIGIN = "NaturalOrigin";
    private static final String TAG_TRAIN_EXPLORE_DIR = "TrainExploreDir";
    private static final String TAG_ORDER_TIMEOUT = "OrderTimeout";
    private static final String TAG_ORDER_INTERRUPTIBLE = "OrderInterruptible";
    private static final String TAG_TRAIN_PAIR_PARTNER = "TrainPairPartner";
    private static final String TAG_EXPLORED_BLOCKS = "ExploredBlocks";
    private static final String TAG_EXPLORED_ENTITIES = "ExploredEntities";
    private static final String TAG_POS = "Pos";
    private static final String TAG_UUID = "UUID";
    private static final String TAG_TICK = "Tick";
    private static final String TAG_STAY_NEAR = "StayNear";

    // ---- Fields -----------------------------------------------------------

    /**
     * The two locked personal traits (fight/flight, friendliness). Server-side
     * only. Rolled at spawn, set by spawn eggs / {@code /summon}, persisted to NBT.
     */
    private final DispositionTraits traits = new DispositionTraits();

    /**
     * Evolving per-individual feelings toward players and other PlayerMobs.
     * Server-side only; persisted to NBT. Phase A only decreases them (on attack).
     */
    private final FeelingLedger feelings = new FeelingLedger();

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
     * Per-individual "was crouch-greeting this mob at the last social scan" (crouching
     * + facing it + line of sight), so a held greeting counts once on its rising edge.
     * Transient — rebuilt each scan from who's in range, so it never retains anyone who
     * logged out or wandered off.
     */
    private final Map<UUID, Boolean> crouchHeld = new HashMap<>();

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

    /**
     * Whether this mob arrived in the world on its own (wild / chunk-generation / mob-spawner spawn, or a
     * Dungeon-Train event) rather than being placed by a player with a spawn egg, {@code /summon} or a
     * dispenser. Set in {@link #finalizeSpawn} from the spawn reason and persisted; a mob saved before
     * this existed loads as {@code true}, so the scavenging gates keep their old behaviour for it.
     *
     * <p>Read by the scavenging goals through {@link #allowsScavenging(ScavengeMode)} — see
     * {@link ScavengeMode#ONLY_NATURALLY_SPAWNING}.</p>
     */
    private boolean naturalOrigin = true;

    /**
     * True once {@link #finalizeSpawn} has classified this spawn — transient, never persisted. A spawn
     * egg's {@code entity_data} is merged <em>after</em> {@code finalizeSpawn} (see the note there), so
     * {@link #readCustomTag} must not reset {@link #naturalOrigin} to its "legacy save" default when that
     * merge carries no {@code NaturalOrigin} key; an explicit key still wins.
     */
    private boolean naturalOriginResolved = false;

    /**
     * Whether this mob's skin came from loaded NBT (a reincarnation egg's snapshot,
     * or a {@code /summon} carrying a {@code Skin*} tag) rather than being rolled at
     * spawn. The skin analogue of {@link DispositionTraits}'s explicit-set tracking:
     * when {@code true}, {@link #finalizeSpawn} keeps the loaded skin instead of
     * re-rolling over it. Set in {@link #readAdditionalSaveData} only when a skin key
     * is actually present, so a trait-only egg still rolls a random skin.
     */
    private boolean skinExplicit;

    /**
     * A player name to resolve into a skin on the next server tick — set by {@link #readCustomTag} when a
     * {@code /summon} (or entity-egg {@code entity_data}) carries a {@code SkinPlayerName} tag. Deliberately
     * <b>not</b> persisted: it's a one-shot input, consumed by {@link #resolvePendingSkinPlayerName} the
     * moment it's read, same as {@code /playermob summon}'s own async skin apply (see
     * {@link games.brennan.playermob.skin.SkinNameApplier}).
     */
    private String pendingSkinPlayerName;

    /**
     * Transient (never saved): when {@code true}, {@link #finalizeSpawn} skips the generic skin-source
     * auto-name, leaving naming to the caller. Set by {@link PlayerMobSummon} for {@code /playermob summon},
     * whose real skin is applied <em>after</em> {@code finalizeSpawn} (a local file synchronously, a player
     * name asynchronously) — so the command names the mob once that skin lands rather than off the rolled one.
     */
    private boolean deferAutoName;

    /**
     * Transient (never saved): set in {@link #finalizeSpawn} for a spawn-egg spawn. On 1.21.1 a spawn egg
     * merges its {@code entity_data} AFTER {@code finalizeSpawn}, so the {@code customSkinChance} URL roll is
     * deferred to the first tick ({@link #resolvePendingSkinPlayerName}) — by when the egg's skin directive
     * (a {@code SkinPlayerName} or an authored {@code SkinTextureUrl}) is known and suppresses the roll. Left
     * {@code false} for every other spawn (which roll their URL skin in {@code finalizeSpawn} as before), and
     * never persisted, so a saved mob never re-rolls its skin on world load.
     */
    private boolean eggAwaitingSkinRoll;

    /**
     * Server tick of the last moment this mob stood on a train carriage, or a
     * large negative sentinel if it never has. Drives {@link #ticksSinceOnTrain}
     * so {@link TrainRecoveryGoal} fires only for a mob that actually fell off a
     * train. Transient (not saved) — a reloaded mob just isn't "recovering" until
     * it rides again.
     */
    private int lastOnTrainTick = -100_000;

    /**
     * True only while {@link TrainRecoveryGoal} is actively climbing this mob back
     * onto a train (set on the goal's start, cleared on stop). Off the train, recovery
     * is the mob's <em>sole</em> focus — this flag suppresses combat (target
     * acquisition + held targets) so it never breaks off to chase a mob mid-recovery.
     * Transient server-only AI state.
     */
    private boolean recovering;

    /**
     * A pending one-off {@link Order} issued by the {@code /playermob order} command,
     * executed (and then cleared) by {@link CommandedActionGoal}. Transient server-only
     * AI state — never persisted, replaced wholesale by a newer order.
     */
    private Order pendingOrder;

    /**
     * The {@code tickCount} at which {@link #pendingOrder} was issued — used by
     * {@link #tickOrderTimeout()} to abandon an order that can't be executed within its
     * {@link Order#timeoutTicks()} window. Transient server-only AI state.
     */
    private int orderStartTick;

    /**
     * Per-mob defaults for the {@code /playermob order} timeout / interruptibility, applied when the
     * command omits the {@code for <n> s} / {@code forever} / {@code nonstop} flags. Persisted in NBT
     * (unlike the transient live order) so a summoned or {@code /data}-configured mob keeps them.
     */
    private int orderTimeoutDefaultTicks = Order.DEFAULT_TIMEOUT_TICKS;
    private boolean orderInterruptibleDefault = true;

    /**
     * A commanded {@link AttackOrder} (from {@code /playermob order ... attack}), monitored each
     * server tick by {@link #tickAttackOrder()} rather than via a goal. Mutually exclusive with
     * {@link #pendingOrder}. Transient server-only AI state — never persisted.
     */
    private AttackOrder attackOrder;

    /** Place a one-off order for this mob to carry out (replaces any pending order). */
    public void setOrder(Order order) {
        this.pendingOrder = order;
        this.orderStartTick = this.tickCount;   // start the timeout clock for tickOrderTimeout
        this.attackOrder = null;   // a movement order supersedes a commanded attack
    }

    /** The pending order, or {@code null} when the mob has none. */
    public Order getOrder() {
        return this.pendingOrder;
    }

    /** Clear the pending order — called by {@link CommandedActionGoal} once it's done. */
    public void clearOrder() {
        this.pendingOrder = null;
    }

    /**
     * Abandon a pending order that has outlived its {@link Order#timeoutTicks()} window (default 2
     * min; {@code < 0} = never). Called each server tick from {@link #customServerAiStep()} beside
     * {@link #tickAttackOrder()} — the single owner of order expiry, so the order survives interruptions
     * and re-approaches for its whole lifetime rather than being dropped by a local walk timeout.
     */
    private void tickOrderTimeout() {
        Order o = this.pendingOrder;
        if (o == null || o.timeoutTicks() < 0) {
            return;
        }
        if (this.tickCount - this.orderStartTick >= o.timeoutTicks()) {
            this.pendingOrder = null;
        }
    }

    /** The mob's default order-timeout (ticks) applied when a command omits the {@code for}/{@code forever} flag. */
    public int getOrderTimeoutDefaultTicks() {
        return this.orderTimeoutDefaultTicks;
    }

    /** The mob's default order interruptibility applied when a command omits the {@code nonstop} flag. */
    public boolean isOrderInterruptibleDefault() {
        return this.orderInterruptibleDefault;
    }

    /**
     * The stay-near tether keeping this mob within a radius of an anchor (a fixed position or a live
     * entity), or {@code null} when it may roam freely. Persisted in {@code PlayerMobData/StayNear};
     * read each tick by {@link StayNearGoal}. Set/cleared by {@code /playermob stay …} or authored in
     * spawn NBT.
     */
    private StayAnchor stayAnchor;

    /** The mob's stay-near tether, or {@code null} if it roams freely. */
    public StayAnchor getStayAnchor() {
        return this.stayAnchor;
    }

    /** Tether this mob near {@code anchor} (a position or entity + radius). */
    public void setStayAnchor(StayAnchor anchor) {
        this.stayAnchor = anchor;
    }

    /** Remove the stay-near tether — the mob roams freely again. */
    public void clearStayAnchor() {
        this.stayAnchor = null;
    }

    /**
     * Order this mob to attack {@code target} until {@code limit} is met (see {@link AttackOrder}).
     * Pins the combat target immediately; the existing weapon-aware attack goal does the fighting,
     * while {@link #tickAttackOrder()} enforces the stop condition. Supersedes any movement order.
     */
    public void orderAttack(LivingEntity target, AttackOrder.Limit limit, int amount) {
        this.pendingOrder = null;
        this.attackOrder = new AttackOrder(target, limit, amount, this.tickCount);
        setTarget(target);
    }

    /**
     * Enforce a commanded {@link AttackOrder}: drop the target + order once the stop condition is
     * met (target gone, the sweep would drop it, or the duration / hearts / hit-back limit is hit),
     * otherwise keep the commanded target pinned so disposition/idle goals don't break it off.
     */
    private void tickAttackOrder() {
        AttackOrder o = this.attackOrder;
        if (o == null) {
            return;
        }
        LivingEntity tgt = o.target();
        boolean done = recovering
            || tgt == null || !tgt.isAlive() || tgt.isRemoved()
            || isIgnoredPlayer(tgt) || !TrainConfinement.allowsTarget(this, tgt);
        if (!done) {
            done = switch (o.limit()) {
                case SECONDS -> (this.tickCount - o.startTick()) >= o.amount() * 20L;
                case HEARTS -> tgt.getHealth() <= o.amount() * 2.0F;
                case UNTIL_HIT -> getLastHurtByMobTimestamp() > o.startTick();
                case KILL -> false;
            };
        }
        if (done) {
            if (getTarget() == tgt) {
                setTarget(null);
            }
            this.attackOrder = null;
            return;
        }
        if (getTarget() != tgt) {
            setTarget(tgt);   // re-pin so the commanded attack rides through idle/flee goals
        }
    }

    /**
     * True only while the Dungeon-Train dig-through reflex is actively mining a fill block that's
     * blocking this mob's march (set/cleared each tick by
     * {@code DungeonTrainEnvironment#digObstructingBlock}). Read by {@link DigThroughGoal} to
     * surface "Digging through" in the Creative readout. Transient server-only AI state.
     */
    private boolean digging;

    /**
     * Fixed march direction while exploring a Dungeon Train: {@code -1} toward
     * decreasing carriage index, {@code +1} toward increasing, {@code 0} = not yet
     * latched. Set once, the first server tick the mob is found on a train, from
     * the sign of its boarding carriage index (see {@link #customServerAiStep} and
     * {@link TrainConfinement#boardingDirection(int)}), then kept — so the mob
     * keeps marching the same way after passing carriage 0, and the choice
     * survives save/load. Read each tick by {@link AdvanceCarriageGoal}.
     *
     * <p>Server-only AI state — never rendered — so a plain field with NBT
     * persistence, not a synched DataTracker entry (mirrors {@link #closesDoors}).
     * Defaults to {@code 0} (no train mod ⇒ never latched ⇒ the goal no-ops).
     * NOTE: not reset when the mob leaves a train; behaviour #2 (cross-group
     * traversal) must revisit re-latching on a genuine disembark/re-board.</p>
     */
    private int trainExploreDir;

    /**
     * Cached redirect of the Dungeon-Train march toward a player this mob loves, refreshed on a
     * throttle in {@link #customServerAiStep} and read by {@link #effectiveTrainMarchDir}. When
     * {@link #hasLovedMarchOverride} is set it replaces the fixed {@link #trainExploreDir}:
     * {@code +1}/{@code -1} steps toward the loved player's carriage, {@code 0} once in the same
     * carriage (so the march goals idle and the mob just travels with the player). Server-only and
     * transient — never saved, recomputed from a live scan; reuses {@link DispositionResolver#FEELING_LOVE}
     * and is gated by {@link PlayerMobConfig#trainFollowLovedPlayer}.
     */
    private int lovedMarchDir;
    private boolean hasLovedMarchOverride;
    /** Re-scan cadence (ticks) for the loved-player march redirect; the march goals read the cache every tick. */
    private static final int LOVED_MARCH_REFRESH_TICKS = 10;

    /**
     * Chance, per Dungeon-Train ({@link MobSpawnType#EVENT}) spawn, to also spawn one
     * companion PlayerMob that is mutually max friends with this mob (see
     * {@link #maybeSpawnFriendPair}).
     */
    private static final float DT_PAIR_CHANCE = 0.10F;

    /**
     * UUID of the Dungeon-Train spawn companion this mob was paired with as max friends, or
     * {@code null} if it has none. Used once, at boarding, to copy the partner's
     * already-latched march direction (see {@link #latchTrainExploreDirection}) so a
     * spawned-together pair travels the train the same way instead of splitting at the
     * carriage-0 boundary after collision nudges them apart. Persisted (additive NBT);
     * never reset — once both have latched it has no further effect.
     */
    private UUID trainPairPartner;

    /**
     * True only while {@link CrossGroupGapGoal} is carrying the mob across the gap
     * between two carriage groups. Transient AI state (never saved): a leap is short and
     * always restarts from scratch, so it has no meaning across a reload. While set, the
     * hostile-targeting goal declines new targets, so a passing mob can't preempt the
     * leap and abandon the PlayerMob mid-gap.
     */
    private boolean crossingGap;

    /**
     * True only while {@link FleeFromCategoryGoal} is running this mob away from a Shy
     * threat (set on the goal's start, cleared on stop). While set, the arrow-blocking
     * reflex ({@link BlockArrowsGoal}) stands down — a fleeing mob shouldn't stop to face
     * and raise its shield, which fights the retreat. Transient server-only AI state.
     */
    private boolean fleeing;

    /** How long door-opening is suppressed after a stuck-recovery close, so the mob can cross. ~2 s. */
    private static final int DOOR_CLOSE_HOLD_TICKS = 40;
    /** Half-width of the cube scanned around the mob for an open door to close when stuck (off-train). */
    private static final int DOOR_RECOVERY_REACH = 2;

    /**
     * Off-train detector for the "close a door that's blocking me" recovery. The on-train
     * (Dungeon Train) reflex keeps its own per-mob detectors, so this one only runs off a
     * train. Transient AI state (never saved): a stuck run restarts cleanly across a reload.
     * See {@link #recoverFromStuckDoor()} and {@link DoorStuckMonitor}.
     */
    private final DoorStuckMonitor offTrainDoorStuck = new DoorStuckMonitor();

    /**
     * Ticks left during which this mob opens no doors, so a door the stuck-recovery just closed
     * isn't reopened before the mob can cross the perpendicular path the open swing was blocking.
     * Set by {@link #holdDoorsClosed()} (on or off a train), decremented each server tick, and read
     * by both door openers via {@link #isHoldingDoorsClosed()}. Transient (never saved).
     */
    private int doorCloseHoldTicks;

    /** Length of a deliberate door-operation window: face, operate ~midway, brief hold. ~0.5 s. */
    private static final int DOOR_OP_TICKS = 10;
    /** Ticks-remaining at which the open/close fires — a few ticks in, so the mob faces *then* operates. */
    private static final int DOOR_OP_REACH_TICKS = 6;

    /** Ticks left in the current deliberate door operation (0 ⇒ not operating). Transient AI state. */
    private int doorOpTicks;
    /** Whether to drive the look toward the door this op (false ⇒ the caller drives its own gaze, e.g. an iron control). */
    private boolean doorOpFacing;
    /** Eye-relative offset to the door, re-applied each tick so the look tracks a moving carriage (like the iron-control gaze). */
    private double doorOpDx;
    private double doorOpDy;
    private double doorOpDz;
    /** The deferred open/close, run once at the reach tick; {@code null} when the caller performs the action itself. */
    private Runnable doorOpAction;

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
        //? if >=26 {
        /*// 26.x: guaranteed gear drops are per-slot drop-chance state (no getEquipmentDropChance
        // override); stamp them here. Pre-26 the override below does the same job per-call.
        applyGuaranteedDrops();
        *///?}
        // Route pathfinding through closed wooden doors (passing through *open*
        // doors is already on by default). PlayerMobDoorGoal does the opening;
        // without this flag DoorInteractGoal.canUse() never fires.
        if (this.getNavigation() instanceof GroundPathNavigation groundNav) {
            groundNav.setCanOpenDoors(true);
        }
        // Let the path cross water (float on the surface) instead of treating it as a
        // wall — so a mob recovering back onto a train can swim toward it / to the
        // nearest shore. FloatGoal (priority 0) keeps it from sinking.
        this.getNavigation().setCanFloat(true);
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

    //? if >=1.21.1 {
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_IS_CHARGING_CROSSBOW, false);
        builder.define(DATA_SKIN_INDEX, 0);
        builder.define(DATA_SKIN_TEXTURE_URL, "");
        builder.define(DATA_SKIN_SLIM, false);
        builder.define(DATA_FIGHT_FLIGHT, DispositionTraits.DEFAULT);
        builder.define(DATA_FRIENDLINESS, DispositionTraits.DEFAULT);
        builder.define(DATA_REACTION_SPEED, DispositionTraits.DEFAULT);
        builder.define(DATA_FEELINGS, "");
        builder.define(DATA_OBJECTIVES, "");
    }
    //?} else {
    /*@Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_IS_CHARGING_CROSSBOW, false);
        this.entityData.define(DATA_SKIN_INDEX, 0);
        this.entityData.define(DATA_SKIN_TEXTURE_URL, "");
        this.entityData.define(DATA_SKIN_SLIM, false);
        this.entityData.define(DATA_FIGHT_FLIGHT, DispositionTraits.DEFAULT);
        this.entityData.define(DATA_FRIENDLINESS, DispositionTraits.DEFAULT);
        this.entityData.define(DATA_REACTION_SPEED, DispositionTraits.DEFAULT);
        this.entityData.define(DATA_FEELINGS, "");
        this.entityData.define(DATA_OBJECTIVES, "");
    }*///?}

    /**
     * The current objective readout — one line per running goal ("Objective —
     * phase"), highest priority first, or "Idle". Refreshed server-side in
     * {@link #customServerAiStep()} and synced; the client renderer and the
     * right-click menu read it to show what the mob is doing (Creative only).
     */
    public String getObjectivesReadout() {
        return this.entityData.get(DATA_OBJECTIVES);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // On fire? Get to water — priority 0 (same tier as FloatGoal, which only holds the JUMP
        // flag so there's no conflict) so this preempts literally everything else, including an
        // explicit player order and train recovery: a same-priority goal can never interrupt
        // another once it's running (vanilla GoalSelector only lets a STRICTLY higher-priority
        // goal preempt), so at priority 1 this could get starved mid-sequence — stuck approaching
        // water, or never starting at all — by any other priority-1 goal already holding
        // MOVE/LOOK. Priority 0 guarantees it always wins that slot the instant it's on fire.
        // No-op unless on fire. See FireBucketGoal.
        this.goalSelector.addGoal(0, new FireBucketGoal(this, /* speed */ 1.4)); // sprint to water — it's on fire
        // An explicit player order (/playermob order ...) overrides autonomous behaviour.
        // Added before the other priority-1 goals so it wins the MOVE/LOOK slot while it runs;
        // no-op (canUse false) whenever there's no pending order, so normal AI is unaffected.
        this.goalSelector.addGoal(1, new CommandedActionGoal(this, /* speed */ 1.0));
        // Fell off a Dungeon Train carriage? Getting back on preempts everything
        // but swimming — added before the other priority-1 goals so its canUse is
        // evaluated first. No-op without a train mod (nearestCarriage → null).
        this.goalSelector.addGoal(1, new TrainRecoveryGoal(this, /* speed */ 1.0));
        // Social goals (flee / watch / greet) — priority 1 so they preempt
        // raiding/strolling when their reaction applies. Each self-gates on the
        // live reaction; Skeptical/Friendly also gate on "no target" so they yield to combat.
        // Flee range 10 → detectRange 16 (range + DETECT_RANGE_BONUS) covers the
        // widest fight/flight bubble (fr0 hated ≈ MAX_RANGE); the mob still only
        // flees ~10 blocks before hiding.
        this.goalSelector.addGoal(1, new FleeFromCategoryGoal(this, /* range */ 10.0F, /* walk */ 1.0, /* sprint */ 1.3));
        // Watch scan = MAX_RANGE so fr0's ~15-block skeptical ring is visible.
        this.goalSelector.addGoal(1, new SkepticalWatchGoal(this, /* watchRange */ DispositionResolver.MAX_RANGE, /* closeRange */ 4.0));
        this.goalSelector.addGoal(1, new FriendlyGreetGoal(this, /* range */ 10.0, /* approachSpeed */ 0.9));
        // Open (and, for "tidy" mobs, close) wooden doors on the path. Declares
        // no flags, so it runs alongside whatever movement goal owns the walk — it
        // only *triggers* the deliberate operation below.
        this.goalSelector.addGoal(1, new PlayerMobDoorGoal(this));
        // Holds MOVE+LOOK while a door is being operated, so opening/closing it interrupts
        // combat/movement (the mob stops, faces the door, operates it, then resumes) instead of
        // flipping the door silently mid-stride. Above the priority-2 attack goal by design.
        this.goalSelector.addGoal(1, new DoorOperationGoal(this));
        // Raise a held shield to deflect an incoming arrow. Declares no flags (like
        // the door goal) so a sword-and-board mob keeps meleeing via the priority-2
        // attack goal and blocks between swings rather than freezing. No-op without a
        // shield in hand.
        this.goalSelector.addGoal(1, new BlockArrowsGoal(this));
        // Surfaces "Digging through" in the Creative readout while the Dungeon-Train dig reflex
        // mines fill blocking the march. No flags (like PlayerMobDoorGoal) so it never evicts the
        // advance goal — the mob keeps stepping into the gap as the wall clears. No-op off a train.
        this.goalSelector.addGoal(1, new DigThroughGoal(this));
        // Carrying TNT + a way to light it? Bomb the enemy instead of trading bow/melee blows — registered
        // BEFORE the seek/attack goals at the same priority so its canUse() (config on, mobGriefing on, TNT +
        // an igniter on hand) wins the MOVE slot while armed. When it runs out of TNT/igniters its canUse()
        // goes false and the normal fight goals take back over. Gated on mobGriefing (it places + primes TNT).
        this.goalSelector.addGoal(2, new TntCombatGoal(this, /* speed */ 1.0));
        // Carrying end crystals + obsidian + solid cover blocks? Bomb the enemy with crystals instead — same
        // priority-2 slot, registered right after TntCombatGoal so TNT keeps first dibs if a mob somehow holds both
        // kits. It builds a little bunker (obsidian base + crystal, a 2-tall cover between mob and crystal), crouches
        // behind the cover with a shield up, and punches the crystal to set it off; when it runs out of the kit its
        // canUse() goes false and the normal fight goals take back over. Gated on mobGriefing (places blocks + explodes).
        this.goalSelector.addGoal(2, new EndCrystalCombatGoal(this, /* speed */ 1.0));
        // Out of ammo mid-fight? Fetch a nearby dropped round before fighting — registered BEFORE the attack
        // goal at the same priority so its narrow canUse() (ranged weapon owned, no ammo, enemy not too close,
        // a round within reach) wins the MOVE slot; otherwise the attack goal runs. After a restock its
        // canUse() goes false and the attack goal re-draws ranged. Ammo is weapon-aware (arrows for bows,
        // arrows or fireworks for crossbows). No-op when seekArrowsWhenEmpty/requireArrows is off (mob melees).
        this.goalSelector.addGoal(2, new SeekAmmoGoal(this, /* speed */ 1.0, /* scanRadius */ 10.0));
        this.goalSelector.addGoal(2, new WeaponAwareAttackGoal(this, 1.0, 8.0f));
        // Follow the one it loves (a player or another PlayerMob): priority 2 so it
        // deprioritises every own-task (raid 3, harvest 6, train-advance 7, stroll 8) to tag
        // along, yet still yields to combat — registered after the attack goal and self-gated
        // on "no target", so a target means fight and no target means follow. Joining its
        // fights is then automatic: following parks the mob beside the loved one where the
        // target goals acquire foes. See FollowLovedOneGoal / FollowLovedOnePolicy.
        this.goalSelector.addGoal(2, new FollowLovedOneGoal(this));
        // Stay-near tether: keep the mob within its anchor's radius. Priority 2 like FollowLovedOne
        // so it preempts the own-tasks (raid 3 … stroll 8) that cause wandering, yet self-gates on
        // "no target" (registered after the attack goal) so combat wins and the mob only walks back
        // once the fight ends. No-op whenever the mob has no anchor or is already inside its radius.
        this.goalSelector.addGoal(2, new StayNearGoal(this));
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
        // On a Dungeon Train, once the current carriage room is clear (combat 2,
        // raid/collect 3, harvest 6 all preempt this), march to the next room.
        // No-op off a train (the seam reports "not confined"). Below harvest so
        // "fully explore" includes farming; above idle stroll.
        this.goalSelector.addGoal(7, new AdvanceCarriageGoal(this, /* speed */ 0.9));
        // When the next room is across a group gap (AdvanceCarriageGoal stops), leap the
        // gap to the adjacent group and keep marching. Same priority/flags as the advance
        // goal; mutually exclusive because it only fires when the within-group target is
        // null. No-op off a train.
        this.goalSelector.addGoal(7, new CrossGroupGapGoal(this, /* speed */ 0.9));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, LivingEntity.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Defend an individual it loves: registered right after HurtByTargetGoal at
        // the same priority, so self-defence wins the TARGET-flag tie but defending
        // a friend still outranks proactively hunting a random hostile (priority 2).
        this.targetSelector.addGoal(1, new DefendLovedOneGoal(this, DispositionResolver.MAX_RANGE));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
            this,
            LivingEntity.class,
            10,
            true,
            false,
            // 26.x: the target selector is a two-arg TargetingConditions.Selector
            // (entity, ServerLevel); the level is unused here.
            //? if >=26 {
            /*(candidate, serverLevel) -> !recovering
                && !this.crossingGap
                && reactionToward(candidate) == Reaction.FIGHT
                && TrainConfinement.allowsTarget(this, candidate)));
            *///?} else {
            candidate -> !recovering
                && !this.crossingGap
                && reactionToward(candidate) == Reaction.FIGHT
                && TrainConfinement.allowsTarget(this, candidate)));
            //?}
        // Hunt food animals only while hungry, and below the hostile-targeting
        // goal (2) so defending against a zombie always beats chasing a cow.
        this.targetSelector.addGoal(3, new HuntForFoodGoal(this));
    }

    /**
     * Central held-target sweep, covering every target source (hostile,
     * retaliation, hunt) in one place — clearing the target also makes the
     * priority-2 {@code WeaponAwareAttackGoal} stand down. Drops a target that:
     *
     * <ul>
     *   <li>has switched to <b>Creative or Spectator</b> mid-engagement (or was
     *       acquired by the vanilla {@link HurtByTargetGoal} retaliation path,
     *       which bypasses {@link TargetCategory#classify}) — see
     *       {@link #isIgnoredPlayer}; or</li>
     *   <li>has <b>left the Dungeon Train</b> carriage the mob is riding.
     *       Acquisition is already filtered (the priority-2 target goal and
     *       {@link HuntForFoodGoal}); this sweep additionally drops a target that
     *       <em>wanders</em> off. No-op unless a train mod is installed and the
     *       mob is on a train: {@link TrainConfinement#allowsTarget}
     *       short-circuits to {@code true} otherwise.</li>
     * </ul>
     */
    @Override
    public void tick() {
        super.tick();
        // The arm-swing animation progress (swingTime → attackAnim) wasn't advancing for this
        // entity — it froze at the start of a swing, so punch/attack swings never animated. Advance
        // it each tick (both sides) so commanded and combat swings play out like a real player's.
        updateSwingTime();
        resolvePendingSkinPlayerName();
    }

    /**
     * First-server-tick skin settling. Two jobs, both server-side (this field/flag are never set on the
     * client), each a one-shot no-op once done. Entity has no reliable "just added to a ServerLevel" hook in
     * this version, so this piggybacks on the first tick.
     *
     * <ol>
     *   <li>Resolve a {@link #pendingSkinPlayerName} (a {@code SkinPlayerName} the egg / {@code /summon} NBT
     *       named but that wasn't already cached) off-thread via {@link SkinNameApplier}.</li>
     *   <li>Run the {@code customSkinChance} URL roll deferred from {@link #finalizeSpawn} for a spawn egg
     *       (see {@link #eggAwaitingSkinRoll}), but only if the egg specified no skin of its own — no pending
     *       name and still the empty (bundled-index) URL. A name or authored URL wins.</li>
     * </ol>
     */
    private void resolvePendingSkinPlayerName() {
        if (pendingSkinPlayerName != null && level() instanceof ServerLevel serverLevel) {
            String name = pendingSkinPlayerName;
            pendingSkinPlayerName = null;
            SkinNameApplier.apply(serverLevel.getServer(), name, this);
        }
        if (eggAwaitingSkinRoll && level() instanceof ServerLevel serverLevel) {
            eggAwaitingSkinRoll = false;
            // Only roll when the egg carried no skin directive: a SkinPlayerName leaves pending set
            // (handled above) or already baked a URL; an authored SkinTextureUrl leaves a non-empty URL.
            if (pendingSkinPlayerName == null && getSkinTextureUrl().isEmpty()) {
                rollCustomSkinUrl(serverLevel.getRandom());
            }
        }
    }

    @Override
    //? if >=26 {
    /*protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
    *///?} else {
    protected void customServerAiStep() {
        super.customServerAiStep();
    //?}
        LivingEntity target = getTarget();
        // The mob dropping a target for its OWN reasons (climbing back onto a train, or a
        // creative/spectator player it ignores) is not the player getting away — flag it so the
        // escape check below skips this tick. A TrainConfinement drop is NOT such a reason: on
        // Dungeon Train, putting carriages between you and an echo is exactly how you run away.
        boolean droppedByHousekeeping = false;
        if (target != null
                && (recovering || isIgnoredPlayer(target) || !TrainConfinement.allowsTarget(this, target))) {
            droppedByHousekeeping = recovering || isIgnoredPlayer(target);
            setTarget(null);   // recovering back onto a train preempts all combat
        }
        // Whoever this mob is hunting, it picked that fight — latch it against the player so the
        // blows they land back are scored as self-defence (PlayerLifeRecord.DEFENSIVE_SCALE).
        // Read after the validity block above, so a target this tick rejected never counts.
        if (getTarget() instanceof Player hunted) {
            feelings.markProvoked(hunted.getUUID());
            lastPlayerTarget = hunted;
        } else if (lastPlayerTarget != null) {
            // Falling edge of the same read: it was hunting them and now it isn't. If they are
            // still standing and never hit it back, they got away — bank Flight, once per pairing.
            Player fled = lastPlayerTarget;
            lastPlayerTarget = null;
            if (!droppedByHousekeeping) {
                creditEscape(fled);
            }
        }
        tickAttackOrder();   // enforce a commanded attack's stop limit (duration / hearts / hit-back)
        tickOrderTimeout();  // abandon a movement order that outlived its timeout (default 2 min)
        latchTrainExploreDirection();

        // Tick down the door-close hold (armed by stuck-recovery, on or off a train) so a door it
        // just closed isn't reopened until the mob has had time to cross the now-clear path.
        if (doorCloseHoldTicks > 0) {
            doorCloseHoldTicks--;
        }
        // Advance any in-progress deliberate door operation (face the door, then open/close it).
        // After super.customServerAiStep() ticked the goals, so this look wins — like the iron gaze.
        tickDoorOperation();

        if (TrainConfinement.isConfined(this)) {
            // Remember we're aboard, so TrainRecoveryGoal can tell "fell off" from
            // "never boarded" (see ticksSinceOnTrain / RECOVERY_WINDOW_TICKS).
            lastOnTrainTick = tickCount;
            // Open any door we're up against — and, when wedged, close one whose open swing is
            // blocking us. Vanilla's DoorInteractGoal opens doors by inspecting nav path nodes +
            // collision, which doesn't fire on a moving Sable carriage — so the train seam reaches
            // for the door block directly (in the carriage's own coordinate space), every tick,
            // regardless of which goal owns movement.
            TrainConfinement.openBlockingDoor(this);
            // ...and, when wedged against soft fill (ice/dirt/mud/moss/logs) packing a carriage,
            // mine it to clear the march. Like the door reflex it reaches into the carriage's
            // sub-level block space (the fill doesn't sit at the mob's world position), so it can't
            // be an ordinary block-breaking goal. No-op without Dungeon Train.
            TrainConfinement.digObstructingBlock(this);
            // Travel-with-a-loved-player: while a player it loves rides the same train, redirect the
            // march toward that player's carriage instead of the fixed boarding direction (see
            // effectiveTrainMarchDir). Throttled — the two march goals read the cached value every tick.
            if (this.tickCount % LOVED_MARCH_REFRESH_TICKS == 0) {
                refreshLovedMarchOverride();
            }
        } else {
            hasLovedMarchOverride = false;   // off a train ⇒ no redirect (the march goals don't run here)
            setDigging(false);   // off a train ⇒ never mid-dig (the reflex that clears it doesn't run here)
            // Off a train, opening is handled by PlayerMobDoorGoal; this reflex adds the
            // close-when-stuck half — an open door can block the perpendicular path.
            recoverFromStuckDoor();
        }

        // Refresh the Creative-only objective readout (synced to clients for the
        // under-name visualisation + right-click menu). Throttled — goal/phase
        // transitions are infrequent, so a quarter-second lag is imperceptible —
        // and written only on change, so it costs a tracking packet only when the
        // text actually changes.
        if (this.tickCount % 5 == 0) {
            String readout = ObjectiveReadout.of(this.goalSelector, this.targetSelector);
            if (!readout.equals(this.entityData.get(DATA_OBJECTIVES))) {
                this.entityData.set(DATA_OBJECTIVES, readout);
            }
        }
    }

    /**
     * Off-train door recovery: when the mob has been wedged for a while ({@link DoorStuckMonitor})
     * while actively pathing, close the open hand-closable door that's actually blocking the way it
     * is going ({@link DoorObstruction}) and arm the hold so it isn't reopened before the mob can
     * cross. An open door's panel swings across the perpendicular edge of its cell, which vanilla
     * pathing treats as passable — so a mob whose route turns at the doorway jams against it, and
     * closing clears the way. Opening a closed door on the path is {@link PlayerMobDoorGoal}'s job;
     * this half only clears the perpendicular jam. On a train the Dungeon-Train reflex does the
     * whole job (open or close) in the carriage's coordinate space, so this branch never runs while
     * confined.
     */
    private void recoverFromStuckDoor() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Track the heading every tick (world frame off a train) so it's latched before the mob
        // stalls against a panel — measured the same way as the stuck signal below.
        Direction.Axis travelAxis = DoorObstruction.travelAxis(this, getX(), getZ());
        boolean tryingToMove = !getNavigation().isDone();
        if (!offTrainDoorStuck.tick(getX(), getZ(), tryingToMove) || travelAxis == null) {
            return;
        }
        DoorObstruction.Obstruction blocking = DoorObstruction.nearestObstructing(
            serverLevel, blockPosition(), DOOR_RECOVERY_REACH, travelAxis, DoorObstruction.OPEN_HAND_DOOR);
        if (blocking != null) {
            BlockPos pos = blocking.pos();
            Vec3 eye = getEyePosition();
            beginDoorOperation(
                pos.getX() + 0.5 - eye.x, pos.getY() + 0.5 - eye.y, pos.getZ() + 0.5 - eye.z,
                () -> DoorObstruction.setOpen(this, serverLevel, pos, false));
            holdDoorsClosed();
        }

        tickFeelingEvents();
    }

    /**
     * The positive feeling-events (Phase B), driven off one throttled nearby scan.
     * A per-entity phase offset ({@code + getId()}) staggers a co-located group so
     * they don't all scan the same tick. One {@link Reaction#GREET}-style sweep feeds
     * encounter / crouch / travel; {@link #checkDefended} uses the vanilla hurt-by tracking
     * (no scan), and witnessed attacks are credited at the damage event (see
     * {@link #witnessAttack}). All changes are batched into one
     * {@link #pushDispositionToClient} so the open menu updates live.
     */
    private void tickFeelingEvents() {
        if ((this.tickCount + getId()) % SOCIAL_SCAN_INTERVAL != 0) {
            return;
        }
        boolean changed = false;

        int carriage = TrainConfinement.carriageIndex(this);
        boolean onTrain = carriage != TrainConfinement.NO_CARRIAGE;

        List<LivingEntity> nearby = level().getEntitiesOfClass(LivingEntity.class,
            getBoundingBox().inflate(SOCIAL_SCAN_RANGE), e -> e != this && e.isAlive());

        // Rebuild the crouch-state map from who's in range now, so it never retains
        // logged-out / wandered-off players.
        Map<UUID, Boolean> nextCrouch = new HashMap<>();

        for (LivingEntity e : nearby) {
            if (TargetCategory.classify(e) != TargetCategory.PLAYERS) {
                continue;
            }
            UUID id = e.getUUID();

            // Encounter — remember everyone seen (roster).
            if (hasLineOfSight(e)) {
                changed |= feelings.encounter(id);
            }

            // Crouch — a player OR PlayerMob crouching AT this mob (facing it, in
            // sight) greets it. Counts on the rising edge of "crouching + looking at
            // me", so it fires once when the croucher both sneaks and turns to face
            // the mob — and a greeting PlayerMob, whose head is turned to its target,
            // registers between mobs too.
            boolean greetCrouch = e.isCrouching() && crouchTargetsMe(e);
            nextCrouch.put(id, greetCrouch);
            Boolean was = crouchHeld.get(id);
            if (greetCrouch && (was == null || !was)) {
                // Friendlier mobs warm faster per bow (DispositionResolver.kindnessScale).
                changed |= feelings.crouch(id, DispositionResolver.kindnessScale(friendliness()));
                // Credit the real player's lifetime kindness for the greeting gesture.
                if (e instanceof ServerPlayer sp) {
                    PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.CROUCH, 0);
                }
            }

            // Travel-together — same train, advancing carriages, not in combat with them.
            if (onTrain && getTarget() != e && TrainConfinement.allowsTarget(this, e)) {
                if (feelings.travel(id, carriage)) {
                    changed = true;
                    if (e instanceof ServerPlayer sp) {
                        PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.TRAVEL, 0);
                    }
                }
            }
            // Witnessed attacks are no longer polled here — they're credited at the moment damage
            // lands (LivingHurtWitnessMixin -> WitnessedAttacks -> witnessAttack), so admiration
            // accumulates per real hit instead of missing most to i-frames / flee / one-attacker slot.
        }

        crouchHeld.clear();
        crouchHeld.putAll(nextCrouch);

        changed |= checkDefended();

        if (changed) {
            pushDispositionToClient();
        }
    }

    /**
     * Defended-me event: if the mob is currently being attacked by a hostile mob and
     * something attacked <em>that</em> hostile, credit the attacker (a player /
     * PlayerMob) with a defence. Scoped to hostile attackers so a player-vs-player
     * brawl doesn't read as "defending me". Debounced per-individual in the ledger.
     */
    private boolean checkDefended() {
        LivingEntity myAttacker = getLastHurtByMob();
        if (myAttacker == null || !myAttacker.isAlive()) {
            return false;
        }
        if (TargetCategory.classify(myAttacker) != TargetCategory.HOSTILE_MOBS) {
            return false;
        }
        LivingEntity defender = myAttacker.getLastHurtByMob();
        if (defender == null || defender == this
                || TargetCategory.classify(defender) != TargetCategory.PLAYERS) {
            return false;
        }
        // Friendlier mobs value being defended more (DispositionResolver.kindnessScale).
        boolean defended = feelings.defend(defender.getUUID(),
            myAttacker.getLastHurtByMobTimestamp(), DispositionResolver.kindnessScale(friendliness()));
        if (defended && defender instanceof ServerPlayer sp) {
            PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.DEFEND, 0);
        }
        return defended;
    }

    /**
     * Register a witnessed attack: {@code attacker} just landed real damage on {@code victim}
     * (both players/PlayerMobs, neither this mob). Forms a feeling toward the attacker — admiration
     * for violence this mob approves of, resentment for harming someone it loves — scaled by this
     * mob's fight/flight and its feeling toward the victim
     * ({@link DispositionResolver#witnessedAttackDelta}). Debounced per attacker on the victim's
     * hit-tick so one blow counts once. Called server-side from {@code WitnessedAttacks} (driven by
     * {@link games.brennan.playermob.mixin.LivingHurtWitnessMixin}).
     */
    public void witnessAttack(LivingEntity attacker, LivingEntity victim) {
        float delta = DispositionResolver.witnessedAttackDelta(traits.fightFlight(),
            feelings.feelingToward(victim.getUUID()));
        if (delta != 0.0F
                && feelings.witness(attacker.getUUID(), delta, victim.getLastHurtByMobTimestamp())) {
            // A real player harming someone this mob loves (negative delta) feeds their
            // lifetime cruelty — the reincarnation-tracking heir to the old harm poll.
            if (delta < 0.0F && attacker instanceof ServerPlayer sp) {
                PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.HARM, 0);
            }
            pushDispositionToClient();
        }
    }

    /**
     * Whether {@code croucher} is directing a crouch at THIS mob — it must see the
     * mob and have its head turned toward it (within {@link #CROUCH_LOOK_CONE_DEGREES}
     * of facing). Keeps a crouch from counting unless aimed at the mob, and lets a
     * greeting PlayerMob (head turned to its friend) register the gesture mob-to-mob.
     */
    private boolean crouchTargetsMe(LivingEntity croucher) {
        if (!croucher.hasLineOfSight(this)) {
            return false;
        }
        double dx = getX() - croucher.getX();
        double dz = getZ() - croucher.getZ();
        float yawToMe = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        return Mth.degreesDifferenceAbs(croucher.getYHeadRot(), yawToMe) <= CROUCH_LOOK_CONE_DEGREES;
    }

    /**
     * The first server tick the mob is found on a train, fix its march direction
     * from the sign of its boarding carriage index and keep it (see
     * {@link #trainExploreDir}). Once latched ({@code != 0}) this short-circuits,
     * so the train seam isn't queried every tick. No-op without a train mod
     * ({@link TrainConfinement#isConfined} is always false) — zero cost. The
     * carriage-index call self-checks its geometry and returns
     * {@link TrainConfinement#NO_CARRIAGE} if it can't be trusted, in which case
     * we leave the direction unlatched and try again next tick.
     */
    private void latchTrainExploreDirection() {
        if (trainExploreDir != 0 || !TrainConfinement.isConfined(this)) {
            return;
        }
        // A spawned-together pair (see maybeSpawnFriendPair) shares one march direction: if
        // our partner has already latched, copy it — so collision drift across carriage 0 in
        // the first ticks after spawn can't split the pair onto opposite headings.
        Integer shared = partnerExploreDir();
        if (shared != null) {
            trainExploreDir = shared;
            return;
        }
        int idx = TrainConfinement.carriageIndex(this);
        if (idx != TrainConfinement.NO_CARRIAGE) {
            trainExploreDir = TrainConfinement.boardingDirection(idx);
        }
    }

    /**
     * The pair partner's latched march direction ({@code -1}/{@code +1}), or {@code null} if
     * this mob has no {@link #trainPairPartner}, the partner isn't loaded, or it hasn't
     * latched yet. Whichever of the pair latches first decides; the other copies it, so the
     * two always march the train the same way.
     */
    private Integer partnerExploreDir() {
        if (trainPairPartner == null || !(level() instanceof ServerLevel server)) {
            return null;
        }
        return server.getEntity(trainPairPartner) instanceof PlayerMobEntity partner
                && partner.trainExploreDir != 0
            ? partner.trainExploreDir
            : null;
    }

    /**
     * Fixed Dungeon-Train march direction ({@code -1}/{@code +1}), or {@code 0} if
     * the mob hasn't boarded a train yet. Read each tick by
     * {@link AdvanceCarriageGoal}. See {@link #trainExploreDir}.
     */
    public int getTrainExploreDir() {
        return this.trainExploreDir;
    }

    /**
     * Set the fixed Dungeon-Train march direction explicitly, overriding the
     * {@linkplain #latchTrainExploreDirection boarding latch}. A spawner that places a mob with an
     * intended heading — e.g. Dungeon Train's behind-the-player spawn, which wants the mob to march
     * the player's own travel direction rather than the default toward-carriage-0 route — calls this
     * right after spawn. Because the latch short-circuits once {@link #trainExploreDir} is non-zero,
     * the value set here is kept, not recomputed from the boarding carriage. Stored as a sign
     * ({@code -1}/{@code 0}/{@code +1}); {@code 0} leaves the mob unlatched so the latch picks normally.
     *
     * @param dir desired march direction; only its sign is used
     */
    public void setTrainExploreDir(int dir) {
        this.trainExploreDir = Integer.signum(dir);
    }

    /**
     * The Dungeon-Train march direction the {@link AdvanceCarriageGoal} / {@link CrossGroupGapGoal}
     * actually follow: when a loved player rides the same train (refreshed in
     * {@link #customServerAiStep}), the step toward that player's carriage — {@code +1}/{@code -1}, or
     * {@code 0} once in the same carriage so both goals idle — otherwise the fixed latched
     * {@link #trainExploreDir}. This is the "abandon the fixed march to travel with you" redirect;
     * {@code 0} is exactly how the unlatched default already no-ops, so the goals only swap which getter
     * they read.
     */
    public int effectiveTrainMarchDir() {
        return hasLovedMarchOverride ? lovedMarchDir : this.trainExploreDir;
    }

    /**
     * Refresh the {@linkplain #effectiveTrainMarchDir loved-player march redirect}: if the feature is
     * enabled and a player this mob loves ({@code feeling >=} {@link DispositionResolver#FEELING_LOVE})
     * rides the same train within {@link FollowLovedOnePolicy#SCAN_RANGE}, latch the step toward that
     * player's carriage ({@code 0} when already together); otherwise clear the redirect so the mob
     * resumes its fixed march. Called on a throttle from {@link #customServerAiStep} while confined.
     */
    private void refreshLovedMarchOverride() {
        if (!PlayerMobConfig.trainFollowLovedPlayer()) {
            hasLovedMarchOverride = false;
            return;
        }
        Player loved = nearestLovedPlayerAboard();
        if (loved == null) {
            hasLovedMarchOverride = false;
            return;
        }
        int mine = TrainConfinement.carriageIndex(this);
        int theirs = TrainConfinement.carriageIndex(loved);
        if (mine == TrainConfinement.NO_CARRIAGE || theirs == TrainConfinement.NO_CARRIAGE) {
            hasLovedMarchOverride = false;
            return;
        }
        lovedMarchDir = FollowLovedOnePolicy.marchDirectionToward(mine, theirs);
        hasLovedMarchOverride = true;
    }

    /**
     * The nearest player this mob loves ({@code feeling >=} {@link DispositionResolver#FEELING_LOVE})
     * on the same train within {@link FollowLovedOnePolicy#SCAN_RANGE}, or {@code null} if none. The
     * {@link TrainConfinement#allowsTarget same-train gate} is what keeps the redirect from walking the
     * mob off the train — it only ever heads toward a player still aboard. Player-only and mirrors
     * {@link #findFollowTarget}'s scan; mutual-mob travel stays with {@link FollowLovedOneGoal}.
     */
    private Player nearestLovedPlayerAboard() {
        double range = FollowLovedOnePolicy.SCAN_RANGE;
        AABB box = getBoundingBox().inflate(range);
        double rangeSq = range * range;
        Player best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Player player : level().getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive()) continue;
            if (feelingToward(player) < DispositionResolver.FEELING_LOVE) continue;
            if (!TrainConfinement.allowsTarget(this, player)) continue;
            double distSq = distanceToSqr(player);
            if (distSq <= rangeSq && distSq < bestDistSq) {
                best = player;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    /**
     * Ticks since this mob was last on a train carriage (large when it never has).
     * {@link TrainRecoveryGoal} compares it against {@link #RECOVERY_WINDOW_TICKS}
     * so recovery fires only for a mob that recently fell off a train.
     */
    public int ticksSinceOnTrain() {
        return tickCount - lastOnTrainTick;
    }

    /**
     * Marks whether {@link TrainRecoveryGoal} is actively recovering this mob (set on
     * its start, cleared on its stop). While {@code true}, combat is suppressed so
     * getting back aboard is the mob's only focus (see {@link #recovering}).
     */
    public void setRecovering(boolean recovering) {
        this.recovering = recovering;
    }

    /** True while actively climbing back onto a train (see {@link #recovering}). */
    public boolean isRecovering() {
        return this.recovering;
    }

    /**
     * Marks whether the Dungeon-Train dig-through reflex is currently mining a blocking fill block
     * (set/cleared per tick by {@code DungeonTrainEnvironment#digObstructingBlock}). Drives
     * {@link DigThroughGoal}'s readout. See {@link #digging}.
     */
    public void setDigging(boolean digging) {
        this.digging = digging;
    }

    /** True while actively mining through a blocked Dungeon-Train carriage (see {@link #digging}). */
    public boolean isDigging() {
        return this.digging;
    }

    /**
     * Set by {@link CrossGroupGapGoal} for the duration of a cross-gap leap. While
     * {@code true}, the mob declines new combat targets so the leap can't be preempted.
     * See {@link #crossingGap}.
     */
    public void setCrossingGap(boolean crossingGap) {
        this.crossingGap = crossingGap;
    }

    /** True while actively leaping a Dungeon-Train group gap (see {@link #crossingGap}). */
    public boolean isCrossingGap() {
        return this.crossingGap;
    }

    /**
     * Set by {@link FleeFromCategoryGoal} while the mob is fleeing a Shy threat. While
     * {@code true}, {@link BlockArrowsGoal} won't raise the shield — blocking (which faces
     * the threat and stalls) fights the retreat. See {@link #fleeing}.
     */
    public void setFleeing(boolean fleeing) {
        this.fleeing = fleeing;
    }

    /** True while actively fleeing a Shy threat (see {@link #fleeing}). */
    public boolean isFleeing() {
        return this.fleeing;
    }

    /**
     * Auto-equip the best tool the mob owns for breaking {@code state} (highest
     * {@link ItemStack#getDestroySpeed}), swapping it into the main hand and
     * stashing the displaced item. Returns {@code true} if a swap happened — so the
     * caller can add a short "reach for the tool" pause. No-op (returns {@code false})
     * when the main hand is already the fastest, or nothing beats an empty hand.
     */
    public boolean equipBetterToolFor(BlockState state) {
        float bestSpeed = getMainHandItem().getDestroySpeed(state);
        int bestSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack candidate = inventory.getItem(i);
            if (candidate.isEmpty()) continue;
            float speed = candidate.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack tool = inventory.getItem(bestSlot).copy();
        ItemStack previous = getMainHandItem();
        inventory.setItem(bestSlot, ItemStack.EMPTY);
        setItemSlot(EquipmentSlot.MAINHAND, tool);
        if (!previous.isEmpty()) {
            ItemStack leftover = EquipmentEvaluator.addToContainer(inventory, previous);
            if (!leftover.isEmpty()) dropAtLocation(leftover);
        }
        return true;
    }

    /**
     * Players in Creative or Spectator mode are ignored by all AI — the mob
     * treats them as not present. {@link TargetCategory#classify} returns
     * {@code null} for them (covering proactive targeting and the social goals);
     * this helper covers the two paths {@code classify} can't — the vanilla
     * {@link HurtByTargetGoal} retaliation target ({@link #customServerAiStep})
     * and the {@link #hurt} provoke/retaliate flip. {@code isCreative()} matches
     * Creative only; {@code isSpectator()} matches Spectator only.
     */
    private static boolean isIgnoredPlayer(LivingEntity entity) {
        return entity instanceof Player player && (player.isCreative() || player.isSpectator());
    }

    /**
     * Roll the random skin at spawn so all clients see the same value.
     * Server-side; syncs to clients via SynchedEntityData.
     *
     * <p>Always rolls a bundled-vanilla index first — it's both the (default)
     * ~60% common case (see {@link PlayerMobConfig#customSkinChance()}) and the
     * downgrade-safe payload for 0.2.0 clients. Then, with probability
     * {@link PlayerMobConfig#customSkinChance()}, overrides it with a Mojang URL
     * skin from {@link PlayerMobSkinRegistry}. The rest keep the bundled
     * vanilla skin (URL left blank ⇒ renderer uses the index path). An
     * empty registry always falls through to the bundled skin.</p>
     */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor world,
                                        DifficultyInstance difficulty,
                                        //? if >=26 {
                                        /*EntitySpawnReason reason,
                                        *///?} else {
                                        MobSpawnType reason,
                                        //?}
                                        SpawnGroupData data
                                        //? if <1.21.1 {
                                        /*, net.minecraft.nbt.CompoundTag dataTag
                                        *///?}
                                        ) {
        // Resolve any datapack skin entries that named a player (off-thread, cached, idempotent),
        // so this and later spawns can draw the resolved skins. Cheap no-op once all are resolved.
        PlayerMobSkinRegistry.ensureResolved(world.getLevel().getServer());
        // Dungeon Train spawns PlayerMobs via finalizeSpawn(..., EVENT, ...); give those
        // a chance to embody a stored past life instead of a fresh random mob. Applying the
        // snapshot here (before the rolls) pins skin + traits explicit, so the rolls below
        // skip — same path as the reincarnation egg. Skipped when a skin is already loaded
        // (a Skin* summon), so it never clobbers an explicit identity.
        ReincarnationRecord echo = isEventSpawn(reason) && !skinExplicit
            ? PlayerReincarnation.maybeReincarnateOnSpawn(this, world) : null;
        // A spawn egg's entity_data is merged AFTER finalizeSpawn on 1.21.1 (vanilla
        // EntityType.create runs finalizeSpawn, THEN the stack-config consumer / CustomData.loadInto
        // → readCustomTag). So a URL skin rolled here would be pre-saved by loadInto and then mistaken
        // for an authored SkinTextureUrl in readCustomTag, clobbering the egg's SkinPlayerName. Roll
        // only the bundled index for egg spawns and defer the URL roll to the first tick (see
        // eggAwaitingSkinRoll / resolvePendingSkinPlayerName), by when the egg's skin directive
        // (a name or an authored URL) is known and can suppress the roll.
        rollSpawnDefaults(world.getRandom(), echo != null, !isSpawnEggSpawn(reason));
        this.eggAwaitingSkinRoll = isSpawnEggSpawn(reason);
        boolean companion = maybeSpawnFriendPair(world, reason, echo);
        spawnPetEchoes(world, echo);
        if (isEventSpawn(reason)) {
            DtSpawnDebug.report(world.getLevel(), this, echo != null, companion);
        }
        this.naturalOrigin = ScavengeMode.isNaturalOrigin(reason.name());
        this.naturalOriginResolved = true;
        maybeAutoName(reason.name());
        //? if >=1.21.1 {
        return super.finalizeSpawn(world, difficulty, reason, data);
        //?} else {
        /*return super.finalizeSpawn(world, difficulty, reason, data, dataTag);*///?}
    }

    /**
     * Suppress (or restore) the generic skin-source auto-name for this mob's {@code finalizeSpawn}. Used by
     * {@link PlayerMobSummon} so {@code /playermob summon} can name the mob after its real skin lands instead.
     */
    public void setDeferAutoName(boolean defer) {
        this.deferAutoName = defer;
    }


    /**
     * If the configured {@link AutoNameMode} covers this spawn's category, give the mob a nameplate drawn
     * from its skin source (local filename / online displayName; see {@link SkinDisplayName}). A no-op when
     * naming is deferred to the caller, the mob is already named, the mode doesn't cover the category, or the
     * skin has no source name (a bundled default). Called from {@link #finalizeSpawn} once the skin is rolled.
     *
     * @param reasonName the spawn reason's enum-constant name ({@code reason.name()}), version-agnostic
     */
    private void maybeAutoName(String reasonName) {
        if (deferAutoName || hasCustomName()) {
            return;
        }
        if (!PlayerMobConfig.autoNameMode().covers(AutoNameMode.categorize(reasonName))) {
            return;
        }
        SkinDisplayName.resolve(getSkinTextureUrl()).ifPresent(this::applyNameplate);
    }

    /**
     * Give the mob a visible nameplate, unless it already has a custom name. Public so {@code /playermob
     * summon} can label the mob with its source ({@code <name|file>}) once a deferred skin has been applied.
     */
    public void applyNameplate(String name) {
        if (hasCustomName()) {
            return;
        }
        setCustomName(Component.literal(name));
        setCustomNameVisible(true);
    }

    /**
     * The per-mob spawn rolls — skin (unless pinned by loaded NBT), the two locked traits,
     * and (unless {@code reincarnated}) the door-closing personality — then a client sync. Extracted from
     * {@link #finalizeSpawn} so a {@link #maybeSpawnFriendPair} companion, which is created
     * with {@code EntityType.create} (and so never runs {@code finalizeSpawn}), still gets a
     * normal mob's randomised look and personality.
     *
     * @param rollUrlSkin whether to draw a {@code customSkinChance} online/local URL skin now. False for
     *                    spawn eggs, whose {@code entity_data} is merged AFTER this runs — a URL rolled
     *                    here would be mistaken for an authored skin and clobber the egg's SkinPlayerName.
     *                    Those defer the URL roll to the first tick (see {@link #resolvePendingSkinPlayerName}).
     */
    private void rollSpawnDefaults(RandomSource random, boolean reincarnated, boolean rollUrlSkin) {
        // Keep a skin already loaded from NBT (a reincarnation egg's snapshot, or a
        // /summon with a Skin* tag).
        if (!skinExplicit) {
            setSkinIndex(random.nextInt(SKIN_COUNT));
            // Bundled defaults: roll the arm model independently of the name, ~50/50.
            // Vanilla DefaultPlayerSkin ships every default name in both wide and slim
            // and picks (name × model) uniformly by UUID hash, so there's no canonical
            // per-name model — we mirror that coin-flip. A URL skin (below) overrides
            // this with its own authored model.
            setSkinSlim(random.nextBoolean());
            if (rollUrlSkin) {
                rollCustomSkinUrl(random);
            }
        }
        // Roll any trait not pinned by a spawn egg's entity_data or /summon NBT
        // (an archetype egg / partial summon leaves the rest to chance).
        traits.rollIfUnset(random);
        pushDispositionToClient();
        // Roll the door-closing personality (~50% close behind, ~50% leave open),
        // unless a reincarnation already restored it from the past life's snapshot.
        if (!reincarnated) {
            this.closesDoors = random.nextBoolean();
        }
    }

    /**
     * With probability {@link PlayerMobConfig#customSkinChance()}, override the bundled index skin with a
     * URL skin drawn uniformly across the enabled online-registry + local-folder pool via the pure
     * {@link SkinSourceSelector}. A BUNDLED outcome leaves the already-rolled index untouched. Shared by
     * {@link #rollSpawnDefaults} and the deferred egg roll on the first tick
     * ({@link #resolvePendingSkinPlayerName}). Snapshots both lists once so the selector's index matches.
     */
    private void rollCustomSkinUrl(RandomSource random) {
        List<PlayerMobSkin> online = PlayerMobSkinRegistry.all();
        List<String> local = LocalSkinFolder.list();
        SkinSourceSelector.Choice choice = SkinSourceSelector.choose(
            PlayerMobConfig.skinSourceBundled(),
            PlayerMobConfig.skinSourceOnline(),
            PlayerMobConfig.skinSourceLocal(),
            online.size(), local.size(), PlayerMobConfig.customSkinChance(),
            random::nextInt, () -> random.nextFloat());
        switch (choice.kind()) {
            case ONLINE -> {
                PlayerMobSkin skin = online.get(choice.index());
                setSkinTextureUrl(skin.textureUrl());
                setSkinSlim(skin.model() == SkinModel.SLIM);
            }
            case LOCAL -> setSkinTextureUrl(LocalSkinRef.encode(local.get(choice.index())));
            case BUNDLED -> { /* keep the bundled index rolled above */ }
        }
    }

    /**
     * On a Dungeon-Train ({@link MobSpawnType#EVENT}) spawn, maybe spawn one companion PlayerMob
     * beside this one as mutual max friends (see {@link #linkAsFriends}). Two flavours:
     *
     * <ul>
     *   <li><b>Echo with logged friends</b> ({@code echo != null}, non-empty {@code friendSnapshots}):
     *       with probability {@link PlayerMobConfig#echoFriendChance()}, bring back a {@linkplain
     *       #spawnFriendEcho friend-echo} of someone who actually loved this life — rebuilt with their
     *       last-seen gear.</li>
     *   <li><b>Anything else</b> (a fresh non-echo mob): with probability {@link #DT_PAIR_CHANCE}, a
     *       {@linkplain #spawnRandomFriend simple random buddy}. An echo whose life had no logged
     *       friend spawns alone.</li>
     * </ul>
     *
     * <p>Either companion is built with {@code EntityType.create} + {@code addFreshEntity}, which does
     * <em>not</em> invoke {@link #finalizeSpawn} — so it neither re-rolls a pair nor recurses, and a
     * spawn yields exactly a duo, never a chain. Egg / {@code /summon} spawns aren't {@code EVENT},
     * so this is a no-op for them.</p>
     *
     * @return whether a companion (friend or friend-echo) actually spawned — used by the
     *     {@link DtSpawnDebug} readout to colour the spawn message.
     */
    /**
     * Whether {@code reason} is the Dungeon-Train {@code EVENT} spawn. Centralises the
     * spawn-reason enum, which MC 26.x renamed {@code MobSpawnType} → {@code EntitySpawnReason}.
     */
    //? if >=26 {
    /*private static boolean isEventSpawn(EntitySpawnReason reason) {
        return reason == EntitySpawnReason.EVENT;
    }
    *///?} else {
    private static boolean isEventSpawn(MobSpawnType reason) {
        return reason == MobSpawnType.EVENT;
    }
    //?}

    /**
     * Whether {@code reason} is a spawn-egg spawn. Its {@code entity_data} is merged AFTER
     * {@code finalizeSpawn}, so the URL-skin roll is deferred to the first tick for these (see
     * {@link #eggAwaitingSkinRoll}). Version-bridged like {@link #isEventSpawn}.
     */
    //? if >=26 {
    /*private static boolean isSpawnEggSpawn(EntitySpawnReason reason) {
        // 26.x renamed MobSpawnType.SPAWN_EGG → EntitySpawnReason.SPAWN_ITEM_USE.
        return reason == EntitySpawnReason.SPAWN_ITEM_USE;
    }
    *///?} else {
    private static boolean isSpawnEggSpawn(MobSpawnType reason) {
        return reason == MobSpawnType.SPAWN_EGG;
    }
    //?}

    private boolean maybeSpawnFriendPair(ServerLevelAccessor world,
                                         //? if >=26 {
                                         /*EntitySpawnReason reason,
                                         *///?} else {
                                         MobSpawnType reason,
                                         //?}
                                         ReincarnationRecord echo) {
        if (!isEventSpawn(reason)) {
            return false;                             // only natural Dungeon-Train spawns pair up
        }
        RandomSource random = world.getRandom();
        if (echo != null) {
            List<CompoundTag> friends = echo.friendSnapshots();
            if (friends.isEmpty() || random.nextFloat() >= PlayerMobConfig.echoFriendChance()) {
                return false;                         // no one loved this life, or the roll missed
            }
            return spawnFriendEcho(world, friends.get(random.nextInt(friends.size())));
        }
        if (random.nextFloat() < DT_PAIR_CHANCE) {
            return spawnRandomFriend(world);
        }
        return false;
    }

    /**
     * Build a fresh {@code PlayerMobEntity} for {@code level}. MC 26.x dropped the
     * single-argument {@code EntityType.create(Level)} in favour of
     * {@code create(Level, EntitySpawnReason)} — a {@code MOB_SUMMONED} reason matches the
     * pre-26 behaviour for these programmatically-created companions (which skip
     * {@code finalizeSpawn} either way).
     */
    private static PlayerMobEntity createCompanion(ServerLevel level) {
        //? if >=26 {
        /*return PlayerMobRegistry.PLAYER_MOB.create(level, EntitySpawnReason.MOB_SUMMONED);
        *///?} else {
        return PlayerMobRegistry.PLAYER_MOB.create(level);
        //?}
    }

    /** Spawn a fresh random max-friends buddy beside this mob (the non-echo pair); {@code true} if one spawned. */
    private boolean spawnRandomFriend(ServerLevelAccessor world) {
        ServerLevel level = world.getLevel();
        PlayerMobEntity friend = createCompanion(level);
        if (friend == null) {
            return false;
        }
        placeCompanion(friend);
        // A companion is created via EntityType.create (no egg entity_data to follow), so it takes a
        // normal full roll here — URL skin included.
        friend.rollSpawnDefaults(world.getRandom(), false, true);
        linkAsFriends(friend);
        level.addFreshEntity(friend);
        return true;
    }

    /**
     * Spawn a friend-echo beside this mob from a stored {@code friendSnapshot} — a PlayerMob that
     * loved the life this echo embodies. {@code readAdditionalSaveData} rebuilds it exactly as last
     * seen (skin/identity, gear, traits), and it's titled "Echo of &lt;label&gt;" from the snapshot's
     * {@link GlobalLifeStore#FRIEND_LABEL_KEY}. Like {@link #spawnRandomFriend} it skips
     * {@code finalizeSpawn}, so the friend-echo never rolls its own echo or friend.
     *
     * @return {@code true} once the friend-echo is added to the world.
     */
    private boolean spawnFriendEcho(ServerLevelAccessor world, CompoundTag friendSnapshot) {
        ServerLevel level = world.getLevel();
        PlayerMobEntity friend = createCompanion(level);
        if (friend == null) {
            return false;
        }
        placeCompanion(friend);
        friend.applyCustomData(friendSnapshot.copy());
        String label = NbtCompat.getStringOr(friendSnapshot, GlobalLifeStore.FRIEND_LABEL_KEY, "");
        friend.setCustomName(Component.literal("Echo of " + (label.isBlank() ? "a friend" : label)));
        friend.setCustomNameVisible(true);
        linkAsFriends(friend);
        level.addFreshEntity(friend);
        return true;
    }

    /**
     * Bring back the pets of the life this echo embodies — the animals it had tamed when it died,
     * captured by {@code PlayerReincarnation.capturePetSnapshots} — each re-tamed to this mob, so
     * they read as the echo's own rather than as strays.
     *
     * <p>The "up to three" cap lives at capture: a record never holds more than that, so every
     * logged pet returns. Like the friend-echo path these are added with {@code addFreshEntity} and
     * never see {@code finalizeSpawn}. Non-echo spawns and echoes from a source that logs no pets
     * (a remote life off the relay) simply have nothing to replay.</p>
     *
     * <p>They are pets, not bodyguards: on 1.21.x a tamed animal resolves its owner only through
     * the player list, so an animal owned by a PlayerMob will not follow or defend it. What the
     * re-tame buys is that they are unmistakably <em>his</em> — tamed, non-hostile, and never
     * re-tameable by whoever walks up next.</p>
     *
     * @return how many pets actually returned
     */
    private int spawnPetEchoes(ServerLevelAccessor world, ReincarnationRecord echo) {
        if (echo == null || echo.petSnapshots().isEmpty()) {
            return 0;
        }
        ServerLevel level = world.getLevel();
        int spawned = 0;
        for (CompoundTag snapshot : echo.petSnapshots()) {
            Entity pet = PetSnapshots.spawn(PetSnapshots.retame(snapshot, getUUID()), level);
            if (pet == null) {
                continue; // a type that no longer exists — that pet just doesn't come back
            }
            placeCompanion(pet);
            level.addFreshEntity(pet);
            spawned++;
        }
        return spawned;
    }

    /**
     * Place {@code friend} on this mob's already-valid tile; entity collision separates them next
     * tick. Avoids clipping the companion into a carriage wall by guessing an offset.
     */
    private void placeCompanion(Entity friend) {
        //? if >=26 {
        /*friend.snapTo(getX(), getY(), getZ(), getYRot(), 0.0F);
        *///?} else {
        friend.moveTo(getX(), getY(), getZ(), getYRot(), 0.0F);
        //?}
    }

    /**
     * Make {@code friend} and this mob mutual max friends (feeling {@link FeelingLedger#MAX}), synced
     * for the menu, and link them via {@link #trainPairPartner} so they latch one shared march
     * direction (see {@link #latchTrainExploreDirection}) and travel the train together.
     */
    private void linkAsFriends(PlayerMobEntity friend) {
        this.feelings.set(friend.getUUID(), FeelingLedger.MAX);
        friend.feelings.set(this.getUUID(), FeelingLedger.MAX);
        this.pushDispositionToClient();
        friend.pushDispositionToClient();
        this.trainPairPartner = friend.getUUID();
        friend.trainPairPartner = this.getUUID();
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

    // ---- Disposition accessors + behaviour helpers ------------------------

    /**
     * Pin one or both locked traits <em>before</em> {@code finalizeSpawn}, so the spawn roll keeps
     * them instead of randomising (each setter marks the trait explicit — see
     * {@link DispositionTraits#rollIfUnset}). A {@code null} leaves that trait to the normal random
     * roll. Used by {@code /playermob summon} to honour its optional trait arguments. Values clamp
     * to {@code [0, 10]}.
     */
    public void setExplicitTraits(Integer fightFlight, Integer friendliness, Integer reactionSpeed) {
        if (fightFlight != null) {
            traits.setFightFlight(fightFlight);
        }
        if (friendliness != null) {
            traits.setFriendliness(friendliness);
        }
        if (reactionSpeed != null) {
            traits.setReactionSpeed(reactionSpeed);
        }
    }

    public int fightFlight() {
        return traits.fightFlight();
    }

    public int friendliness() {
        return traits.friendliness();
    }

    public int reactionSpeed() {
        return traits.reactionSpeed();
    }

    /**
     * Scale a fixed delay constant by this mob's reaction speed — {@link ReactionSpeed#ticks}.
     * Goal code keeps its tuned constant as the documented neutral baseline and wraps the use
     * site in this, so the tuning intent stays readable: {@code mob.reactTicks(EMPTY_SCAN_COOLDOWN)}.
     */
    public int reactTicks(int baseTicks) {
        return ReactionSpeed.ticks(traits.reactionSpeed(), baseTicks);
    }

    /**
     * Roll a delay in the inclusive window {@code [min, max]}, biased low for a fast-reacting
     * mob and high for a slow one — {@link ReactionSpeed#roll}. Replaces the
     * {@code min + getRandom().nextInt(span + 1)} idiom at every tuned-window call site; at the
     * neutral reaction speed the two are distributionally identical.
     */
    public int reactRoll(int min, int max) {
        return ReactionSpeed.roll(traits.reactionSpeed(), min, max, getRandom());
    }

    /** The mob's current feeling (0–10, default 5) toward a specific entity. */
    public float feelingToward(LivingEntity entity) {
        return feelings.feelingToward(entity.getUUID());
    }

    /**
     * The mob's computed {@link Reaction} toward a live entity — never null
     * ({@link Reaction#IGNORE} for an uncategorised / creative / spectator
     * target). Feelings are only consulted for players and other PlayerMobs.
     *
     * <p>For a mid fight/flight mob ({@link DispositionResolver#isMidBand}) a
     * proactive FIGHT/FLEE is refined by a "can I win?" power comparison
     * ({@link DispositionResolver#applyWinAssessment}) — it fights weaker foes and
     * flees stronger ones. This gates <em>acquisition</em> only; once a target is
     * locked, vanilla retention keeps the fight going (the power-aware break-off
     * lives in {@link #hurt}). The power estimate is computed only after we know
     * it's a mid-band engage decision, so the common cases stay free.</p>
     */
    public Reaction reactionToward(LivingEntity entity) {
        TargetCategory category = TargetCategory.classify(entity);
        if (category == null) {
            return Reaction.IGNORE;
        }
        // A neutral mob is left alone until it actually turns hostile toward this mob
        // (it's now targeting us). Only then is it engaged proactively — as a hostile
        // — so a brave mob fights the aggroed piglin and a timid one flees it. Being
        // hit without a prior target is still covered by the hurt/retaliation path.
        if (category == TargetCategory.NEUTRAL_MOBS
                && entity instanceof Mob mob && mob.getTarget() == this) {
            category = TargetCategory.HOSTILE_MOBS;
        }
        float feeling = category == TargetCategory.PLAYERS
            ? feelings.feelingToward(entity.getUUID())
            : FeelingLedger.DEFAULT;
        int ff = traits.fightFlight();
        Reaction base = DispositionResolver.resolve(
            ff, traits.friendliness(), feeling, category, distanceTo(entity));
        if ((base == Reaction.FIGHT || base == Reaction.FLEE) && DispositionResolver.isMidBand(ff)) {
            return DispositionResolver.applyWinAssessment(base, ff, selfCombatPower(), combatPowerOf(entity));
        }
        return base;
    }

    // ---- Combat-power assessment ("can I win?") ---------------------------

    /** Cached {@link #combatPowerOf}{@code (this)}, recomputed at most once per tick. */
    private double selfCombatPowerCache;
    /** The {@code tickCount} the cache was filled on; {@code -1} = stale/unset. */
    private int selfCombatPowerTick = -1;

    /**
     * The mob's own combat power for the current tick. {@link #reactionToward}
     * runs per-candidate across several scan sites, so the mob's own power — the
     * same value for every candidate this tick — is computed once and reused.
     * Transient: never saved or synced.
     */
    private double selfCombatPower() {
        if (selfCombatPowerTick != tickCount) {
            selfCombatPowerCache = combatPowerOf(this);
            selfCombatPowerTick = tickCount;
        }
        return selfCombatPowerCache;
    }

    /**
     * A combat-power estimate for any living entity from its health, held weapon
     * ({@link EquipmentEvaluator#score}) and armour value. Used to decide whether a
     * mid fight/flight mob fights or flees a given foe.
     */
    private static double combatPowerOf(LivingEntity entity) {
        double weaponScore = EquipmentEvaluator.score(entity.getMainHandItem());
        return DispositionResolver.combatPower(entity.getHealth(), weaponScore, entity.getArmorValue());
    }

    // ---- Defend-loved-ones support (DefendLovedOneGoal) -------------------

    /**
     * The last categorisable entity to attack this mob, and the tick it happened —
     * a defender's signal for "who hurt my friend". Kept separately from the vanilla
     * {@code lastHurtByMob} because a fleeing mob clears that (so its own retaliation
     * goal stands down), which would otherwise erase the very record a defender reads.
     * Session memory: not saved, not synced.
     */
    private LivingEntity lastAttacker;
    private int lastAttackerTick = -10000;

    /**
     * The player this mob held as its combat target on the previous AI step — the edge detector
     * behind the escape credit in {@code customServerAiStep}. Session memory: not saved, not
     * synced. Losing it across a reload only costs an escape that was mid-flight at the moment
     * of saving; the per-pair {@code escaped} latch it feeds <em>is</em> persisted, so a getaway
     * already paid out never pays twice.
     */
    private Player lastPlayerTarget;

    /** The last categorisable attacker (survives the flee-driven {@code lastHurtByMob} reset), or null. */
    public LivingEntity getLastAttacker() {
        return lastAttacker;
    }

    /** Tick of the last categorisable attack, in this entity's own {@code tickCount} frame. */
    public int getLastAttackerTick() {
        return lastAttackerTick;
    }

    /**
     * Find an individual this mob loves ({@code feeling >= }{@link
     * DispositionResolver#FEELING_LOVE}) that was recently attacked, paired with its
     * attacker, for {@link DefendLovedOneGoal} to target. Scans within {@code range}
     * and returns {@code {defended, foe}} for the <em>nearest</em> qualifying loved
     * one, or {@code null} if none. A loved one qualifies only when its
     * {@code getLastHurtByMob()} is a valid, recent, reachable foe — alive, not this
     * mob or the loved one, categorisable (so creative/spectator attackers are
     * skipped), train-allowed, within range, and last hit no more than
     * {@code recencyTicks} ago (measured in the loved one's own {@code tickCount}
     * frame, the same frame its hurt-timestamp is recorded in). Only players and
     * other PlayerMobs ever reach the love threshold, so {@code defended} is always
     * player-shaped.
     */
    public LivingEntity[] findLovedOneAndFoe(double range, int recencyTicks) {
        AABB box = getBoundingBox().inflate(range);
        double rangeSq = range * range;
        LivingEntity bestDefended = null;
        LivingEntity bestFoe = null;
        double closestSq = rangeSq;
        for (LivingEntity loved : level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (loved == this || !loved.isAlive()) continue;
            if (feelingToward(loved) < DispositionResolver.FEELING_LOVE) continue;
            double lovedSq = distanceToSqr(loved);
            if (lovedSq >= closestSq) continue; // farther than a match we already have
            // A PlayerMob victim clears its vanilla lastHurtByMob when it flees, so read
            // its surviving attacker memory; real players don't flee-clear, so use theirs.
            LivingEntity foe;
            int hurtTick;
            if (loved instanceof PlayerMobEntity pm) {
                foe = pm.getLastAttacker();
                hurtTick = pm.getLastAttackerTick();
            } else {
                foe = loved.getLastHurtByMob();
                hurtTick = loved.getLastHurtByMobTimestamp();
            }
            if (loved.tickCount - hurtTick > recencyTicks) continue;
            if (!isDefensibleFoe(foe, loved, rangeSq)) continue;
            closestSq = lovedSq;
            bestDefended = loved;
            bestFoe = foe;
        }
        return bestDefended == null ? null : new LivingEntity[] { bestDefended, bestFoe };
    }

    /** Whether {@code foe} is a valid target to defend {@code loved} from. */
    private boolean isDefensibleFoe(LivingEntity foe, LivingEntity loved, double rangeSq) {
        return foe != null
            && foe != this
            && foe != loved
            && foe.isAlive()
            && TargetCategory.classify(foe) != null      // skips creative/spectator + uncategorised
            && TrainConfinement.allowsTarget(this, foe)
            && distanceToSqr(foe) <= rangeSq;
    }

    /**
     * Whether this mob will wade into a fight against {@code foe} to defend a loved
     * one — the gate for {@link DefendLovedOneGoal}. Defending overrides the mob's
     * lower-priority stance toward the foe (it will charge a player it would otherwise
     * GREET / WATCH / IGNORE, and drops raiding / strolling to do so — setting the
     * target makes those goals yield) but <b>never overrides {@link Reaction#FLEE}</b>:
     * a mob whose nature is to flee that foe keeps fleeing rather than being forced to
     * fight. Capped by {@link DispositionResolver#defendIsWorthwhile} so even a fearless
     * mob won't throw itself at a hopelessly stronger foe.
     */
    public boolean wouldEngageFoe(LivingEntity foe) {
        return reactionToward(foe) != Reaction.FLEE
            && DispositionResolver.defendIsWorthwhile(selfCombatPower(), combatPowerOf(foe));
    }

    /**
     * Nearest living entity within {@code range} the mob currently reacts to with
     * {@code reaction}. Used by the social goals to find their subject.
     */
    public LivingEntity nearestWhereReaction(Reaction reaction, double range) {
        AABB box = getBoundingBox().inflate(range);
        LivingEntity closest = null;
        double closestSq = range * range;
        List<LivingEntity> candidates = level().getEntitiesOfClass(
            LivingEntity.class, box,
            e -> e != this && e.isAlive() && reactionToward(e) == reaction);
        for (LivingEntity e : candidates) {
            double distSq = distanceToSqr(e);
            if (distSq < closestSq) {
                closestSq = distSq;
                closest = e;
            }
        }
        return closest;
    }

    // ---- Follow-loved-one support (FollowLovedOneGoal) --------------------

    /**
     * The entity this mob should follow because it has come to love it — the nearest,
     * most-loved player or PlayerMob within {@link FollowLovedOnePolicy#SCAN_RANGE} (feeling
     * ≥ {@link DispositionResolver#FEELING_LOVE} and {@link TrainConfinement#allowsTarget
     * train-allowed}), or {@code null} if it has no one to follow. Drives
     * {@code FollowLovedOneGoal} (which throttles how often it asks).
     *
     * <p><b>Mutual-love leadership:</b> a candidate PlayerMob that loves this mob back is
     * skipped when this mob {@linkplain FollowLovedOnePolicy#leads leads} the pair (lower
     * UUID) — so exactly one of a mutual pair chases and the other leads, letting them travel
     * together instead of converging on each other. A loved player is never skipped.</p>
     *
     * <p>Only players and other PlayerMobs ever reach the love threshold, so a non-categorised
     * entity's DEFAULT feeling keeps it out.</p>
     */
    public LivingEntity findFollowTarget() {
        double range = FollowLovedOnePolicy.SCAN_RANGE;
        AABB box = getBoundingBox().inflate(range);
        double rangeSq = range * range;
        LivingEntity best = null;
        float bestFeeling = -1.0F;
        double bestDistSq = Double.MAX_VALUE;
        for (LivingEntity candidate : level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (candidate == this || !candidate.isAlive()) continue;
            float feeling = feelingToward(candidate); // DEFAULT for non-player/-mob → never loved
            if (feeling < DispositionResolver.FEELING_LOVE) continue;
            if (!TrainConfinement.allowsTarget(this, candidate)) continue;
            double distSq = distanceToSqr(candidate);
            if (distSq > rangeSq) continue;
            // Mutual-love leadership: don't follow a mob I lead — it follows me instead.
            if (candidate instanceof PlayerMobEntity other
                    && other.feelingToward(this) >= DispositionResolver.FEELING_LOVE
                    && FollowLovedOnePolicy.leads(getUUID(), other.getUUID())) {
                continue;
            }
            // Rank: strongest feeling first, nearest as the tiebreak.
            if (feeling > bestFeeling || (feeling == bestFeeling && distSq < bestDistSq)) {
                best = candidate;
                bestFeeling = feeling;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    // ---- Client-synced disposition (for the menu UI) ----------------------

    /** Push the server-side traits + feelings into the synced fields the client reads. */
    private void pushDispositionToClient() {
        this.entityData.set(DATA_FIGHT_FLIGHT, traits.fightFlight());
        this.entityData.set(DATA_FRIENDLINESS, traits.friendliness());
        this.entityData.set(DATA_REACTION_SPEED, traits.reactionSpeed());
        this.entityData.set(DATA_FEELINGS, feelings.encode());
    }

    /** Client-synced fight/flight (0–10), for the menu UI. */
    public int getSyncedFightFlight() {
        return this.entityData.get(DATA_FIGHT_FLIGHT);
    }

    /** Client-synced friendliness (0–10), for the menu UI. */
    public int getSyncedFriendliness() {
        return this.entityData.get(DATA_FRIENDLINESS);
    }

    /** Client-synced reaction speed (0–10), for the menu UI. */
    public int getSyncedReactionSpeed() {
        return this.entityData.get(DATA_REACTION_SPEED);
    }

    /** Client-synced feelings (UUID → 0–10), decoded from the synced string. */
    public Map<UUID, Float> getSyncedFeelings() {
        return FeelingLedger.decode(this.entityData.get(DATA_FEELINGS));
    }

    /**
     * Apply a Creative trait-editor button (see {@link TraitEditButtons}) and
     * re-sync the result to watching clients so the open menu updates next frame.
     * Called server-side from {@code PlayerMobMenu.clickMenuButton}; values are
     * clamped to {@code [0, 10]} by {@link DispositionTraits}. Editing marks the
     * trait explicit, so it persists through the existing {@code traits.save} NBT
     * path with no migration.
     *
     * @return {@code true} if {@code buttonId} mapped to a trait edit.
     */
    public boolean applyTraitEditButton(int buttonId) {
        boolean handled = TraitEditButtons.apply(buttonId, traits);
        if (handled) {
            pushDispositionToClient();
        }
        return handled;
    }

    /**
     * Apply a Creative relationship-feeling editor button (see
     * {@link FeelingEditButtons}) and re-sync. Server-side; the feeling is clamped
     * to {@code [0, 10]} by {@link FeelingLedger}, and persists through the existing
     * {@code feelings.save} NBT path. The relationship stays in the roster even at
     * neutral (Phase B keeps every met individual).
     *
     * @return {@code true} if {@code buttonId} mapped to a feeling edit.
     */
    public boolean applyFeelingEditButton(int buttonId) {
        boolean handled = FeelingEditButtons.apply(buttonId, feelings);
        if (handled) {
            pushDispositionToClient();
        }
        return handled;
    }

    /** True if the main hand holds a recognised weapon (drives the provoked fight/flee choice). */
    public boolean isArmed() {
        return isWeapon(getMainHandItem());
    }

    private static boolean isWeapon(ItemStack stack) {
        return ItemKindCompat.isSword(stack)
            || stack.getItem() instanceof AxeItem
            || stack.getItem() instanceof TridentItem
            || stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem
            //? if >=1.21.1 {
            || stack.getItem() instanceof MaceItem;
            //?} else {
            /*;*///?}
    }

    /**
     * Drop {@code stack} into the world at this mob's feet. MC 26.x added a leading
     * {@code ServerLevel} argument to {@code Entity.spawnAtLocation}; every call here runs
     * server-side (drops, swaps, death loot), so {@code level()} is a {@code ServerLevel}.
     * No-op for an empty stack, matching vanilla {@code spawnAtLocation}.
     */
    public void dropAtLocation(ItemStack stack) {
        //? if >=26 {
        /*if (level() instanceof ServerLevel server) {
            spawnAtLocation(server, stack);
        }
        *///?} else {
        spawnAtLocation(stack);
        //?}
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

    /**
     * Commanded "wield this": if the mob already holds {@code item} or carries one in its pack,
     * move it to the main hand (stashing whatever it held) and return {@code true}; else {@code false}.
     * Used by {@code /playermob order ... attack ... with <weapon>} (the non-spawn form).
     */
    public boolean equipWeapon(Item item) {
        if (getMainHandItem().is(item)) {
            return true;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                ItemStack found = inventory.removeItemNoUpdate(i);
                stashMainHandToPack();
                setItemSlot(EquipmentSlot.MAINHAND, found);
                return true;
            }
        }
        return false;
    }

    /**
     * Commanded "conjure and wield": equip a freshly minted {@code stack} in the main hand,
     * stashing whatever it held. Used by the {@code with <weapon> spawn} form.
     */
    public void spawnWeapon(ItemStack stack) {
        stashMainHandToPack();
        setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    /** Move the current main-hand item into the pack (dropping any overflow) so a new weapon can be held. */
    private void stashMainHandToPack() {
        ItemStack current = getMainHandItem();
        if (current.isEmpty()) {
            return;
        }
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemStack leftover = inventory.addItem(current);
        if (!leftover.isEmpty()) {
            dropAtLocation(leftover);
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
     * True if this mob is holding a shield it could raise (either hand). Gates the
     * arrow-blocking reflex ({@code BlockArrowsGoal}) so a shieldless mob is a no-op.
     */
    public boolean hasShieldReady() {
        return getOffhandItem().is(Items.SHIELD) || getMainHandItem().is(Items.SHIELD);
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
        faceBodyToward(target.getX(), target.getZ());
    }

    /**
     * Snap the body (and head) to face a horizontal point. Used by
     * {@code BlockArrowsGoal} to square up to an incoming arrow's position so the
     * shield block registers (same facing requirement as {@link #faceBodyToward(LivingEntity)}).
     */
    public void faceBodyToward(double targetX, double targetZ) {
        double dx = targetX - getX();
        double dz = targetZ - getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
    }

    /** Food carried beyond this item count is "excess" the mob will give away. */
    private static final int FOOD_KEEP_COUNT = 20;

    /**
     * Choose a gift for {@code recipient} from <em>what the mob is actually
     * carrying</em>, its value scaled by how loved they are ({@link GiftPolicy}).
     * Tries the richest rung the feeling+friendliness allows and cascades down
     * ({@link GiftPolicy#cascadeFrom}):
     * <ul>
     *   <li><b>UPGRADE</b> — a backpack gear piece that upgrades the recipient
     *       (player-shaped recipients only, so an animal is never handed armour);</li>
     *   <li><b>SPARE</b> — gear the mob already holds an equal/better duplicate of;</li>
     *   <li><b>SURPLUS</b> — excess food (kept above {@value #FOOD_KEEP_COUNT}) or a spare block stack.</li>
     * </ul>
     * Returns the chosen stack <em>already removed</em> from the backpack, or
     * {@link ItemStack#EMPTY} if the pack has nothing to give at any rung (the
     * caller then fetches a nearby item, or mints a token as a last resort). Gear is
     * only ever sourced from the backpack, so the mob never disarms itself.
     */
    public ItemStack selectGiftFromInventory(LivingEntity recipient) {
        GiftPolicy.GiftTier top = GiftPolicy.tierFor(feelingToward(recipient), friendliness());
        boolean playerShaped = recipient instanceof Player || recipient instanceof PlayerMobEntity;
        for (GiftPolicy.GiftTier rung : GiftPolicy.cascadeFrom(top)) {
            ItemStack gift = switch (rung) {
                case UPGRADE -> playerShaped ? takeBestGearUpgradeFor(recipient) : ItemStack.EMPTY;
                case SPARE -> takeSpareGear();
                case SURPLUS -> takeSurplus();
            };
            if (!gift.isEmpty()) {
                return gift;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Find and remove the single backpack equipment piece that best upgrades
     * {@code recipient}'s gear — the highest-{@link EquipmentEvaluator#score} stack
     * for which {@link EquipmentEvaluator#shouldReplace} beats what the recipient
     * already wears/holds in that slot — or {@link ItemStack#EMPTY} if the backpack
     * holds no upgrade. {@code getEquipmentSlotForItem} is item-driven (same slot
     * for any humanoid), so it's read off the mob. Read-only scan, then one removal.
     */
    private ItemStack takeBestGearUpgradeFor(LivingEntity recipient) {
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack candidate = inventory.getItem(i);
            if (candidate.isEmpty()) continue;
            EquipmentSlot slot = getEquipmentSlotForItem(candidate);
            if (!EquipmentEvaluator.shouldReplace(candidate, recipient.getItemBySlot(slot))) continue;
            double s = EquipmentEvaluator.score(candidate);
            if (s > bestScore) {
                bestScore = s;
                bestSlot = i;
            }
        }
        return bestSlot < 0 ? ItemStack.EMPTY : inventory.removeItemNoUpdate(bestSlot);
    }

    /**
     * Find and remove the best backpack equipment piece the mob already holds an
     * equal-or-better of in its slot — redundant kit it can give away for free. A
     * piece that would <em>upgrade</em> the mob (better than what's equipped, or
     * filling an empty slot it wants) is kept, so this never disarms the mob.
     */
    private ItemStack takeSpareGear() {
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack candidate = inventory.getItem(i);
            if (candidate.isEmpty()) continue;
            if (!EquipmentEvaluator.shouldReplace(candidate, ItemStack.EMPTY)) continue; // not recognised gear
            EquipmentSlot slot = getEquipmentSlotForItem(candidate);
            ItemStack equipped = getItemBySlot(slot);
            if (equipped.isEmpty()) continue;                                   // empty slot → mob wants it
            if (EquipmentEvaluator.shouldReplace(candidate, equipped)) continue; // upgrade for mob → keep
            double s = EquipmentEvaluator.score(candidate);
            if (s > bestScore) {
                bestScore = s;
                bestSlot = i;
            }
        }
        return bestSlot < 0 ? ItemStack.EMPTY : inventory.removeItemNoUpdate(bestSlot);
    }

    /**
     * A surplus gift: excess food (the lowest-nutrition stack, only when carrying
     * more than {@value #FOOD_KEEP_COUNT} food items — giving just the amount over
     * the buffer), else a spare building-block stack. {@link ItemStack#EMPTY} if it
     * has neither.
     */
    private ItemStack takeSurplus() {
        int foodCount = carriedFoodCount();
        if (foodCount > FOOD_KEEP_COUNT) {
            int slot = lowestNutritionFoodSlot();
            if (slot >= 0) {
                int give = Math.min(inventory.getItem(slot).getCount(), foodCount - FOOD_KEEP_COUNT);
                return inventory.removeItem(slot, give);
            }
        }
        int blockSlot = ItemPickupPolicy.smallestBuildingBlockSlot(inventory);
        return blockSlot < 0 ? ItemStack.EMPTY : inventory.removeItemNoUpdate(blockSlot);
    }

    /** Total food items carried (count, not nutrition) — drives the surplus-food gift. */
    private int carriedFoodCount() {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemDataCompat.isFood(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Backpack slot of the lowest-nutrition food (give the cheap food, keep the good), or -1. */
    private int lowestNutritionFoodSlot() {
        int slot = -1;
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = ItemDataCompat.foodProperties(stack);
            if (food != null && ItemDataCompat.nutrition(food) < lowest) {
                lowest = ItemDataCompat.nutrition(food);
                slot = i;
            }
        }
        return slot;
    }

    /**
     * Nearest alive, pickup-ready, train-allowed dropped item within {@code radius}
     * — what the greet goal walks to and gives when its pack has nothing to offer.
     * Reuses the {@code CollectFloorItemsGoal} scan shape, minus the self-interest
     * filter: any item will do as a gift.
     */
    public ItemEntity findGiftableNearbyItem(double radius) {
        AABB box = getBoundingBox().inflate(radius);
        ItemEntity closest = null;
        double closestSq = radius * radius;
        for (ItemEntity item : level().getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && !e.isRemoved() && !e.hasPickUpDelay()
                    && TrainConfinement.allowsTarget(this, e))) {
            double d = distanceToSqr(item);
            if (d < closestSq) {
                closestSq = d;
                closest = item;
            }
        }
        return closest;
    }

    /** Toss {@code gift} arcing toward {@code target} from eye height, like a player's Q-drop. */
    public void tossGift(LivingEntity target, ItemStack gift) {
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
        // Attribute the toss so a PlayerMob recipient credits it as a gift (and the
        // giver never self-credits picking its own toss back up). Pickup delay is
        // independent of the thrower, so this doesn't change re-collection timing.
        //? if >=1.21.1 {
        thrown.setThrower(this);
        //?} else {
        /*thrown.setThrower(this.getUUID());*///?}
        level().addFreshEntity(thrown);
        // Announce a gift to a player so an optional mod (e.g. Dungeon Train's befriended
        // advancement) can credit it by subscribing to PlayerMobSocialHooks — no mixin into
        // this method required. No-op when nothing is installed.
        if (target instanceof ServerPlayer recipient) {
            PlayerMobSocialHooks.onMobGift(recipient, getUUID());
        }
    }

    /** A flower — the last-resort gift when the pack is empty and nothing's nearby to fetch. */
    public ItemStack trinketGift() {
        return new ItemStack(getRandom().nextBoolean() ? Items.POPPY : Items.DANDELION);
    }

    /** Whether world-griefing (and thus chest raiding) is permitted here. */
    public boolean canRaid() {
        return GameRuleCompat.mobGriefing(level());
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
     * The client renderer swaps to the slim body model when this is set.
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

    /**
     * Whether this mob arrived on its own rather than being placed by a player — see
     * {@link #naturalOrigin}.
     */
    public boolean isNaturallySpawned() {
        return this.naturalOrigin;
    }

    /**
     * Whether a scavenging behaviour configured with {@code mode} is allowed for this mob:
     * always under {@link ScavengeMode#ENABLED}, never under {@link ScavengeMode#DISABLED}, and only
     * for a naturally-spawned mob under {@link ScavengeMode#ONLY_NATURALLY_SPAWNING}. Called from
     * {@code canUse()} in the chest / armor-stand / floor-item goals.
     */
    public boolean allowsScavenging(ScavengeMode mode) {
        return mode.allows(this.naturalOrigin);
    }

    /**
     * Arm the door-close hold: for {@link #DOOR_CLOSE_HOLD_TICKS} this mob opens no doors, so a
     * door the stuck-recovery just closed (to clear an open swing blocking its path) isn't reopened
     * before it can cross. Called by the stuck-recovery on and off a train.
     */
    public void holdDoorsClosed() {
        this.doorCloseHoldTicks = DOOR_CLOSE_HOLD_TICKS;
    }

    /**
     * Whether door-opening is currently suppressed for this mob (see {@link #holdDoorsClosed()}).
     * Consulted by {@link PlayerMobDoorGoal} and the Dungeon-Train door reflex so neither reopens a
     * door the stuck-recovery just closed.
     */
    public boolean isHoldingDoorsClosed() {
        return this.doorCloseHoldTicks > 0;
    }

    /**
     * Begin a deliberate door operation: for {@link #DOOR_OP_TICKS} the mob faces the door — via the
     * eye-relative offset, re-applied each tick so it tracks a moving carriage — and, through
     * {@link DoorOperationGoal} claiming MOVE+LOOK at priority 1, stops fighting/walking; partway in
     * (at {@link #DOOR_OP_REACH_TICKS}) it swings and runs {@code action} (the actual open/close).
     * A no-op while already operating a door or recovering onto a train, so it can't re-fire every
     * tick. See {@link #tickDoorOperation()}.
     *
     * @param dx the door centre's X offset from the mob's eyes (world axes, == sub-level axes on a
     *           rigid carriage); re-applied each tick as the look target
     * @param dy the door centre's Y offset from the mob's eyes
     * @param dz the door centre's Z offset from the mob's eyes
     * @param action the deferred open/close (e.g. {@link DoorObstruction#setOpen})
     */
    public void beginDoorOperation(double dx, double dy, double dz, Runnable action) {
        if (!armDoorOperation()) {
            return;
        }
        this.doorOpFacing = true;
        this.doorOpDx = dx;
        this.doorOpDy = dy;
        this.doorOpDz = dz;
        this.doorOpAction = action;
    }

    /**
     * Arm only the interrupt window — no look override, no deferred action — for a door operation
     * that already drives its own look and action (the Dungeon-Train iron-door control, which faces
     * and punches the button via its own gaze). This just makes that operation pause combat too.
     */
    public void interruptForDoorOperation() {
        if (!armDoorOperation()) {
            return;
        }
        this.doorOpFacing = false;
        this.doorOpAction = null;
    }

    /** Start a fresh door-operation window unless one is already running (or the mob is recovering). */
    private boolean armDoorOperation() {
        if (isOperatingDoor() || this.recovering) {
            return false;
        }
        this.doorOpTicks = DOOR_OP_TICKS;
        return true;
    }

    /** Whether the mob is mid deliberate door-operation — the condition {@link DoorOperationGoal} runs on. */
    public boolean isOperatingDoor() {
        return this.doorOpTicks > 0;
    }

    /**
     * Advance the door-operation window one tick: keep facing the door (when this op drives the
     * look), and at the reach tick swing the arm and run the deferred open/close once. Called from
     * {@link #customServerAiStep()} after the goal selector, so the look wins over whatever goal
     * otherwise owns it (mirrors the iron-control gaze).
     */
    private void tickDoorOperation() {
        if (this.doorOpTicks <= 0) {
            return;
        }
        if (this.doorOpFacing) {
            getLookControl().setLookAt(getX() + this.doorOpDx, getEyeY() + this.doorOpDy, getZ() + this.doorOpDz);
        }
        if (this.doorOpTicks == DOOR_OP_REACH_TICKS) {
            if (this.doorOpFacing) {
                swing(InteractionHand.MAIN_HAND);
            }
            if (this.doorOpAction != null) {
                this.doorOpAction.run();
                this.doorOpAction = null;
            }
        }
        this.doorOpTicks--;
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
            //? if >=26 {
            /*// 26.x folded sided-success into the new sealed InteractionResult.SUCCESS,
            // which swings the arm client-side and is a no-op server-side automatically.
            return InteractionResult.SUCCESS;
            *///?} else {
            return InteractionResult.sidedSuccess(this.level().isClientSide);
            //?}
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
    //? if >=26 {
    /*protected boolean canReplaceCurrentItem(ItemStack candidate, ItemStack existing,
                                            EquipmentSlot slot) {
    *///?} else {
    protected boolean canReplaceCurrentItem(ItemStack candidate, ItemStack existing) {
    //?}
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
        if (ItemDataCompat.isFood(candidate)) {
            return EquipmentEvaluator.tryCollectFood(source, slotIdx, this.inventory);
        }

        // Wood/stone the mob stocks up on — a stack of each, capped independently.
        ItemPickupPolicy.BlockResource resource = ItemPickupPolicy.blockResource(candidate);
        if (resource != null) {
            return takeBlockResourceCapped(source, slotIdx, resource);
        }

        EquipmentSlot slot = getEquipmentSlotForItem(candidate);
        ItemStack current = getItemBySlot(slot);
        if (EquipmentEvaluator.shouldReplace(candidate, current)) {
            setItemSlot(slot, candidate.copy());
            source.setItem(slotIdx, ItemStack.EMPTY);
            source.setChanged();
            if (!current.isEmpty()) {
                ItemStack leftover = EquipmentEvaluator.addToContainer(source, current.copy());
                if (!leftover.isEmpty()) dropAtLocation(leftover);
            }
            return true;
        }

        // Not an equip upgrade — hoard it into the backpack if it's an admin-configured extra pickup.
        if (isExtraWanted(candidate)) return takeExtraWantedFromContainer(source, slotIdx);
        return false;
    }

    /**
     * Move as much of an {@code extraPickupItems} stack as fits from a chest slot into the backpack
     * (uncapped, matching the floor/valuables hoard semantics). Overflow that doesn't fit stays in the
     * chest — nothing is dropped on the ground.
     *
     * @return true if any item moved
     */
    private boolean takeExtraWantedFromContainer(Container source, int slotIdx) {
        ItemStack found = source.getItem(slotIdx);
        if (found.isEmpty()) return false;
        ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, found.copy());
        int moved = found.getCount() - leftover.getCount();
        if (moved <= 0) return false;
        found.shrink(moved);
        source.setChanged();
        return true;
    }

    /**
     * Move up to a per-resource cap (one stack) of {@code resource} from a chest
     * slot into the backpack. Under the cap, take only enough to reach it. At the
     * cap, trade up: if {@code found} out-ranks the lowest-value stack of this
     * resource the mob carries (for STONE, tool-craftable beats decorative;
     * otherwise a bigger pile wins) push that stack back into the chest and take
     * the better one. Overflow that doesn't fit stays in the chest — nothing is
     * ever dropped on the ground.
     *
     * <p>Mirrors {@link #pickUpBlockCapped} (the floor-pickup equivalent) but
     * moves chest → backpack and returns displaced stacks to the chest rather
     * than the floor. Looted wood/stone are also {@code BlockItem}s, so they also
     * count toward the floor-pickup pooled
     * {@link ItemPickupPolicy#countBuildingBlocks} cap — the caps share slots but
     * enforce independently.</p>
     *
     * @return true if any item moved
     */
    private boolean takeBlockResourceCapped(Container source, int slotIdx,
                                            ItemPickupPolicy.BlockResource resource) {
        ItemStack found = source.getItem(slotIdx);
        if (found.isEmpty()) return false;

        int cap = resource == ItemPickupPolicy.BlockResource.WOOD
            ? ItemPickupPolicy.WOOD_CAP : ItemPickupPolicy.STONE_CAP;
        int carried = ItemPickupPolicy.countResource(this.inventory, resource);

        if (carried >= cap) {
            int lowSlot = ItemPickupPolicy.lowestValueResourceSlot(this.inventory, resource);
            if (lowSlot < 0) return false;
            ItemStack lowest = this.inventory.getItem(lowSlot);
            if (ItemPickupPolicy.tradeUpValue(resource, found)
                    <= ItemPickupPolicy.tradeUpValue(resource, lowest)) {
                return false;
            }
            // Trade up: push the displaced stack back into the chest. If the chest
            // is full, undo and bail rather than drop it on the ground.
            this.inventory.setItem(lowSlot, ItemStack.EMPTY);
            ItemStack back = EquipmentEvaluator.addToContainer(source, lowest);
            if (!back.isEmpty()) {
                this.inventory.setItem(lowSlot, back);
                return false;
            }
            carried = ItemPickupPolicy.countResource(this.inventory, resource);
        }

        int room = cap - carried;
        if (room <= 0) return false;
        ItemStack toAdd = found.copy();
        toAdd.setCount(Math.min(found.getCount(), room));
        int before = toAdd.getCount();
        ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, toAdd);
        int moved = before - leftover.getCount();
        if (moved <= 0) return false;
        found.shrink(moved);
        source.setChanged();
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
        if (!EquipmentEvaluator.shouldReplace(candidate, current)) return false;

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
        if (ItemDataCompat.isFood(candidate)) {
            return EquipmentEvaluator.canCollectFood(source, slotIdx, this.inventory);
        }
        // Wood/stone pre-check — kept exactly in sync with takeBlockResourceCapped's decision.
        ItemPickupPolicy.BlockResource resource = ItemPickupPolicy.blockResource(candidate);
        if (resource != null) {
            if (!ItemPickupPolicy.wantsResource(this.inventory, candidate, resource)) return false;
            int cap = resource == ItemPickupPolicy.BlockResource.WOOD
                ? ItemPickupPolicy.WOOD_CAP : ItemPickupPolicy.STONE_CAP;
            // At cap, wantsResource already confirmed a displaceable slot — trade-up frees it.
            if (ItemPickupPolicy.countResource(this.inventory, resource) >= cap) return true;
            return EquipmentEvaluator.hasRoomFor(this.inventory, candidate);
        }
        EquipmentSlot slot = getEquipmentSlotForItem(candidate);
        ItemStack current = getItemBySlot(slot);
        if (EquipmentEvaluator.shouldReplace(candidate, current)) return true;
        // Mirror tryTakeFromContainer's extra-pickup fallback: hoard configured items with backpack room.
        return isExtraWanted(candidate) && EquipmentEvaluator.hasRoomFor(this.inventory, candidate);
    }

    /** Pre-check variant of {@link #tryReplaceFromArmorStand}. */
    public boolean wouldReplaceFromArmorStand(ArmorStand stand, EquipmentSlot fromSlot) {
        ItemStack candidate = stand.getItemBySlot(fromSlot);
        if (candidate.isEmpty()) return false;
        EquipmentSlot mobSlot = getEquipmentSlotForItem(candidate);
        ItemStack current = getItemBySlot(mobSlot);
        return EquipmentEvaluator.shouldReplace(candidate, current);
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
    //? if >=26 {
    /*public boolean wantsToPickUp(ServerLevel level, ItemStack stack) {
    *///?} else {
    public boolean wantsToPickUp(ItemStack stack) {
    //?}
        ItemPickupPolicy.WeaponCategory cat = ItemPickupPolicy.weaponCategory(stack);
        if (cat != null) {
            Located best = bestOfCategory(cat);
            return best == null || ItemPickupPolicy.compareQuality(stack, best.stack()) > 0;
        }
        return wouldEquipArmor(stack)
            || wantsAsAmmo(stack)
            || ItemPickupPolicy.isValuable(stack)
            || ItemPickupPolicy.isConsumable(stack)
            || ItemPickupPolicy.isShulkerBox(stack)
            || ItemPickupPolicy.isIgniterTool(stack)
            || isExtraWanted(stack)
            || (ItemPickupPolicy.isBuildingBlock(stack)
                && ItemPickupPolicy.wantsBuildingBlock(this.inventory, stack));
    }

    /**
     * True if {@code stack} is in the admin-configured {@code extraPickupItems} list — extra items
     * the mob always grabs and hoards in its backpack (off the floor and out of chests), beyond the
     * built-in gear/ammo/valuable/consumable categories. Empty by default. See {@link WantedItemList}.
     */
    private boolean isExtraWanted(ItemStack stack) {
        return PlayerMobConfig.extraPickups().matches(stack);
    }

    /**
     * Vanilla passive-pickup entry point — fires from {@code Mob.aiStep} when
     * {@code canPickUpLoot} + {@code mobGriefing} hold. Routes through the same
     * logic the active goal uses.
     */
    @Override
    //? if >=26 {
    /*protected void pickUpItem(ServerLevel level, ItemEntity itemEntity) {
    *///?} else {
    protected void pickUpItem(ItemEntity itemEntity) {
    //?}
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

        // Capture gift attribution + value BEFORE the take, which may shrink/discard
        // the entity. Credit only once the item is fully absorbed (below), so a stack
        // taken across several ticks isn't counted as several gifts.
        LivingEntity gifter = giftSource(itemEntity);
        ItemStack giftCopy = gifter != null ? stack.copy() : null;

        boolean took = takeFloorItem(itemEntity, stack);

        if (took && gifter != null && (itemEntity.isRemoved() || itemEntity.getItem().isEmpty())) {
            creditGift(gifter, giftCopy);
        }
        return took;
    }

    /** The branch logic of {@link #tryPickUpFloorItem}, factored out for the gift hook. */
    private boolean takeFloorItem(ItemEntity itemEntity, ItemStack stack) {
        ItemPickupPolicy.WeaponCategory cat = ItemPickupPolicy.weaponCategory(stack);
        if (cat != null) {
            return finishPickup(itemEntity, stack, reconcileWeaponPickup(cat, stack));
        }
        if (wouldEquipArmor(stack)) {
            return finishPickup(itemEntity, stack, equipArmorUpgrade(stack));
        }
        if (wantsAsAmmo(stack)
                || ItemPickupPolicy.isValuable(stack)
                || ItemPickupPolicy.isConsumable(stack)
                || ItemPickupPolicy.isShulkerBox(stack)
                || ItemPickupPolicy.isIgniterTool(stack)
                || isExtraWanted(stack)) {
            // InventoryCarrier.pickUpItem handles its own want-check, take, and discard.
            // Shulker boxes route here (not the building-block path) so their stored
            // inventory is carried and returned intact — they are never placed.
            //? if >=26 {
            /*// 26.x added a leading ServerLevel argument; pickup only runs server-side.
            if (level() instanceof ServerLevel server) {
                InventoryCarrier.pickUpItem(server, this, this, itemEntity);
            }
            *///?} else {
            InventoryCarrier.pickUpItem(this, this, itemEntity);
            //?}
            return true;
        }
        if (ItemPickupPolicy.isBuildingBlock(stack)) {
            return finishPickup(itemEntity, stack, pickUpBlockCapped(stack));
        }
        return false;
    }

    /**
     * The player / PlayerMob that threw {@code itemEntity}, if it should count as a
     * gift — i.e. a non-self thrower the mob holds disposition toward. Creative /
     * spectator throwers classify as {@code null} and are ignored, same as everywhere.
     */
    private LivingEntity giftSource(ItemEntity itemEntity) {
        if (!(itemEntity.getOwner() instanceof LivingEntity owner)) return null;
        if (owner == this) return null;
        if (TargetCategory.classify(owner) != TargetCategory.PLAYERS) return null;
        return owner;
    }

    /**
     * Raise feeling toward {@code gifter} by the gift's value over the mob's current
     * gear in that slot ({@link EquipmentEvaluator#score} — non-gear scores 0, so it
     * yields the floor). Pushes the change so the open menu updates live.
     */
    private void creditGift(LivingEntity gifter, ItemStack gift) {
        double giftScore = EquipmentEvaluator.score(gift);
        double currentScore = EquipmentEvaluator.score(getItemBySlot(getEquipmentSlotForItem(gift)));
        float delta = FeelingRecord.giftDelta(giftScore, currentScore);
        // Friendlier mobs are moved more by the same gift (DispositionResolver.kindnessScale).
        feelings.adjust(gifter.getUUID(), delta * DispositionResolver.kindnessScale(friendliness()));
        // Credit the real player's lifetime kindness by the gift's worth — unscaled, since the
        // store tracks what the player did, not this mob's trait-coloured perception of it.
        if (gifter instanceof ServerPlayer sp) {
            PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.GIFT, delta);
            // Announce a player→mob gift so an optional mod (e.g. Dungeon Train's befriended
            // advancement) can credit it by subscribing to PlayerMobSocialHooks — no mixin
            // into the pickup path required. No-op when nothing is installed.
            PlayerMobSocialHooks.onPlayerGift(sp, getUUID());
        }
        pushDispositionToClient();
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
        return EquipmentEvaluator.shouldReplace(stack, getItemBySlot(slot));
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
        if (!EquipmentEvaluator.shouldReplace(found, current)) return 0;

        ItemStack toEquip = found.copy();
        toEquip.setCount(1); // equipment slots hold a single piece
        setItemSlot(slot, toEquip);
        if (!current.isEmpty()) {
            ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, current);
            if (!leftover.isEmpty()) dropAtLocation(leftover);
        }
        return 1;
    }

    // ---- Weapon toolkit (best-of-each + combat switching) ----------------

    /** A located toolkit item: {@code slot == -1} means the main hand, else a backpack slot index. */
    private record Located(ItemStack stack, int slot) {}

    /** {@code d²} — squares an engage distance for comparison against {@code distanceToSqr}. */
    private static double sq(double d) {
        return d * d;
    }

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

    /** True if the mob carries a bow or crossbow anywhere (main hand or backpack) — used by {@link SeekAmmoGoal}. */
    public boolean ownsRangedWeapon() {
        return bestOfCategory(ItemPickupPolicy.WeaponCategory.RANGED) != null;
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
            dropAtLocation(best.stack()); // drop the worse duplicate
        }
        ItemStack one = picked.copy();
        one.setCount(1);
        ItemStack leftover = EquipmentEvaluator.addToContainer(this.inventory, one);
        if (!leftover.isEmpty()) dropAtLocation(leftover);

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
     * combat. The ranged weapon is only a candidate when the mob actually has
     * ammo that weapon accepts ({@link #hasRangedAmmo(ItemStack)}) — an
     * out-of-ammo mob draws melee and closes in instead of dry-firing. Thresholds come from
     * {@link PlayerMobConfig} (defaults 8 / 4 blocks). Called every combat tick
     * by {@link WeaponAwareAttackGoal} — a cheap no-op when the right weapon is
     * already in hand.
     */
    public void equipBestWeaponForTarget(LivingEntity target) {
        if (target == null) return;
        double distSq = distanceToSqr(target);
        double rangedSq = sq(PlayerMobConfig.rangedEngageDistance());
        double meleeSq = sq(PlayerMobConfig.meleeEngageDistance());
        // Only consider a ranged weapon if there's ammo that weapon accepts; without it the mob fights melee.
        Located ranged = bestOfCategory(ItemPickupPolicy.WeaponCategory.RANGED);
        if (ranged != null && !hasRangedAmmo(ranged.stack())) {
            ranged = null;
        }
        Located melee = bestMelee();

        Located desired;
        if (ranged != null && distSq > rangedSq) {
            desired = ranged;
        } else if (melee != null && distSq < meleeSq) {
            desired = melee;
        } else {
            // Hysteresis band (or only one type owned): keep the current combat
            // weapon if we have one, else fall back to whatever we do own. A held
            // ranged weapon only "counts" while we have ammo ({@code ranged != null});
            // out of arrows, drop it for melee so the mob draws its sword/axe rather
            // than bashing with an empty bow.
            ItemPickupPolicy.WeaponCategory current = ItemPickupPolicy.weaponCategory(getMainHandItem());
            boolean keepCurrent = current == ItemPickupPolicy.WeaponCategory.SWORD
                || current == ItemPickupPolicy.WeaponCategory.AXE
                || (current == ItemPickupPolicy.WeaponCategory.RANGED && ranged != null);
            if (keepCurrent) {
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
            if (!leftover.isEmpty()) dropAtLocation(leftover);
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
            dropAtLocation(smallest);
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
     * item carries food data (see {@link ItemDataCompat#isFood}) — same
     * definition vanilla uses for items players can right-click to eat.
     */
    public int findBestFoodSlot() {
        int bestSlot = -1;
        int bestNutrition = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = ItemDataCompat.foodProperties(stack);
            if (food == null) continue;
            if (ItemDataCompat.nutrition(food) > bestNutrition) {
                bestNutrition = ItemDataCompat.nutrition(food);
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
            FoodProperties food = ItemDataCompat.foodProperties(stack);
            if (food != null) total += ItemDataCompat.nutrition(food) * stack.getCount();
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
        //? if >=26 {
        /*// 26.x: ItemParticleOption takes an ItemStackTemplate, not a bare ItemStack.
        ItemParticleOption particle = new ItemParticleOption(ParticleTypes.ITEM,
            net.minecraft.world.item.ItemStackTemplate.fromStack(food.copy()));
        *///?} else {
        ItemParticleOption particle = new ItemParticleOption(ParticleTypes.ITEM, food.copy());
        //?}
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
    //
    // MC 26.x moved the entity save format from raw CompoundTag onto the abstract
    // ValueInput / ValueOutput stream interfaces, so the override SIGNATURES differ by
    // version. To keep the (large, behaviour-sensitive) field logic in one place, the
    // custom data is read/written through version-agnostic CompoundTag helpers
    // (writeCustomTag / readCustomTag, NbtCompat-bridged); the thin per-version overrides
    // only differ in how they obtain that CompoundTag and where they push the inventory.
    //
    // For 26.x the flat custom keys are nested under a single TAG_CUSTOM child (the on-disk
    // layout only matters within a single MC version, and 26.x is a fresh target with no
    // legacy 26.x saves to remain byte-compatible with).

    /** NBT key the 26.x save nests all PlayerMob custom keys under (see class note above). */
    private static final String TAG_CUSTOM = "PlayerMobData";

    //? if >=26 {
    /*@Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput out) {
        super.addAdditionalSaveData(out);
        CompoundTag custom = new CompoundTag();
        writeCustomTag(custom);
        out.store(TAG_CUSTOM, CompoundTag.CODEC, custom);
        writeInventoryToTag(out);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput in) {
        super.readAdditionalSaveData(in);
        readCustomTag(in.read(TAG_CUSTOM, CompoundTag.CODEC).orElseGet(CompoundTag::new));
        readInventoryFromTag(in);
    }
    *///?} else {
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        writeCustomTag(tag);
        // Inventory persistence — InventoryCarrier helper handles slot encoding.
        // registryAccess() is a HolderLookup.Provider on Entity in 1.21.1+; the
        // 1.20.1 overload takes no provider.
        //? if >=1.21.1 {
        writeInventoryToTag(tag, this.registryAccess());
        //?} else {
        /*writeInventoryToTag(tag);*///?}
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        readCustomTag(tag);
        //? if >=1.21.1 {
        readInventoryFromTag(tag, this.registryAccess());
        //?} else {
        /*readInventoryFromTag(tag);*///?}
    }
    //?}

    /**
     * Capture this mob's full additional save data (custom fields + inventory) into a
     * {@link CompoundTag} — the reincarnation / friend-echo snapshot. Version-neutral: on
     * 26.x it bridges the {@code ValueOutput} stream through a {@code TagValueOutput}.
     */
    public CompoundTag captureCustomData() {
        //? if >=26 {
        /*net.minecraft.world.level.storage.TagValueOutput out =
            net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, this.registryAccess());
        addAdditionalSaveData(out);
        return out.buildResult();
        *///?} else {
        CompoundTag tag = new CompoundTag();
        addAdditionalSaveData(tag);
        return tag;
        //?}
    }

    /**
     * Restore additional save data from a {@code captureCustomData()} snapshot. Version-neutral;
     * on 26.x bridges through a {@code TagValueInput}.
     */
    public void applyCustomData(CompoundTag tag) {
        //? if >=26 {
        /*net.minecraft.world.level.storage.ValueInput in =
            net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, this.registryAccess(), tag);
        readAdditionalSaveData(in);
        *///?} else {
        readAdditionalSaveData(tag);
        //?}
    }

    /** Write every PlayerMob custom field to {@code tag}. Version-agnostic (CompoundTag + NbtCompat). */
    private void writeCustomTag(CompoundTag tag) {
        traits.save(tag);
        feelings.save(tag);
        tag.putInt(TAG_SKIN_INDEX, getSkinIndex());
        // Persist the arm model for bundled mobs too (previously URL-only). Additive:
        // a save without this key reads back false ⇒ wide (the old bundled-mob look).
        tag.putBoolean(TAG_SKIN_SLIM, isSkinSlim());
        tag.putBoolean(TAG_CLOSES_DOORS, this.closesDoors);
        tag.putBoolean(TAG_NATURAL_ORIGIN, this.naturalOrigin);
        // Per-mob order defaults (timeout / interruptibility) applied when a /playermob order command
        // omits the flags. Additive — a save without these keys reads back the 2-min / interruptible
        // defaults. The live pending order itself is transient and never persisted.
        tag.putInt(TAG_ORDER_TIMEOUT, this.orderTimeoutDefaultTicks);
        tag.putBoolean(TAG_ORDER_INTERRUPTIBLE, this.orderInterruptibleDefault);
        // Train march direction — additive. Only written once latched (!= 0) so a
        // mob that never boarded a train round-trips no key (matches the URL-skin
        // additive pattern above).
        if (this.trainExploreDir != 0) {
            tag.putInt(TAG_TRAIN_EXPLORE_DIR, this.trainExploreDir);
        }
        // Friend-pair partner — additive. Only written when paired (most mobs aren't), so an
        // unpaired mob round-trips no key. Survives the rare save in the few ticks before
        // both partners have latched their shared march direction.
        if (this.trainPairPartner != null) {
            NbtCompat.putUUID(tag, TAG_TRAIN_PAIR_PARTNER, this.trainPairPartner);
        }
        // URL skin tags are purely additive on top of v1 (SkinIndex). Only
        // write the URL key when set, so 0.2.0-loaded mobs that never had a
        // URL assigned don't round-trip an empty string back into the save.
        String url = getSkinTextureUrl();
        if (!url.isEmpty()) {
            tag.putString(TAG_SKIN_TEXTURE_URL, url);
        }

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
            NbtCompat.putUUID(entry, TAG_UUID, e.getKey());
            entry.putLong(TAG_TICK, e.getValue());
            entities.add(entry);
        }
        tag.put(TAG_EXPLORED_ENTITIES, entities);

        // Stay-near tether — additive. Only written when tethered (most mobs aren't), so an
        // untethered mob round-trips no key (matches the TrainExploreDir / SkinTextureUrl pattern).
        if (stayAnchor != null) {
            CompoundTag stay = new CompoundTag();
            stayAnchor.save(stay);
            tag.put(TAG_STAY_NEAR, stay);
        }
    }

    /** Read every PlayerMob custom field from {@code tag}. Version-agnostic (CompoundTag + NbtCompat). */
    private void readCustomTag(CompoundTag tag) {
        // Traits + feelings; missing keys keep defaults. Legacy *Personality keys ignored.
        traits.load(tag);
        feelings.load(tag);
        pushDispositionToClient();
        // Only mark the skin explicit when a key is really present — a trait-only
        // egg (archetype) carries no Skin* keys and must still roll a skin at spawn.
        if (NbtCompat.containsOfType(tag, TAG_SKIN_INDEX, NbtCompat.ANY_NUMERIC)) {
            setSkinIndex(NbtCompat.getIntOr(tag, TAG_SKIN_INDEX, 0));
            // #73: restore the per-mob arm model for index-path mobs too. Missing key
            // (pre-per-mob-slim saves) ⇒ false ⇒ wide, so old bundled mobs keep their
            // wide look. A URL mob re-applies its authored model in the block below.
            setSkinSlim(NbtCompat.getBooleanOr(tag, TAG_SKIN_SLIM, false));
            skinExplicit = true;
        }
        // Missing key (pre-door-feature saves) ⇒ false ⇒ leave-open. Additive.
        this.closesDoors = NbtCompat.getBooleanOr(tag, TAG_CLOSES_DOORS, false);
        // An explicit key always wins. Absent: keep what finalizeSpawn decided if it already ran (a spawn
        // egg's entity_data merge lands after it), else default to naturally spawned — which is what mobs
        // saved before the scavenging gates existed should count as.
        if (NbtCompat.containsOfType(tag, TAG_NATURAL_ORIGIN, NbtCompat.ANY_NUMERIC)) {
            this.naturalOrigin = NbtCompat.getBooleanOr(tag, TAG_NATURAL_ORIGIN, true);
            this.naturalOriginResolved = true;
        } else if (!this.naturalOriginResolved) {
            this.naturalOrigin = true;
        }
        // Missing keys ⇒ the 2-min / interruptible order defaults (pre-durable-orders saves). Additive.
        this.orderTimeoutDefaultTicks = NbtCompat.getIntOr(tag, TAG_ORDER_TIMEOUT, Order.DEFAULT_TIMEOUT_TICKS);
        this.orderInterruptibleDefault = NbtCompat.getBooleanOr(tag, TAG_ORDER_INTERRUPTIBLE, true);
        // Missing key ⇒ 0 ⇒ unlatched ⇒ re-derived on the next train boarding. Additive.
        this.trainExploreDir = NbtCompat.getIntOr(tag, TAG_TRAIN_EXPLORE_DIR, 0);
        // Missing key ⇒ null ⇒ no pair (every pre-pair-feature mob). Additive.
        this.trainPairPartner =
            NbtCompat.hasUUID(tag, TAG_TRAIN_PAIR_PARTNER) ? NbtCompat.getUUID(tag, TAG_TRAIN_PAIR_PARTNER) : null;
        // Backward compat: 0.2.0 saves have no SkinTextureUrl tag. Missing key
        // ⇒ URL stays the default "" ⇒ renderer uses the legacy bundled-
        // vanilla path keyed off SkinIndex. New v2 mobs round-trip the URL.
        boolean urlLoaded = false;
        if (NbtCompat.containsOfType(tag, TAG_SKIN_TEXTURE_URL, Tag.TAG_STRING)) {
            setSkinTextureUrl(NbtCompat.getStringOr(tag, TAG_SKIN_TEXTURE_URL, ""));
            setSkinSlim(NbtCompat.getBooleanOr(tag, TAG_SKIN_SLIM, false));
            skinExplicit = true;
            urlLoaded = true;
        }
        // Summon-by-player-name. Gated on urlLoaded (not skinExplicit) so an explicit SkinTextureUrl
        // still wins, but a bare SkinIndex does NOT block the name: a spawn egg's entity_data is applied
        // via CustomData.loadInto, which pre-saves the entity (always writing SkinIndex) before merging
        // the egg's tag — that injected index would otherwise trip skinExplicit and silently drop the
        // name. If the skin is already known (a local file, or a player already in the resolver cache —
        // the common repeat-spawn case), bake it in synchronously HERE: readCustomTag runs before the
        // entity is tracked to clients, so the spawn packet carries the real skin — no default frame, no
        // race. Otherwise keep it pending for the off-thread first-tick resolve (see
        // resolvePendingSkinPlayerName); readAdditionalSaveData has no guaranteed server-thread access.
        if (!urlLoaded && NbtCompat.containsOfType(tag, TAG_SKIN_PLAYER_NAME, Tag.TAG_STRING)) {
            String name = NbtCompat.getStringOr(tag, TAG_SKIN_PLAYER_NAME, "");
            if (!name.isBlank()) {
                skinExplicit = true;
                if (!SkinNameApplier.applyIfImmediate(name, this)) {
                    pendingSkinPlayerName = name;
                }
            }
        }

        recentlyExploredBlocks.clear();
        if (NbtCompat.containsOfType(tag, TAG_EXPLORED_BLOCKS, Tag.TAG_LIST)) {
            ListTag blocks = NbtCompat.getListOfType(tag, TAG_EXPLORED_BLOCKS, Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag entry = NbtCompat.compoundAt(blocks, i);
                recentlyExploredBlocks.put(NbtCompat.getLongOr(entry, TAG_POS, 0L),
                    NbtCompat.getLongOr(entry, TAG_TICK, 0L));
            }
        }
        recentlyExploredEntities.clear();
        if (NbtCompat.containsOfType(tag, TAG_EXPLORED_ENTITIES, Tag.TAG_LIST)) {
            ListTag entities = NbtCompat.getListOfType(tag, TAG_EXPLORED_ENTITIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < entities.size(); i++) {
                CompoundTag entry = NbtCompat.compoundAt(entities, i);
                if (NbtCompat.hasUUID(entry, TAG_UUID)) {
                    recentlyExploredEntities.put(NbtCompat.getUUID(entry, TAG_UUID),
                        NbtCompat.getLongOr(entry, TAG_TICK, 0L));
                }
            }
        }

        // Stay-near tether — additive. Missing key ⇒ null ⇒ roams freely (every pre-feature mob).
        // Present ⇒ rebuild the anchor; StayAnchor.load returns null for a malformed/empty compound.
        this.stayAnchor = NbtCompat.containsOfType(tag, TAG_STAY_NEAR, Tag.TAG_COMPOUND)
            ? StayAnchor.load(NbtCompat.getCompoundOrEmpty(tag, TAG_STAY_NEAR))
            : null;
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
     * On death, drop the entire backpack inventory. Combined with the guaranteed
     * {@link #getEquipmentDropChance} override below — which makes {@code super.dropCustomDeathLoot}
     * drop every equipped slot too — the mob drops everything it was carrying, just like a player.
     * Mirrors {@code Pillager.dropCustomDeathLoot} for the backpack half.
     */
    //? if >=1.21.1 {
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
    //?} else {
    /*@Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
    *///?}
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            this.dropAtLocation(stack);
        }
        this.inventory.clearContent();
    }

    // ---- Provocation reaction --------------------------------------------

    /**
     * When struck by a categorisable entity: cool the mob's feeling toward a
     * player/PlayerMob attacker in proportion to the damage ({@link #DMG_TO_FEELING}),
     * scaled down for aggressive mobs ({@link DispositionResolver#attackScale}), then react
     * immediately — a fighter ({@code fightFlight >= 5}) retaliates;
     * a flighty mob drops the retaliation target so {@link FleeFromCategoryGoal}
     * takes over. Creative/spectator attackers are ignored entirely.
     */
    /**
     * Bank Flight for a real player this mob just landed a blow on: they were hit and, so far,
     * have not hit back. Mirrored by {@link #forfeitRestraint} — one blow in return hands every
     * banked point straight back, so the credit only survives a fight they never joined.
     *
     * <p>Called from {@link WitnessedAttacks#onHurt} once per hit that actually dealt damage.
     * {@code amount} is the incoming, pre-mitigation damage — the same basis the attacker-side
     * {@code Signal.ATTACK} credit in {@link #hurt} uses, so damage dealt and damage endured are
     * measured the same way and cancel cleanly.</p>
     */
    public void onStruckPlayer(ServerPlayer player, float amount) {
        if (amount <= 0.0F || isIgnoredPlayer(player)) {
            return;   // creative/spectator are outside the social model entirely
        }
        if (feelings.accrueTimidity(player.getUUID(), amount) > 0.0F) {
            PlayerLifeStore.record(player, PlayerLifeRecord.Signal.FLEE, amount);
        }
    }

    /**
     * Credit {@code fled} for breaking away clean from this mob — it was hunting them, it isn't
     * any more, and they are still alive to tell it. Skipped if they ever hit back (that was a
     * fight, not a getaway) and capped at one payout per pairing, so an echo that re-acquires and
     * loses them all afternoon is worth one escape.
     */
    private void creditEscape(Player fled) {
        if (!isAlive() || !fled.isAlive() || fled.isDeadOrDying()) {
            return;   // dying while it hunts you is the opposite of escaping it
        }
        if (!(fled instanceof ServerPlayer sp) || isIgnoredPlayer(fled)) {
            return;
        }
        if (feelings.isAnswered(sp.getUUID()) || !feelings.markEscaped(sp.getUUID())) {
            return;
        }
        feelings.accrueTimidity(sp.getUUID(), FeelingRecord.ESCAPE_TIMIDITY);
        PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.FLEE, FeelingRecord.ESCAPE_TIMIDITY);
    }

    /**
     * They hit back (or landed the kill) — hand back every point of Flight this mob had banked
     * for them. Idempotent: the ledger returns the forfeited total once and 0 forever after, so
     * the cancellation can never over-refund and drive the life tally negative.
     */
    private void forfeitRestraint(ServerPlayer sp) {
        float forfeited = feelings.markAnswered(sp.getUUID());
        if (forfeited > 0.0F) {
            PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.FLEE, -forfeited);
        }
    }

    //? if >=26 {
    /*@Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean result = super.hurtServer(level, source, amount);
        // hurtServer only ever runs server-side, so the pre-26 !isClientSide guard is implicit.
        if (result && source.getEntity() instanceof LivingEntity attacker) {
    *///?} else {
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && !level().isClientSide && source.getEntity() instanceof LivingEntity attacker) {
    //?}
            // Creative/Spectator players are ignored: take the hit (they can still
            // kill the mob) but never retaliate or flip personality toward them.
            // super.hurt() already set lastHurtByMob — clear it so the vanilla
            // HurtByTargetGoal doesn't re-acquire them every tick.
            if (isIgnoredPlayer(attacker)) {
                setLastHurtByMob(null);
                return result;
            }
            TargetCategory category = TargetCategory.classify(attacker);
            if (category != null) {
                // Remember the attacker independently of the vanilla lastHurtByMob the
                // flee branch below clears — this is what a defender reads to learn who
                // hurt a loved one, even when that loved one flees and wipes its own.
                this.lastAttacker = attacker;
                this.lastAttackerTick = tickCount;
                // A player/PlayerMob hit cools the feeling toward them, ∝ damage
                // (a solid blow can flip neutral straight into "hate").
                if (category == TargetCategory.PLAYERS) {
                    // recordAttack (not adjust): a ≥1-feeling loss re-opens crouch headroom.
                    // More aggressive mobs care less about being hit (DispositionResolver.attackScale).
                    feelings.recordAttack(attacker.getUUID(),
                        -amount * DMG_TO_FEELING * DispositionResolver.attackScale(fightFlight()));
                    // Credit the real player's lifetime aggression by the damage dealt — at a
                    // tenth weight if this mob had already come for them. Read the flag BEFORE
                    // the retaliation below can set it, so a first strike is never self-defence.
                    if (attacker instanceof ServerPlayer sp) {
                        PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.ATTACK, amount,
                            feelings.isProvoked(sp.getUUID()));
                        // ...and take back the Flight this mob's own blows had banked for them.
                        // After the ATTACK credit, so the provoked read above is untouched.
                        forfeitRestraint(sp);
                    }
                    pushDispositionToClient();
                }
                // Immediate response: fighters retaliate (keep the HurtByTargetGoal
                // target); flighty mobs drop it so FleeFromCategoryGoal takes over. A
                // mid fight/flight mob weighs whether it can win this exchange before
                // standing its ground — this is also the break-off path for a fight
                // the proactive acquisition started but is now losing.
                DispositionResolver.HurtResponse response = DispositionResolver.onHurt(
                    traits.fightFlight(), selfCombatPower(), combatPowerOf(attacker));
                if (response == DispositionResolver.HurtResponse.FLEE) {
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
    //? if <26 {
    @Override
    protected float getEquipmentDropChance(EquipmentSlot slot) {
        return GUARANTEED_EQUIPMENT_DROP_CHANCE;
    }
    //?}
    //? if >=26 {
    /*// 26.x removed the overridable getEquipmentDropChance; drop chances are now per-slot state
    // set via setDropChance. applyGuaranteedDrops() (called from the constructor) stamps every
    // equipment slot with the guaranteed-drop sentinel, matching the <26 override behaviour.
    private void applyGuaranteedDrops() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setDropChance(slot, GUARANTEED_EQUIPMENT_DROP_CHANCE);
        }
    }
    *///?}

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
        // Credit the real player who killed this mob with their lifetime aggression — discounted
        // when this mob was the aggressor (it hunted them, they finished it).
        if (getKillCredit() instanceof ServerPlayer sp) {
            PlayerLifeStore.record(sp, PlayerLifeRecord.Signal.KILL, 1,
                feelings.isProvoked(sp.getUUID()));
            // Killing it is answering it — covers the kill that lands without a hurt() of ours
            // first (a shove into lava, a crushing block).
            forfeitRestraint(sp);
        }
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
        if (!GameRuleCompat.showDeathMessages(serverLevel)) {
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

    //? if <1.21.1 {
    /*// CrossbowAttackMob declares shootCrossbowProjectile abstract in 1.20.1 (it became a
    // private helper inside performCrossbowAttack in 1.21.1, so the interface no longer
    // exposes it). Delegate to the interface's 5-arg default exactly as vanilla Monster /
    // Pillager do — projectile speed 1.6 matches the standard crossbow bolt.
    @Override
    public void shootCrossbowProjectile(LivingEntity target, ItemStack crossbow,
                                        net.minecraft.world.entity.projectile.Projectile projectile, float angle) {
        this.shootCrossbowProjectile(this, target, projectile, angle, 1.6F);
    }
    *///?}

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
            // Vanilla crossbow firing shoots the bolt already loaded into the charge. getProjectile hands
            // vanilla a throwaway COPY (so it can't deplete the backpack itself); the one real round (arrow or
            // firework) is consumed deterministically when the shot fires — see PlayerMobCrossbowAttackGoal.
            this.performCrossbowAttack(this, 1.6F);
            return;
        }
        // Bow path. Don't dry-fire with no arrows (the goal also gates on this, but guard the fire itself).
        if (!this.hasRangedAmmo(mainhand)) {
            return;
        }
        ItemStack arrowStack = this.getProjectile(mainhand);
        if (arrowStack.isEmpty()) {
            return;
        }
        // Build the projectile from a single-count copy so consuming the backing stack below can't disturb
        // the in-flight arrow's item (potion/tipped data is carried on the copy).
        ItemStack projectileStack = arrowStack.copy();
        projectileStack.setCount(1);
        //? if >=1.21.1 {
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, projectileStack, velocity, mainhand);
        //?} else {
        /*AbstractArrow arrow = ProjectileUtil.getMobArrow(this, projectileStack, velocity);*///?}

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
        // Consume one real arrow from the backpack now the bolt has left the bow.
        this.consumeAmmoForShot();
    }

    /**
     * The projectile a bow/crossbow will fire — overridden so a PlayerMob draws from its <em>backpack</em>
     * inventory, which vanilla {@code Mob.getProjectile} never consults (it only checks the hands and then
     * hands back a phantom {@link Items#ARROW}). Returns a <em>copy</em> of the first backpack stack the weapon
     * accepts ({@link RangedAmmo#accepts} — arrows for a bow, arrows or firework rockets for a crossbow) so the
     * fired projectile matches the real ammo (tipped/spectral arrows and fireworks all work) while vanilla can't
     * itself deplete the backpack — the single round is consumed deterministically at fire time (bow:
     * {@link #performRangedAttack}; crossbow: {@code PlayerMobCrossbowAttackGoal}). This keeps consumption
     * identical across MC versions regardless of vanilla's mob-side ammo rules.
     *
     * <p>With no accepted ammo and {@code requireArrows} on, returns {@link ItemStack#EMPTY} so the ranged path
     * can't fire; with {@code requireArrows} off it restores the vanilla phantom arrow (infinite ammo). Only
     * meaningful for {@link ProjectileWeaponItem}s.</p>
     */
    @Override
    public ItemStack getProjectile(ItemStack weapon) {
        if (!(weapon.getItem() instanceof ProjectileWeaponItem)) {
            return ItemStack.EMPTY;
        }
        int slot = RangedAmmo.firstAmmoSlot(this.inventory, weapon);
        if (slot >= 0) {
            return this.inventory.getItem(slot).copy();
        }
        return PlayerMobConfig.requireArrows() ? ItemStack.EMPTY : new ItemStack(Items.ARROW);
    }

    /**
     * Whether this mob may fire {@code weapon} right now: true when {@code requireArrows} is off (vanilla
     * infinite ammo) or the backpack holds ammo that weapon accepts ({@link RangedAmmo#accepts} — arrows for a
     * bow, arrows or fireworks for a crossbow). Single source of truth for the ammo-aware weapon switch
     * ({@link #equipBestWeaponForTarget}), both ranged combat goals, and {@code SeekAmmoGoal}.
     */
    public boolean hasRangedAmmo(ItemStack weapon) {
        // Modded firearms always need their real ammo carried — "infinite ammo" (requireArrows=false) can't
        // synthesize an arbitrary mod's cartridge item, and the fake-player fire drive lends a real round from
        // the backpack to the mod's own consume logic (see ModdedRangedAttackGoal).
        if (PlayerMobConfig.moddedRanged().isRangedWeapon(weapon)) {
            return hasModdedAmmoFor(weapon);
        }
        return !PlayerMobConfig.requireArrows() || RangedAmmo.hasAmmoFor(this.inventory, weapon);
    }

    /**
     * Whether the mob carries ammo the given modded {@code weapon} accepts — in its backpack or its off hand.
     * The off hand is checked so an admin can arm a mob entirely with commands
     * ({@code /item replace entity … weapon.offhand with <cartridge>}); the backpack is otherwise fillable only
     * by floor pickup.
     */
    private boolean hasModdedAmmoFor(ItemStack weapon) {
        if (firstModdedAmmoSlot(weapon) >= 0) {
            return true;
        }
        ItemStack off = getOffhandItem();
        return !off.isEmpty() && PlayerMobConfig.moddedRanged().ammoMatches(weapon, off);
    }

    /** First backpack slot holding ammo the given modded ranged {@code weapon} accepts (per config), or -1. */
    private int firstModdedAmmoSlot(ItemStack weapon) {
        ModdedRangedWeapons registry = PlayerMobConfig.moddedRanged();
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty() && registry.ammoMatches(weapon, stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Consume one round of the modded {@code weapon}'s ammo — from the backpack first, else the off hand. Called
     * by {@link ModdedRangedAttackGoal} when the mob loads a round to begin its (real-time) reload, so a shot
     * costs exactly one cartridge. A no-op if the mob carries no matching ammo.
     */
    public void consumeOneModdedAmmo(ItemStack weapon) {
        int slot = firstModdedAmmoSlot(weapon);
        if (slot >= 0) {
            ItemStack stack = this.inventory.getItem(slot);
            stack.shrink(1);
            if (stack.isEmpty()) {
                this.inventory.setItem(slot, ItemStack.EMPTY);
            }
            this.inventory.setChanged();
            return;
        }
        ItemStack off = getOffhandItem();
        if (!off.isEmpty() && PlayerMobConfig.moddedRanged().ammoMatches(weapon, off)) {
            off.shrink(1);
            if (off.isEmpty()) {
                setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Consume one round of the held weapon's ammo for a fired shot — an arrow for a bow, an arrow or firework
     * for a crossbow (whichever {@link #getProjectile} loaded from the first matching slot). Called the moment a
     * shot leaves the mob ({@link #performRangedAttack} / {@code PlayerMobCrossbowAttackGoal}). A no-op when
     * {@code requireArrows} is off (ammo is "infinite") or the backpack holds no accepted ammo.
     */
    public void consumeAmmoForShot() {
        if (PlayerMobConfig.requireArrows()) {
            RangedAmmo.consumeOneAmmo(this.inventory, getMainHandItem());
        }
    }

    /** True if the mob carries a crossbow anywhere (main hand or backpack) — crossbows can fire fireworks. */
    public boolean ownsCrossbow() {
        if (getMainHandItem().getItem() instanceof CrossbowItem) return true;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            if (this.inventory.getItem(i).getItem() instanceof CrossbowItem) return true;
        }
        return false;
    }

    /** The best ranged weapon the mob owns (main hand or backpack), or {@link ItemStack#EMPTY} — for the ammo seek. */
    public ItemStack bestRangedWeaponStack() {
        Located ranged = bestOfCategory(ItemPickupPolicy.WeaponCategory.RANGED);
        return ranged == null ? ItemStack.EMPTY : ranged.stack();
    }

    /**
     * Whether the mob wants {@code stack} as ranged ammo to hoard: arrows always (any ranged weapon may use
     * them), fireworks when it owns a crossbow to fire them with, plus any ammo of a configured modded firearm
     * (cartridges, magazines) so the mob can restock a musket the same way it restocks arrows. Drives floor
     * pickup and the ammo seek.
     */
    public boolean wantsAsAmmo(ItemStack stack) {
        return RangedAmmo.isArrow(stack)
            || (RangedAmmo.isFirework(stack) && ownsCrossbow())
            || PlayerMobConfig.moddedRanged().isModdedAmmo(stack);
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
