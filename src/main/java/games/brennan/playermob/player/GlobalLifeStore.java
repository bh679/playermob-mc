package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.compat.TrainConfinement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global, cross-world log of <em>every</em> completed player life — the
 * reincarnation snapshots — kept in one file outside any world folder so a
 * player's past lives follow them between saves.
 *
 * <p>Append-only: each death is recorded with the carriage (Dungeon-Train room
 * index) it happened in, so Dungeon-Train echo spawns can prefer lives that ended
 * at a similar depth. Selection for a DT echo at carriage {@code C} draws from the
 * deaths within {@link #CARRIAGE_RADIUS} carriages of {@code C} that the live
 * player hasn't already met this life, weighted toward more recent deaths.</p>
 *
 * <p>Stored at {@code <serverDir>/playermob/lives.dat}. For singleplayer that is the
 * {@code .minecraft} (dev {@code run}) directory — shared across every save.</p>
 *
 * <p><b>Lifecycle.</b> Lazy-load on first {@link #get(MinecraftServer)}, single-slot
 * cache keyed by the running server (a new world/restart reloads the same file).
 * Mutations write through to disk. The per-player "used this life" sessions are
 * transient (held on the per-server instance) so a new world resets them; a death
 * also clears that player's session. Server-thread only.</p>
 */
public final class GlobalLifeStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Half-width, in carriages, of the depth band an echo is drawn from around its spawn carriage. */
    public static final int CARRIAGE_RADIUS = 30;

    private static final int NO_CARRIAGE = TrainConfinement.NO_CARRIAGE;

    private static final String DIR = "playermob";
    private static final String FILE = "lives.dat";

    private static final String TAG_DEATHS = "Deaths";
    private static final String TAG_LEGACY_LIVES = "Lives"; // pre-death-log global format
    private static final String TAG_NEXT_ID = "NextId";
    private static final String TAG_ID = "Id";
    private static final String TAG_UUID = "UUID";
    private static final String TAG_NAME = "Name";
    private static final String TAG_CARRIAGE = "Carriage";
    private static final String TAG_SNAPSHOT = "Snapshot";
    private static final String TAG_FRIENDS = "Friends";

    /** NBT key, inside a friend snapshot, holding the label its friend-echo is titled with ("Echo of &lt;label&gt;"). */
    public static final String FRIEND_LABEL_KEY = "FriendLabel";

    /**
     * One recorded death: the player's own reincarnation snapshot, where/when it happened, and
     * snapshots of the PlayerMobs that loved them at death ({@code friendSnapshots}) — replayed as
     * "friend-echoes" beside an echo of this life. Empty when no loved one was nearby, and for every
     * pre-friend-pair record.
     */
    public record DeathRecord(long id, UUID uuid, String name, int carriage,
                              CompoundTag snapshot, List<CompoundTag> friendSnapshots) {
        /** A death with no logged friends — keeps the legacy/test call-sites that predate friend capture. */
        public DeathRecord(long id, UUID uuid, String name, int carriage, CompoundTag snapshot) {
            this(id, uuid, name, carriage, snapshot, List.of());
        }
    }

    private static MinecraftServer cachedServer;
    private static GlobalLifeStore cached;

    private final Path path;
    private final List<DeathRecord> history = new ArrayList<>(); // oldest -> newest
    private long nextId = 1L;

    private GlobalLifeStore(Path path) {
        this.path = path;
    }

    /** Test seam: an in-memory store with no backing file (load/save are no-ops). */
    GlobalLifeStore() {
        this(null);
    }

    public static GlobalLifeStore get(MinecraftServer server) {
        if (cached != null && cachedServer == server) {
            return cached;
        }
        //? if >=1.21.1 {
        GlobalLifeStore store = new GlobalLifeStore(server.getServerDirectory().resolve(DIR).resolve(FILE));
        //?} else {
        /*GlobalLifeStore store = new GlobalLifeStore(server.getServerDirectory().toPath().resolve(DIR).resolve(FILE));*/
        //?}
        store.load();
        cachedServer = server;
        cached = store;
        store.migrateFromWorld(server);
        return store;
    }

    // ---- reads ------------------------------------------------------------

    /** The most recent death recorded for {@code id} (defensive copy), or {@code null}. */
    public CompoundTag mostRecentForPlayer(UUID id) {
        for (int i = history.size() - 1; i >= 0; i--) {
            DeathRecord r = history.get(i);
            if (r.uuid().equals(id)) {
                return r.snapshot().copy();
            }
        }
        return null;
    }

    /** The display name of {@code id}'s most recent death, or {@code null}. */
    public String mostRecentNameForPlayer(UUID id) {
        for (int i = history.size() - 1; i >= 0; i--) {
            DeathRecord r = history.get(i);
            if (r.uuid().equals(id)) {
                return r.name();
            }
        }
        return null;
    }

    public boolean hasAnyForPlayer(UUID id) {
        for (DeathRecord r : history) {
            if (r.uuid().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return history.isEmpty();
    }

    public int size() {
        return history.size();
    }

    // ---- writes -----------------------------------------------------------

    /** Append a death with no logged friends. {@code carriage} is the room it happened in (or {@code NO_CARRIAGE}). */
    public void append(UUID id, String name, int carriage, CompoundTag snapshot) {
        append(id, name, carriage, snapshot, List.of());
    }

    /**
     * Append a death to the log and persist, together with snapshots of the PlayerMobs that loved
     * this player at death ({@code friends}) for later friend-echo replay. All tags are
     * defensively copied so the caller can't mutate the stored record.
     */
    public void append(UUID id, String name, int carriage, CompoundTag snapshot, List<CompoundTag> friends) {
        history.add(new DeathRecord(nextId++, id, name, carriage, snapshot.copy(), copyAll(friends)));
        save();
    }

    private static List<CompoundTag> copyAll(List<CompoundTag> tags) {
        if (tags.isEmpty()) {
            return List.of();
        }
        List<CompoundTag> out = new ArrayList<>(tags.size());
        for (CompoundTag t : tags) {
            out.add(t.copy());
        }
        return out;
    }

    // ---- candidate sets (consumed by the reincarnation source registry) ---

    /**
     * Deaths within {@link #CARRIAGE_RADIUS} carriages of {@code carriage}, oldest→newest — the
     * depth band a Dungeon-Train echo is drawn from. Empty when {@code carriage} is
     * {@link #NO_CARRIAGE} (no depth to compare against). The weighted pick over these is the
     * registry's job; this is just the eligible set.
     */
    public List<DeathRecord> recordsInBand(int carriage) {
        if (carriage == NO_CARRIAGE) {
            return List.of();
        }
        return eligible(history, carriage, CARRIAGE_RADIUS);
    }

    /** Every death belonging to {@code id}, oldest→newest (a fresh list; records are shared). */
    public List<DeathRecord> recordsForPlayer(UUID id) {
        List<DeathRecord> out = new ArrayList<>();
        for (DeathRecord r : history) {
            if (r.uuid().equals(id)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Every recorded death, oldest→newest (a fresh list; records are shared). */
    public List<DeathRecord> allRecords() {
        return new ArrayList<>(history);
    }

    /** The newest {@code limit} deaths, newest first (a fresh list; records are shared). */
    public List<DeathRecord> recent(int limit) {
        List<DeathRecord> out = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(history.get(i));
        }
        return out;
    }

    // ---- pure helpers (unit-tested; no world / no filesystem) -------------

    /**
     * Deaths within {@code radius} carriages of {@code spawnCarriage} that have a known carriage,
     * preserving oldest→newest order (so the last element is the most recent). Just the depth-band
     * filter — the recency/proximity-weighted pick over the result lives in the registry.
     */
    static List<DeathRecord> eligible(List<DeathRecord> historyOldestFirst, int spawnCarriage, int radius) {
        List<DeathRecord> out = new ArrayList<>();
        for (DeathRecord r : historyOldestFirst) {
            if (r.carriage() == NO_CARRIAGE) {
                continue; // died off a train — no depth to compare
            }
            if (Math.abs((long) r.carriage() - spawnCarriage) > radius) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    // ---- persistence ------------------------------------------------------

    static void write(CompoundTag tag, List<DeathRecord> history, long nextId) {
        ListTag deaths = new ListTag();
        for (DeathRecord r : history) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(TAG_ID, r.id());
            NbtCompat.putUUID(entry, TAG_UUID, r.uuid());
            if (r.name() != null) {
                entry.putString(TAG_NAME, r.name());
            }
            entry.putInt(TAG_CARRIAGE, r.carriage());
            entry.put(TAG_SNAPSHOT, r.snapshot().copy());
            if (!r.friendSnapshots().isEmpty()) {
                ListTag friends = new ListTag();
                for (CompoundTag f : r.friendSnapshots()) {
                    friends.add(f.copy());
                }
                entry.put(TAG_FRIENDS, friends);
            }
            deaths.add(entry);
        }
        tag.put(TAG_DEATHS, deaths);
        tag.putLong(TAG_NEXT_ID, nextId);
    }

    /** Read the death log into {@code out} (cleared first); returns the next free id. */
    static long read(CompoundTag tag, List<DeathRecord> out) {
        out.clear();
        long maxId = 0L;
        long fallbackId = 1L;
        // Current format.
        ListTag deaths = NbtCompat.getListOfType(tag, TAG_DEATHS, Tag.TAG_COMPOUND);
        for (int i = 0; i < deaths.size(); i++) {
            CompoundTag entry = NbtCompat.compoundAt(deaths, i);
            if (!NbtCompat.hasUUID(entry, TAG_UUID)) {
                continue;
            }
            long id = NbtCompat.getLongOr(entry, TAG_ID, fallbackId);
            fallbackId = Math.max(fallbackId, id) + 1;
            maxId = Math.max(maxId, id);
            int carriage = NbtCompat.getIntOr(entry, TAG_CARRIAGE, NO_CARRIAGE);
            String name = NbtCompat.getStringOr(entry, TAG_NAME, null);
            out.add(new DeathRecord(id, NbtCompat.getUUID(entry, TAG_UUID), name, carriage,
                NbtCompat.getCompoundOrEmpty(entry, TAG_SNAPSHOT), readFriendList(entry)));
        }
        // Back-compat: the pre-death-log global format stored one snapshot per player under "Lives".
        if (out.isEmpty() && NbtCompat.containsOfType(tag, TAG_LEGACY_LIVES, Tag.TAG_LIST)) {
            ListTag lives = NbtCompat.getListOfType(tag, TAG_LEGACY_LIVES, Tag.TAG_COMPOUND);
            for (int i = 0; i < lives.size(); i++) {
                CompoundTag entry = NbtCompat.compoundAt(lives, i);
                if (!NbtCompat.hasUUID(entry, TAG_UUID)) {
                    continue;
                }
                long id = fallbackId++;
                maxId = Math.max(maxId, id);
                String name = NbtCompat.getStringOr(entry, TAG_NAME, null);
                out.add(new DeathRecord(id, NbtCompat.getUUID(entry, TAG_UUID), name, NO_CARRIAGE,
                    NbtCompat.getCompoundOrEmpty(entry, TAG_SNAPSHOT)));
            }
        }
        long storedNext = NbtCompat.getLongOr(tag, TAG_NEXT_ID, 0L);
        return Math.max(storedNext, maxId + 1);
    }

    /** Friend snapshots stored under {@code entry}, or empty — a missing key (every older record) reads as none. */
    private static List<CompoundTag> readFriendList(CompoundTag entry) {
        if (!NbtCompat.containsOfType(entry, TAG_FRIENDS, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = NbtCompat.getListOfType(entry, TAG_FRIENDS, Tag.TAG_COMPOUND);
        List<CompoundTag> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(NbtCompat.compoundAt(list, i));
        }
        return out;
    }

    private void load() {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            //? if >=1.21.1 {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            //?} else {
            /*CompoundTag tag = NbtIo.readCompressed(path.toFile());*/
            //?}
            if (tag != null) {
                nextId = read(tag, history);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[playermob] failed to load global lives from {}", path, e);
        }
    }

    private void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            CompoundTag tag = new CompoundTag();
            write(tag, history, nextId);
            //? if >=1.21.1 {
            NbtIo.writeCompressed(tag, path);
            //?} else {
            /*NbtIo.writeCompressed(tag, path.toFile());*/
            //?}
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[playermob] failed to save global lives to {}", path, e);
        }
    }

    /**
     * One-time import of legacy per-world {@code lastLife} data (from builds before this
     * store existed) into the log, with an unknown carriage. Drained from the overworld's
     * {@link PlayerLifeStore} so it never re-imports.
     */
    private void migrateFromWorld(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        Map<UUID, CompoundTag> legacyLife = new HashMap<>();
        Map<UUID, String> legacyName = new HashMap<>();
        PlayerLifeStore.get(overworld).drainLegacyLastLives(legacyLife, legacyName);
        if (legacyLife.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, CompoundTag> e : legacyLife.entrySet()) {
            history.add(new DeathRecord(nextId++, e.getKey(), legacyName.get(e.getKey()), NO_CARRIAGE, e.getValue()));
        }
        save();
        LOGGER.info("[playermob] imported {} legacy past life/lives into the global death log", legacyLife.size());
    }
}
