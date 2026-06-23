package games.brennan.playermob.player;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    private static final String DATA_NAME = "playermob_lives";

    private static final String TAG_CURRENT = "Current";
    private static final String TAG_LAST = "LastLife";
    private static final String TAG_UUID = "UUID";
    private static final String TAG_NAME = "Name";
    private static final String TAG_SNAPSHOT = "Snapshot";

    private final Map<UUID, PlayerLifeRecord> current = new HashMap<>();
    // Legacy per-world last-life snapshots from builds before GlobalLifeStore existed.
    // Retained only until the global store imports them via drainLegacyLastLives().
    private final Map<UUID, CompoundTag> legacyLastLife = new HashMap<>();
    private final Map<UUID, String> legacyLastName = new HashMap<>();

    public PlayerLifeStore() {}

    /** Fetch (or create) the single store, always from the overworld's storage. */
    public static PlayerLifeStore get(ServerLevel level) {
        DimensionDataStorage storage = level.getServer().overworld().getDataStorage();
        //? if >=1.21.1 {
        return storage.computeIfAbsent(
            new SavedData.Factory<>(PlayerLifeStore::new, PlayerLifeStore::load, null),
            DATA_NAME);
        //?} else {
        /*// 1.20.1 computeIfAbsent takes (loader, factory, name) directly — no SavedData.Factory.
        return storage.computeIfAbsent(PlayerLifeStore::load, PlayerLifeStore::new, DATA_NAME);*/
        //?}
    }

    // ---- reads ------------------------------------------------------------

    /** The player's in-progress life tally, or {@link PlayerLifeRecord#EMPTY} if untracked. */
    public PlayerLifeRecord current(UUID id) {
        return current.getOrDefault(id, PlayerLifeRecord.EMPTY);
    }

    // ---- writes -----------------------------------------------------------

    /**
     * Credit a survival player's live record with one player→mob action.
     * Convenience entry point for the entity hooks — resolves the store from the
     * player's server level.
     */
    public static void record(ServerPlayer player, PlayerLifeRecord.Signal signal, float magnitude) {
        get(player.serverLevel()).credit(player.getUUID(), signal, magnitude);
    }

    public void credit(UUID id, PlayerLifeRecord.Signal signal, float magnitude) {
        current.put(id, current(id).credit(signal, magnitude));
        setDirty();
    }

    /**
     * Reset the player's in-progress tally — called when a life completes. The
     * completed snapshot itself is stored in {@link GlobalLifeStore}, not here.
     */
    public void resetCurrent(UUID id) {
        if (current.remove(id) != null) {
            setDirty();
        }
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

    //? if >=1.21.1 {
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*@Override
    public CompoundTag save(CompoundTag tag) {
    *///?}
        ListTag currentList = new ListTag();
        for (Map.Entry<UUID, PlayerLifeRecord> e : current.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue; // don't bloat the save with empty tallies
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            e.getValue().save(entry);
            currentList.add(entry);
        }
        tag.put(TAG_CURRENT, currentList);

        // Retain un-migrated legacy snapshots so a pre-update world keeps them until the
        // global store imports them; drops away once drainLegacyLastLives() has run.
        if (!legacyLastLife.isEmpty()) {
            ListTag lastList = new ListTag();
            for (Map.Entry<UUID, CompoundTag> e : legacyLastLife.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID(TAG_UUID, e.getKey());
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

    //? if >=1.21.1 {
    public static PlayerLifeStore load(CompoundTag tag, HolderLookup.Provider registries) {
    //?} else {
    /*public static PlayerLifeStore load(CompoundTag tag) {
    *///?}
        PlayerLifeStore store = new PlayerLifeStore();

        ListTag currentList = tag.getList(TAG_CURRENT, Tag.TAG_COMPOUND);
        for (int i = 0; i < currentList.size(); i++) {
            CompoundTag entry = currentList.getCompound(i);
            if (entry.hasUUID(TAG_UUID)) {
                store.current.put(entry.getUUID(TAG_UUID), PlayerLifeRecord.load(entry));
            }
        }

        // Legacy last-life snapshots from older builds — loaded only so GlobalLifeStore
        // can import them once; current builds write these in the global file instead.
        ListTag lastList = tag.getList(TAG_LAST, Tag.TAG_COMPOUND);
        for (int i = 0; i < lastList.size(); i++) {
            CompoundTag entry = lastList.getCompound(i);
            if (entry.hasUUID(TAG_UUID)) {
                UUID id = entry.getUUID(TAG_UUID);
                store.legacyLastLife.put(id, entry.getCompound(TAG_SNAPSHOT));
                if (entry.contains(TAG_NAME, Tag.TAG_STRING)) {
                    store.legacyLastName.put(id, entry.getString(TAG_NAME));
                }
            }
        }
        return store;
    }
}
