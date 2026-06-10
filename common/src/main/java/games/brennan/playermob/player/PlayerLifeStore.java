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
 * <p>Holds two maps:</p>
 * <ul>
 *   <li>{@code current} — each player's in-progress life tally
 *       ({@link PlayerLifeRecord}), credited as they act toward PlayerMobs.</li>
 *   <li>{@code lastLife} — the snapshot NBT of each player's most recently
 *       completed life (traits + gear + skin), built on death and consumed by the
 *       reincarnation command. Per-life scope: completing a life clears that
 *       player's {@code current} tally.</li>
 * </ul>
 *
 * <p>Writes a new save file {@code world/data/playermob_lives.dat} — additive and
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
    private final Map<UUID, CompoundTag> lastLife = new HashMap<>();
    private final Map<UUID, String> lastName = new HashMap<>();

    public PlayerLifeStore() {}

    /** Fetch (or create) the single store, always from the overworld's storage. */
    public static PlayerLifeStore get(ServerLevel level) {
        DimensionDataStorage storage = level.getServer().overworld().getDataStorage();
        return storage.computeIfAbsent(
            new SavedData.Factory<>(PlayerLifeStore::new, PlayerLifeStore::load, null),
            DATA_NAME);
    }

    // ---- reads ------------------------------------------------------------

    /** The player's in-progress life tally, or {@link PlayerLifeRecord#EMPTY} if untracked. */
    public PlayerLifeRecord current(UUID id) {
        return current.getOrDefault(id, PlayerLifeRecord.EMPTY);
    }

    /** A defensive copy of the player's last-life snapshot NBT, or {@code null} if none. */
    public CompoundTag lastLife(UUID id) {
        CompoundTag tag = lastLife.get(id);
        return tag == null ? null : tag.copy();
    }

    public String lastName(UUID id) {
        return lastName.get(id);
    }

    public boolean hasLastLife(UUID id) {
        return lastLife.containsKey(id);
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

    /** Store a completed life's snapshot and reset the live tally (per-life scope). */
    public void completeLife(UUID id, String name, CompoundTag snapshot) {
        lastLife.put(id, snapshot.copy());
        lastName.put(id, name);
        current.remove(id);
        setDirty();
    }

    // ---- persistence ------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
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

        ListTag lastList = new ListTag();
        for (Map.Entry<UUID, CompoundTag> e : lastLife.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            String name = lastName.get(e.getKey());
            if (name != null) {
                entry.putString(TAG_NAME, name);
            }
            entry.put(TAG_SNAPSHOT, e.getValue().copy());
            lastList.add(entry);
        }
        tag.put(TAG_LAST, lastList);
        return tag;
    }

    public static PlayerLifeStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerLifeStore store = new PlayerLifeStore();

        ListTag currentList = tag.getList(TAG_CURRENT, Tag.TAG_COMPOUND);
        for (int i = 0; i < currentList.size(); i++) {
            CompoundTag entry = currentList.getCompound(i);
            if (entry.hasUUID(TAG_UUID)) {
                store.current.put(entry.getUUID(TAG_UUID), PlayerLifeRecord.load(entry));
            }
        }

        ListTag lastList = tag.getList(TAG_LAST, Tag.TAG_COMPOUND);
        for (int i = 0; i < lastList.size(); i++) {
            CompoundTag entry = lastList.getCompound(i);
            if (entry.hasUUID(TAG_UUID)) {
                UUID id = entry.getUUID(TAG_UUID);
                store.lastLife.put(id, entry.getCompound(TAG_SNAPSHOT));
                if (entry.contains(TAG_NAME, Tag.TAG_STRING)) {
                    store.lastName.put(id, entry.getString(TAG_NAME));
                }
            }
        }
        return store;
    }
}
