package games.brennan.playermob.entity;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tally of the <b>conjured</b> flowers a {@link PlayerMobEntity} has handed to each
 * individual. {@link PlayerMobEntity#trinketGift(net.minecraft.world.entity.LivingEntity)}
 * makes a poppy or dandelion out of nothing when the mob has an empty pack and
 * there's nothing nearby to fetch — without a cap, a mob that never finds anything
 * to give buries one player in an endless flower pile.
 *
 * <p>Only conjured flowers are counted here. Items the mob picked up, looted, or was
 * gifted pass through {@code selectGiftFromInventory} / the fetch path and are never
 * recorded — a mob can keep giving real presents forever.</p>
 *
 * <p>Keyed by entity UUID (stable across save/load), like {@link FeelingLedger}. The
 * map is capped at {@link #MAX_ENTRIES}; the <b>lowest</b> counts are dropped first,
 * so a forgotten stranger's tally is what gets recycled, never a recipient who is
 * already at or near their limit. Extracted from the entity so it's unit-testable
 * without a live world.</p>
 */
public final class TrinketLedger {

    public static final String TAG_TRINKETS = "TrinketGifts";
    static final String TAG_UUID = "UUID";
    static final String TAG_COUNT = "Count";

    /** Cap on persisted entries; lowest counts pruned first. */
    static final int MAX_ENTRIES = 32;

    private final Map<UUID, Integer> given = new HashMap<>();

    /** How many conjured flowers {@code id} has already received. */
    public int countFor(UUID id) {
        return given.getOrDefault(id, 0);
    }

    /**
     * Whether another conjured flower is allowed for {@code id} under {@code limit}.
     * A negative limit means unlimited; {@code 0} means never conjure.
     */
    public boolean canGive(UUID id, int limit) {
        return limit < 0 || countFor(id) < limit;
    }

    /** Record one conjured flower handed to {@code id}. */
    public void record(UUID id) {
        given.put(id, countFor(id) + 1);
        prune();
    }

    /** Write one {@code {UUID, Count}} compound per recipient. */
    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Integer> e : given.entrySet()) {
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUUID(entry, TAG_UUID, e.getKey());
            entry.putInt(TAG_COUNT, e.getValue());
            list.add(entry);
        }
        tag.put(TAG_TRINKETS, list);
    }

    /**
     * Read the tallies back. A missing key (any save written before this feature
     * existed) simply loads as "nobody has been given anything yet".
     */
    public void load(CompoundTag tag) {
        given.clear();
        if (NbtCompat.containsOfType(tag, TAG_TRINKETS, Tag.TAG_LIST)) {
            ListTag list = NbtCompat.getListOfType(tag, TAG_TRINKETS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = NbtCompat.compoundAt(list, i);
                if (!NbtCompat.hasUUID(entry, TAG_UUID)) {
                    continue;
                }
                int count = NbtCompat.getIntOr(entry, TAG_COUNT, 0);
                if (count > 0) {
                    given.put(NbtCompat.getUUID(entry, TAG_UUID), count);
                }
            }
        }
        prune();
    }

    /** Trim to the {@link #MAX_ENTRIES} highest counts — the closest to their cap. */
    private void prune() {
        if (given.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(given.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<UUID, Integer> e) -> e.getValue()).reversed());
        for (int i = MAX_ENTRIES; i < entries.size(); i++) {
            given.remove(entries.get(i).getKey());
        }
    }
}
