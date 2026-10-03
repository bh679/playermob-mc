package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.compat.PetSnapshots;
//? if <26 {
import net.minecraft.core.HolderLookup;
//?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
//? if >=26 {
/*import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
*///?} else {
import net.minecraft.world.level.storage.DimensionDataStorage;
//?}

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

/**
 * World-level persistence for the player-reincarnation feature, keyed by player
 * UUID. Pure vanilla {@link SavedData} stored on the overworld's
 * {@link DimensionDataStorage} — so it lives entirely in common code and behaves
 * identically on Fabric, Forge, and NeoForge with no per-loader plumbing.
 *
 * <p>Holds each player's in-progress life tally {@code current}
 * ({@link PlayerLifeRecord}), credited as they act toward PlayerMobs. This tally is
 * world-scoped: it reflects conduct in the world it was earned in, and is reset when
 * a life completes.</p>
 *
 * <p>The <em>completed</em> last-life snapshots used to live here too, but now belong
 * to {@link GlobalLifeStore} so they follow a player across worlds. The only remnant
 * here is a transient {@code legacyLastLife} map: snapshots written by older builds
 * are loaded so {@link GlobalLifeStore} can import them once (see
 * {@link #drainLegacyLastLives}), and are written back on save only until that import
 * runs — so a one-time migration can't be lost to an autosave.</p>
 *
 * <p>Writes a save file {@code world/data/playermob_lives.dat} — additive and
 * non-breaking (worlds without it simply start empty).</p>
 */
public final class PlayerLifeStore extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DATA_NAME = "playermob_lives";

    private static final String TAG_CURRENT = "Current";
    private static final String TAG_LAST = "LastLife";
    private static final String TAG_UUID = "UUID";
    private static final String TAG_NAME = "Name";
    private static final String TAG_SNAPSHOT = "Snapshot";
    private static final String TAG_PETS = "Pets";
    private static final String TAG_LEDGER = "Ledger";

    private final Map<UUID, PlayerLifeRecord> current = new HashMap<>();
    // Every animal each player's current life has tamed — see PetLedger. Reset with the life.
    private final Map<UUID, PetLedger> pets = new HashMap<>();
    // Legacy per-world last-life snapshots from builds before GlobalLifeStore existed.
    // Retained only until the global store imports them via drainLegacyLastLives().
    private final Map<UUID, CompoundTag> legacyLastLife = new HashMap<>();
    private final Map<UUID, String> legacyLastName = new HashMap<>();

    public PlayerLifeStore() {}

    //? if >=26 {
    /*// 26.x SavedData is codec-based: the SavedDataType bundles the id, constructor, and a
    // Codec<PlayerLifeStore>. We reuse the existing CompoundTag (de)serialisation by mapping
    // CompoundTag.CODEC through saveToTag/loadFromTag — same on-disk bytes as earlier versions,
    // so worlds round-trip cleanly. (DataFixTypes.LEVEL is the generic catch-all fixer type.)
    private static final SavedDataType<PlayerLifeStore> TYPE = new SavedDataType<>(
        games.brennan.playermob.compat.RegistryCompat.id(
            games.brennan.playermob.PlayerMob.MOD_ID, DATA_NAME),
        PlayerLifeStore::new,
        net.minecraft.nbt.CompoundTag.CODEC.xmap(
            PlayerLifeStore::loadFromTag,
            store -> store.saveToTag(new net.minecraft.nbt.CompoundTag())),
        net.minecraft.util.datafix.DataFixTypes.LEVEL);
    *///?}

    /** Fetch (or create) the single store, always from the overworld's storage. */
    public static PlayerLifeStore get(ServerLevel level) {
        //? if >=26 {
        /*SavedDataStorage storage = level.getServer().overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
        *///?} else {
        DimensionDataStorage storage = level.getServer().overworld().getDataStorage();
        //? if >=1.21.1 {
        return storage.computeIfAbsent(
            new SavedData.Factory<>(PlayerLifeStore::new, PlayerLifeStore::load, null),
            DATA_NAME);
        //?} else {
        /*// 1.20.1 computeIfAbsent takes (loader, factory, name) directly — no SavedData.Factory.
        return storage.computeIfAbsent(PlayerLifeStore::load, PlayerLifeStore::new, DATA_NAME);*/
        //?}
        //?}
    }

    // ---- reads ------------------------------------------------------------

    /** The player's in-progress life tally, or {@link PlayerLifeRecord#EMPTY} if untracked. */
    public PlayerLifeRecord current(UUID id) {
        return current.getOrDefault(id, PlayerLifeRecord.EMPTY);
    }

    /** Every animal the player's current life has tamed, or {@link PetLedger#EMPTY}. */
    public PetLedger pets(UUID id) {
        return pets.getOrDefault(id, PetLedger.EMPTY);
    }

    // ---- writes -----------------------------------------------------------

    /**
     * Credit a survival player's live record with one player→mob action.
     * Convenience entry point for the entity hooks — resolves the store from the
     * player's server level.
     */
    public static void record(ServerPlayer player, PlayerLifeRecord.Signal signal, float magnitude) {
        record(player, signal, magnitude, false);
    }

    /**
     * As {@link #record(ServerPlayer, PlayerLifeRecord.Signal, float)}, but marking the action
     * as self-defence — the mob had already picked the fight, so the trait distillation weighs
     * it at {@link PlayerLifeRecord#DEFENSIVE_SCALE}.
     */
    public static void record(ServerPlayer player, PlayerLifeRecord.Signal signal, float magnitude,
                              boolean defensive) {
        //? if >=26 {
        /*// 26.x renamed ServerPlayer.serverLevel() → level() (still returns ServerLevel).
        get(player.level()).credit(player.getUUID(), signal, magnitude, defensive);
        *///?} else {
        get(player.serverLevel()).credit(player.getUUID(), signal, magnitude, defensive);
        //?}
    }

    public void credit(UUID id, PlayerLifeRecord.Signal signal, float magnitude) {
        credit(id, signal, magnitude, false);
    }

    public void credit(UUID id, PlayerLifeRecord.Signal signal, float magnitude, boolean defensive) {
        current.put(id, current(id).credit(signal, magnitude, defensive));
        setDirty();
    }

    /**
     * Reset the player's in-progress tally — called when a life completes. The
     * completed snapshot itself is stored in {@link GlobalLifeStore}, not here.
     */
    public void resetCurrent(UUID id) {
        boolean changed = current.remove(id) != null;
        changed |= pets.remove(id) != null; // a new life starts with no pets of its own
        if (changed) {
            setDirty();
        }
    }

    // ---- pet ledger -------------------------------------------------------

    /**
     * Remember {@code pet} as one of {@code owner}'s current life's animals — called from the tame
     * mixins the moment the taming lands. Never throws into the tame flow.
     */
    public static void recordPet(ServerPlayer owner, Entity pet) {
        try {
            //? if >=26 {
            /*ServerLevel level = owner.level();
            *///?} else {
            ServerLevel level = owner.serverLevel();
            //?}
            get(level).rememberPet(owner.getUUID(), entryOf(pet, true));
        } catch (RuntimeException e) {
            LOGGER.warn("[playermob] Failed to remember a tamed pet", e);
        }
    }

    /**
     * Refresh {@code pet}'s entry as it leaves the world — unloaded, changed dimension, or died —
     * so the snapshot an echo returns with is the animal as it was last seen (named, grown, armoured).
     * A pet that now belongs to someone else is forgotten by every life that no longer owns it.
     * Called for ownable entities only (see {@code EntityRemovalPetLedgerMixin}); never throws.
     */
    public static void onPetRemoved(ServerLevel level, Entity pet, boolean died) {
        try {
            get(level).refreshPet(pet, died);
        } catch (RuntimeException e) {
            LOGGER.warn("[playermob] Failed to refresh a remembered pet", e);
        }
    }

    private void refreshPet(Entity pet, boolean died) {
        if (pets.isEmpty()) {
            return;
        }
        UUID petId = pet.getUUID();
        UUID owner = PetSnapshots.ownerUuid(pet);
        for (Map.Entry<UUID, PetLedger> e : pets.entrySet()) {
            if (e.getValue().get(petId) == null) {
                continue;
            }
            if (!e.getKey().equals(owner)) {
                e.setValue(e.getValue().without(petId)); // given away or untamed — no longer theirs
            } else {
                e.setValue(e.getValue().with(entryOf(pet, !died)));
            }
            setDirty();
        }
    }

    void rememberPet(UUID owner, PetLedger.Entry entry) {
        pets.put(owner, pets(owner).with(entry));
        setDirty();
    }

    /** A ledger entry for {@code pet} as it is right now. */
    static PetLedger.Entry entryOf(Entity pet, boolean alive) {
        return new PetLedger.Entry(pet.getUUID(), PetSnapshots.capture(pet), pet.hasCustomName(),
            PetSnapshots.isMount(pet), alive, pet.level().getGameTime());
    }

    /**
     * Move any legacy per-world last-life snapshots into {@code lifeOut}/{@code nameOut}
     * and forget them here, so they stop being written and never re-import. The one-time
     * bridge from the old world-scoped storage to {@link GlobalLifeStore}; a no-op once
     * drained (and on worlds that never had legacy data).
     */
    public void drainLegacyLastLives(Map<UUID, CompoundTag> lifeOut, Map<UUID, String> nameOut) {
        if (legacyLastLife.isEmpty()) {
            return;
        }
        lifeOut.putAll(legacyLastLife);
        nameOut.putAll(legacyLastName);
        legacyLastLife.clear();
        legacyLastName.clear();
        setDirty();
    }

    // ---- persistence ------------------------------------------------------

    //? if <26 {
    //? if >=1.21.1 {
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*@Override
    public CompoundTag save(CompoundTag tag) {
    *///?}
        return saveToTag(tag);
    }
    //?}

    /** Version-agnostic NBT writer — the on-disk format, shared by the pre-26 {@code save}
     *  override and the 26.x codec. */
    private CompoundTag saveToTag(CompoundTag tag) {
        ListTag currentList = new ListTag();
        for (Map.Entry<UUID, PlayerLifeRecord> e : current.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue; // don't bloat the save with empty tallies
            }
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUUID(entry, TAG_UUID, e.getKey());
            e.getValue().save(entry);
            currentList.add(entry);
        }
        tag.put(TAG_CURRENT, currentList);

        ListTag petList = new ListTag();
        for (Map.Entry<UUID, PetLedger> e : pets.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUUID(entry, TAG_UUID, e.getKey());
            entry.put(TAG_LEDGER, e.getValue().save());
            petList.add(entry);
        }
        tag.put(TAG_PETS, petList);

        // Retain un-migrated legacy snapshots so a pre-update world keeps them until the
        // global store imports them; drops away once drainLegacyLastLives() has run.
        if (!legacyLastLife.isEmpty()) {
            ListTag lastList = new ListTag();
            for (Map.Entry<UUID, CompoundTag> e : legacyLastLife.entrySet()) {
                CompoundTag entry = new CompoundTag();
                NbtCompat.putUUID(entry, TAG_UUID, e.getKey());
                String name = legacyLastName.get(e.getKey());
                if (name != null) {
                    entry.putString(TAG_NAME, name);
                }
                entry.put(TAG_SNAPSHOT, e.getValue().copy());
                lastList.add(entry);
            }
            tag.put(TAG_LAST, lastList);
        }
        return tag;
    }

    //? if <26 {
    //? if >=1.21.1 {
    public static PlayerLifeStore load(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*public static PlayerLifeStore load(CompoundTag tag) {
    *///?}
        return loadFromTag(tag);
    }
    //?}

    /** Version-agnostic NBT reader — the on-disk format, shared by the pre-26 {@code load}
     *  factory and the 26.x codec. */
    private static PlayerLifeStore loadFromTag(CompoundTag tag) {
        PlayerLifeStore store = new PlayerLifeStore();

        ListTag currentList = NbtCompat.getListOfType(tag, TAG_CURRENT, Tag.TAG_COMPOUND);
        for (int i = 0; i < currentList.size(); i++) {
            CompoundTag entry = NbtCompat.compoundAt(currentList, i);
            if (NbtCompat.hasUUID(entry, TAG_UUID)) {
                store.current.put(NbtCompat.getUUID(entry, TAG_UUID), PlayerLifeRecord.load(entry));
            }
        }

        // Missing on saves from before lives remembered their pets ⇒ every ledger starts empty.
        ListTag petList = NbtCompat.getListOfType(tag, TAG_PETS, Tag.TAG_COMPOUND);
        for (int i = 0; i < petList.size(); i++) {
            CompoundTag entry = NbtCompat.compoundAt(petList, i);
            if (NbtCompat.hasUUID(entry, TAG_UUID)) {
                store.pets.put(NbtCompat.getUUID(entry, TAG_UUID),
                    PetLedger.load(NbtCompat.getListOfType(entry, TAG_LEDGER, Tag.TAG_COMPOUND)));
            }
        }

        // Legacy last-life snapshots from older builds — loaded only so GlobalLifeStore
        // can import them once; current builds write these in the global file instead.
        ListTag lastList = NbtCompat.getListOfType(tag, TAG_LAST, Tag.TAG_COMPOUND);
        for (int i = 0; i < lastList.size(); i++) {
            CompoundTag entry = NbtCompat.compoundAt(lastList, i);
            if (NbtCompat.hasUUID(entry, TAG_UUID)) {
                UUID id = NbtCompat.getUUID(entry, TAG_UUID);
                store.legacyLastLife.put(id, NbtCompat.getCompoundOrEmpty(entry, TAG_SNAPSHOT));
                if (NbtCompat.containsOfType(entry, TAG_NAME, Tag.TAG_STRING)) {
                    store.legacyLastName.put(id, NbtCompat.getStringOr(entry, TAG_NAME, null));
                }
            }
        }
        return store;
    }
}
