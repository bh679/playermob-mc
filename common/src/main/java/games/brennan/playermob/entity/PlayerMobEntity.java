package games.brennan.playermob.entity;

import games.brennan.playermob.entity.goal.RaidArmorStandsGoal;
import games.brennan.playermob.entity.goal.RaidContainersGoal;
import games.brennan.playermob.entity.goal.WeaponAwareAttackGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
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
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The PlayerMob entity. Player-shaped (rendered with vanilla PlayerModel via
 * the client renderer) but driven by a vanilla-mob {@link Monster} AI brain.
 *
 * <p><b>Combat</b> is weapon-aware — see {@link WeaponAwareAttackGoal}. The
 * mob supports crossbows, bows, and any melee weapon (or fists) depending on
 * what's in its main hand at any given tick. Implements both
 * {@link CrossbowAttackMob} (which extends {@link RangedAttackMob}) for
 * vanilla ranged-goal compatibility.</p>
 *
 * <p><b>Targets</b> are driven by {@link Stance} (v1 ships exactly one). The
 * goal-selector predicate forwards to {@code stance.permitsTargeting(this, candidate)}
 * so new stances drop in by adding enum constants — no goal rewiring.</p>
 *
 * <p><b>Skins (v1.5)</b> — Each mob rolls a {@link #getSkinIndex skin index}
 * in {@link #finalizeSpawn} from {@code [0, SKIN_COUNT)}. The client renderer
 * ({@code PlayerMobRenderer}, not imported here to keep this class
 * server-loadable) reads the index to pick a bundled texture. Persists
 * across save/load.</p>
 *
 * <p><b>Inventory raiding (v1.5)</b> — Implements {@link InventoryCarrier}
 * so the mob has a backpack. {@link RaidContainersGoal} +
 * {@link RaidArmorStandsGoal} drive scan/path/swap behaviour. A
 * "recently explored" cooldown map keeps the mob from looping the same
 * chest. On death, {@link #dropCustomDeathLoot} dumps the inventory.</p>
 *
 * <p><b>Spawning</b> — spawn egg + {@code /summon playermob:player_mob}
 * only. No natural spawns, no raid hooks.</p>
 */
public class PlayerMobEntity extends Monster implements CrossbowAttackMob, InventoryCarrier {

    // ---- DataTracker ------------------------------------------------------

    private static final EntityDataAccessor<Integer> DATA_STANCE =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_IS_CHARGING_CROSSBOW =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Integer> DATA_SKIN_INDEX =
        SynchedEntityData.defineId(PlayerMobEntity.class, EntityDataSerializers.INT);

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
     * Chest {@code triggerEvent} ID for "viewer count changed" — drives the
     * lid animation. See {@link ChestBlockEntity#triggerEvent}.
     */
    private static final int CHEST_VIEWERS_EVENT = 1;

    private static final String TAG_STANCE = "Stance";
    private static final String TAG_SKIN_INDEX = "SkinIndex";
    private static final String TAG_EXPLORED_BLOCKS = "ExploredBlocks";
    private static final String TAG_EXPLORED_ENTITIES = "ExploredEntities";
    private static final String TAG_POS = "Pos";
    private static final String TAG_UUID = "UUID";
    private static final String TAG_TICK = "Tick";

    // ---- Fields -----------------------------------------------------------

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
        builder.define(DATA_SKIN_INDEX, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new WeaponAwareAttackGoal(this, 1.0, 8.0f));
        this.goalSelector.addGoal(3, new RaidContainersGoal(this, /* speed */ 0.9, /* radius */ 12));
        this.goalSelector.addGoal(3, new RaidArmorStandsGoal(this, /* speed */ 0.9, /* radius */ 12.0));
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
            candidate -> getStance().permitsTargeting(this, candidate)));
    }

    /**
     * Roll the random skin index once at spawn so all clients see the same
     * value. Server-side; syncs to clients via the DATA_SKIN_INDEX TrackedData.
     */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor world,
                                        DifficultyInstance difficulty,
                                        MobSpawnType reason,
                                        SpawnGroupData data) {
        setSkinIndex(world.getRandom().nextInt(SKIN_COUNT));
        return super.finalizeSpawn(world, difficulty, reason, data);
    }

    // ---- Stance accessors -------------------------------------------------

    public Stance getStance() {
        return Stance.byOrdinal(this.entityData.get(DATA_STANCE));
    }

    public void setStance(Stance stance) {
        this.entityData.set(DATA_STANCE, stance.ordinal());
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

    // ---- InventoryCarrier ------------------------------------------------

    @Override
    public SimpleContainer getInventory() {
        return this.inventory;
    }

    // ---- Equipment swap helpers (called from raid goals) -----------------

    /**
     * Try to swap a candidate item from a container slot into the mob's
     * equipment. If the candidate is better than what the mob is wearing in
     * the matching slot, take it (clearing the container slot) and put the
     * displaced item back into the container.
     *
     * <p>Lives on the entity rather than the static {@link EquipmentEvaluator}
     * because {@link #canReplaceCurrentItem} is {@code protected} on Mob —
     * only the Mob subclass can read it.</p>
     */
    public boolean tryReplaceFromContainer(Container source, int slotIdx) {
        if (source == null) return false;
        ItemStack candidate = source.getItem(slotIdx);
        if (candidate.isEmpty()) return false;
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
     * Pre-check variant of {@link #tryReplaceFromContainer} — answers
     * "would the mob take this slot if asked?" without actually swapping.
     * Used by the raid goal to skip worthless slots without burning the
     * per-swap delay budget.
     */
    public boolean wouldReplaceFromContainer(Container source, int slotIdx) {
        if (source == null) return false;
        ItemStack candidate = source.getItem(slotIdx);
        if (candidate.isEmpty()) return false;
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
        tag.putInt(TAG_STANCE, getStance().ordinal());
        tag.putInt(TAG_SKIN_INDEX, getSkinIndex());
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
        // byOrdinal guards against missing/invalid stored values.
        setStance(Stance.byOrdinal(tag.getInt(TAG_STANCE)));
        setSkinIndex(tag.getInt(TAG_SKIN_INDEX));
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
     * On death, drop everything in the inventory in addition to vanilla
     * equipment drops. Mirrors {@code Pillager.dropCustomDeathLoot}.
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

    /**
     * Hook the death event to force-close any container the mob had open.
     * {@link RaidContainersGoal#stop} also closes the container, but when the
     * mob dies mid-raid the goal selector stops ticking before it gets the
     * chance — this override is the safety net so chests/barrels don't
     * stay visually stuck open.
     */
    @Override
    public void die(DamageSource source) {
        closeOpenedContainer();
        super.die(source);
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

        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F,
                       1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    // ---- Sounds (pillager-like, mirrors villager-style "person dies" feel)

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

    /**
     * Returning Enemy from {@code instanceof} checks is already provided
     * by the {@link Monster} superclass — this entity is automatically a
     * valid target for {@link NearestAttackableTargetGoal} filters that
     * test for {@link Enemy}.
     */
}
