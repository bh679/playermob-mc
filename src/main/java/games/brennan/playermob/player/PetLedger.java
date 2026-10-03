package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every animal one life tamed — the pool an echo of that life chooses its pets from, so a wolf left
 * at base or a cat lost earlier in the run is remembered, not only what stood beside the player
 * when they died.
 *
 * <p>Each {@link Entry} keeps the pet's last-seen snapshot (taken at tame, refreshed whenever the
 * animal leaves the world — unloaded, changed dimension, or died — and again at the owner's death
 * if it is still loaded). A pet that died stays in the ledger with {@code alive = false}: it can
 * still return with the echo, ranked behind the living.</p>
 *
 * <p>Immutable value type (mirrors {@link PlayerLifeRecord}): every write returns a fresh ledger.
 * Pure (no Minecraft world) so the bookkeeping is unit-tested — see {@code PetLedgerTest}.</p>
 */
public final class PetLedger {

    /**
     * Most pets one life remembers. Well above the three an echo brings back, so the choice among
     * them is real; bounded because each entry carries a full entity snapshot.
     */
    static final int CAP = 16;

    static final String TAG_PET = "Pet";
    static final String TAG_SNAPSHOT = "Snapshot";
    static final String TAG_NAMED = "Named";
    static final String TAG_MOUNT = "Mount";
    static final String TAG_ALIVE = "Alive";
    static final String TAG_LAST_SEEN = "LastSeen";

    /** A life that has tamed nothing yet. */
    public static final PetLedger EMPTY = new PetLedger(Map.of());

    /** One remembered pet: its last-seen snapshot and what the echo needs to rank it. */
    public record Entry(UUID pet, CompoundTag snapshot, boolean named, boolean mount, boolean alive,
                        long lastSeen) {

        /** This entry, marked as having died — the snapshot stays, so it can still return. */
        public Entry dead() {
            return new Entry(pet, snapshot, named, mount, false, lastSeen);
        }
    }

    /**
     * Which entry goes first when the ledger is full: unnamed before named (a name is the player
     * saying this one mattered), dead before alive, then the longest unseen.
     */
    private static final Comparator<Entry> EVICT_FIRST = Comparator
        .comparing(Entry::named)
        .thenComparing(Entry::alive)
        .thenComparingLong(Entry::lastSeen);

    private final Map<UUID, Entry> entries;

    private PetLedger(Map<UUID, Entry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** The entry for {@code pet}, or {@code null} if this life never tamed it. */
    public Entry get(UUID pet) {
        return entries.get(pet);
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    /** This ledger with {@code entry} added or replacing the pet's previous entry, trimmed to {@link #CAP}. */
    public PetLedger with(Entry entry) {
        Map<UUID, Entry> next = new LinkedHashMap<>(entries);
        next.put(entry.pet(), entry);
        while (next.size() > CAP) {
            Entry evict = next.values().stream().min(EVICT_FIRST).orElseThrow();
            next.remove(evict.pet());
        }
        return new PetLedger(next);
    }

    /** This ledger without {@code pet} — given away, or otherwise no longer this life's. */
    public PetLedger without(UUID pet) {
        if (!entries.containsKey(pet)) {
            return this;
        }
        Map<UUID, Entry> next = new LinkedHashMap<>(entries);
        next.remove(pet);
        return new PetLedger(next);
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            CompoundTag tag = new CompoundTag();
            NbtCompat.putUUID(tag, TAG_PET, e.pet());
            tag.put(TAG_SNAPSHOT, e.snapshot().copy());
            tag.putBoolean(TAG_NAMED, e.named());
            tag.putBoolean(TAG_MOUNT, e.mount());
            tag.putBoolean(TAG_ALIVE, e.alive());
            tag.putLong(TAG_LAST_SEEN, e.lastSeen());
            list.add(tag);
        }
        return list;
    }

    /** Read a ledger back; entries missing a pet UUID or snapshot are skipped. */
    public static PetLedger load(ListTag list) {
        List<Entry> loaded = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = NbtCompat.compoundAt(list, i);
            if (!NbtCompat.hasUUID(tag, TAG_PET)
                    || !NbtCompat.containsOfType(tag, TAG_SNAPSHOT, Tag.TAG_COMPOUND)) {
                continue;
            }
            loaded.add(new Entry(
                NbtCompat.getUUID(tag, TAG_PET),
                NbtCompat.getCompoundOrEmpty(tag, TAG_SNAPSHOT),
                NbtCompat.getBooleanOr(tag, TAG_NAMED, false),
                NbtCompat.getBooleanOr(tag, TAG_MOUNT, false),
                NbtCompat.getBooleanOr(tag, TAG_ALIVE, true),
                NbtCompat.getLongOr(tag, TAG_LAST_SEEN, 0L)));
        }
        PetLedger ledger = EMPTY;
        for (Entry e : loaded) {
            ledger = ledger.with(e);
        }
        return ledger;
    }
}
