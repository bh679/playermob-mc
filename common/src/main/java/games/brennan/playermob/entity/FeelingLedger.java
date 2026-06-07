package games.brennan.playermob.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link PlayerMobEntity}'s evolving <b>feeling</b> toward each individual
 * player and other PlayerMob it has interacted with — a scale of {@code [0, 10]}
 * where {@code 0} is hate, {@link #DEFAULT 5} is neutral (the value for anyone
 * not yet in the ledger), and {@code 10} is love. Feelings drive how the mob
 * treats that specific individual (see {@link DispositionResolver}).
 *
 * <p>Keyed by entity UUID, which is stable across save/load for both players and
 * mobs. Only entries that differ from {@link #DEFAULT} are persisted, so the
 * ledger never bloats with an entry for every player who merely walked past.
 * Extracted from the entity so it's unit-testable without a live world.</p>
 *
 * <p>Phase A only ever <em>decreases</em> feelings (on being attacked). Later
 * phases add positive events and may promote the stored value to a richer
 * per-individual record (crouch budget, defend count) — an additive NBT change.</p>
 */
public final class FeelingLedger {

    public static final String TAG_FEELINGS = "Feelings";
    static final String TAG_UUID = "UUID";
    static final String TAG_FEELING = "Feeling";

    public static final float DEFAULT = 5.0F;
    public static final float MIN = 0.0F;
    public static final float MAX = 10.0F;

    private final Map<UUID, Float> feelings = new HashMap<>();

    /** The mob's feeling toward {@code id}, or {@link #DEFAULT} if it has none yet. */
    public float feelingToward(UUID id) {
        Float value = feelings.get(id);
        return value == null ? DEFAULT : value;
    }

    /** Shift the feeling toward {@code id} by {@code delta}, clamped to [0, 10]. */
    public void adjust(UUID id, float delta) {
        set(id, feelingToward(id) + delta);
    }

    /** Set the feeling toward {@code id} to an absolute value, clamped to [0, 10]. */
    public void set(UUID id, float value) {
        feelings.put(id, clamp(value));
    }

    public boolean has(UUID id) {
        return feelings.containsKey(id);
    }

    public int size() {
        return feelings.size();
    }

    /**
     * Compact {@code "uuid=feeling;uuid=feeling"} encoding of the non-default
     * entries, for syncing to the client UI via a synced String. Neutral entries
     * are skipped (they round-trip to {@link #DEFAULT} anyway).
     */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Float> e : feelings.entrySet()) {
            if (e.getValue() == DEFAULT) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Parse an {@link #encode()} string back into a map (clamped). Empty input
     * yields an empty map; malformed tokens are skipped rather than throwing, so
     * a corrupt/forward-version payload degrades gracefully.
     */
    public static Map<UUID, Float> decode(String encoded) {
        Map<UUID, Float> map = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return map;
        }
        for (String token : encoded.split(";")) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                UUID id = UUID.fromString(token.substring(0, eq));
                map.put(id, clamp(Float.parseFloat(token.substring(eq + 1))));
            } catch (IllegalArgumentException ignored) {
                // skip malformed token
            }
        }
        return map;
    }

    /** Write one {@code {UUID, Feeling}} compound per non-default entry. */
    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Float> e : feelings.entrySet()) {
            if (e.getValue() == DEFAULT) {
                continue; // a neutral entry round-trips to DEFAULT anyway — don't persist it
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.putFloat(TAG_FEELING, e.getValue());
            list.add(entry);
        }
        tag.put(TAG_FEELINGS, list);
    }

    public void load(CompoundTag tag) {
        feelings.clear();
        if (tag.contains(TAG_FEELINGS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_FEELINGS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID(TAG_UUID)) {
                    feelings.put(entry.getUUID(TAG_UUID), clamp(entry.getFloat(TAG_FEELING)));
                }
            }
        }
    }

    static float clamp(float value) {
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }
}
