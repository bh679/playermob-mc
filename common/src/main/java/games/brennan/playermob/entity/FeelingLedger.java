package games.brennan.playermob.entity;

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
 * A {@link PlayerMobEntity}'s evolving relationships — one {@link FeelingRecord}
 * per individual player and other PlayerMob it has <b>met</b>. The headline value
 * is a <b>feeling</b> in {@code [0, 10]} ({@link #DEFAULT 5} neutral, 0 hate, 10
 * love) that drives how the mob treats that individual (see
 * {@link DispositionResolver}); the record also carries the per-individual caps
 * the Phase B feeling-events need.
 *
 * <p>Keyed by entity UUID (stable across save/load). Phase A persisted only
 * non-neutral entries; <b>Phase B persists every entry</b> because an entry now
 * means "met" — the menu's relationship roster lists everyone the mob has seen.
 * To stop a long-lived mob's ledger growing without bound, only the
 * {@link #MAX_ENTRIES} most-interesting entries (highest {@link FeelingRecord#magnitude()})
 * are kept; bare "just met" entries are pruned first. Extracted from the entity so
 * it's unit-testable without a live world.</p>
 *
 * <p>The container mutates (entries are added / replaced); the {@link FeelingRecord}
 * values are immutable, so each event swaps in a fresh record.</p>
 */
public final class FeelingLedger {

    public static final String TAG_FEELINGS = "Feelings";
    static final String TAG_UUID = "UUID";
    static final String TAG_FEELING = "Feeling";
    static final String TAG_CROUCH_USED = "CrouchUsed";
    static final String TAG_CROUCH_CAP = "CrouchCap";
    static final String TAG_DEFEND_COUNT = "DefendCount";
    static final String TAG_LAST_CARRIAGE = "LastCarriage";
    // NB: lastWitnessTick is deliberately NOT persisted — it's session-scoped (see FeelingRecord).

    public static final float DEFAULT = FeelingRecord.DEFAULT;
    public static final float MIN = FeelingRecord.MIN;
    public static final float MAX = FeelingRecord.MAX;

    /** Cap on persisted/encoded entries; lowest-{@link FeelingRecord#magnitude()} pruned first. */
    static final int MAX_ENTRIES = 32;

    /** Feeling penalty when the mob witnesses someone harm an individual it loves. */
    static final float HARM_PENALTY = 1.0F;

    private final Map<UUID, FeelingRecord> feelings = new HashMap<>();

    /** The mob's feeling toward {@code id}, or {@link #DEFAULT} if it has none yet. */
    public float feelingToward(UUID id) {
        FeelingRecord record = feelings.get(id);
        return record == null ? DEFAULT : record.feeling();
    }

    /** The full record for {@code id}, or {@link FeelingRecord#NEUTRAL} if absent. */
    public FeelingRecord recordFor(UUID id) {
        FeelingRecord record = feelings.get(id);
        return record == null ? FeelingRecord.NEUTRAL : record;
    }

    public boolean has(UUID id) {
        return feelings.containsKey(id);
    }

    public int size() {
        return feelings.size();
    }

    /**
     * Note that the mob has met (seen) {@code id} — adds a neutral entry if it has
     * none yet. Returns {@code true} only when a new entry was created (so the
     * caller can sync the roster).
     */
    public boolean encounter(UUID id) {
        if (feelings.containsKey(id)) {
            return false;
        }
        put(id, FeelingRecord.NEUTRAL);
        return true;
    }

    /** Shift the feeling toward {@code id} by {@code delta} (gift +, harm −), clamped. */
    public void adjust(UUID id, float delta) {
        put(id, recordFor(id).adjusted(delta));
    }

    /**
     * Apply attack damage as a feeling drop ({@code delta} negative). Distinct from
     * {@link #adjust} because a ≥1 loss re-opens crouch headroom (see
     * {@link FeelingRecord#afterAttack}).
     */
    public void recordAttack(UUID id, float delta) {
        put(id, recordFor(id).afterAttack(delta));
    }

    /** Set the feeling toward {@code id} to an absolute value (clamped), keeping other state. */
    public void set(UUID id, float value) {
        put(id, recordFor(id).withFeeling(value));
    }

    /** One debounced crouch toward {@code id}. Returns {@code true} if feeling changed. */
    public boolean crouch(UUID id) {
        FeelingRecord before = recordFor(id);
        FeelingRecord after = before.afterCrouch();
        if (after == before) {
            return false;
        }
        put(id, after);
        return true;
    }

    /**
     * Credit {@code id} for defending the mob at game tick {@code eventTick}.
     * Debounced per-individual on {@code eventTick} so one rescue isn't counted
     * every poll. Returns {@code true} if feeling changed.
     */
    public boolean defend(UUID id, int eventTick) {
        FeelingRecord before = recordFor(id);
        if (eventTick <= before.lastWitnessTick()) {
            return false;
        }
        FeelingRecord credited = before.afterDefend();
        put(id, credited.withWitnessTick(eventTick));
        return credited.feeling() != before.feeling();
    }

    /**
     * Lower the feeling toward {@code id} for harming someone the mob loves, at game
     * tick {@code eventTick} (debounced per-individual). Returns {@code true} if
     * feeling changed.
     */
    public boolean harm(UUID id, int eventTick) {
        FeelingRecord before = recordFor(id);
        if (eventTick <= before.lastWitnessTick()) {
            return false;
        }
        FeelingRecord credited = before.adjusted(-HARM_PENALTY);
        put(id, credited.withWitnessTick(eventTick));
        return credited.feeling() != before.feeling();
    }

    /**
     * Credit travelling together to {@code newIndex}. The first poll on a train only
     * records position; later carriage changes add feeling. Returns {@code true} if
     * feeling changed (the position-only first poll returns {@code false}).
     */
    public boolean travel(UUID id, int newIndex) {
        FeelingRecord before = recordFor(id);
        FeelingRecord after = before.afterCarriageAdvance(newIndex);
        if (after == before) {
            return false;
        }
        put(id, after);
        return after.feeling() != before.feeling();
    }

    /**
     * Compact {@code "uuid=feeling;uuid=feeling"} encoding of <b>all</b> entries, for
     * syncing the roster to the client menu via a synced String. The menu only needs
     * each feeling; the richer record fields are server-side.
     */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, FeelingRecord> e : feelings.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue().feeling());
        }
        return sb.toString();
    }

    /**
     * Parse an {@link #encode()} string back into a map (clamped). Empty input
     * yields an empty map; malformed tokens are skipped rather than throwing, so a
     * corrupt / forward-version payload degrades gracefully.
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
                map.put(id, FeelingRecord.clamp(Float.parseFloat(token.substring(eq + 1))));
            } catch (IllegalArgumentException ignored) {
                // skip malformed token
            }
        }
        return map;
    }

    /**
     * Write one compound per entry. The record fields are additive: only non-default
     * sub-fields are written, so a plain "met" entry round-trips as just
     * {@code {UUID, Feeling}} (and a Phase A save reads back unchanged).
     */
    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, FeelingRecord> e : feelings.entrySet()) {
            FeelingRecord r = e.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.putFloat(TAG_FEELING, r.feeling());
            if (r.crouchBudgetUsed() != 0.0F) {
                entry.putFloat(TAG_CROUCH_USED, r.crouchBudgetUsed());
            }
            if (r.crouchCap() != FeelingRecord.CROUCH_CAP_BASE) {
                entry.putFloat(TAG_CROUCH_CAP, r.crouchCap());
            }
            if (r.defendCount() != 0) {
                entry.putInt(TAG_DEFEND_COUNT, r.defendCount());
            }
            if (r.lastCarriageIndex() != FeelingRecord.NO_CARRIAGE) {
                entry.putInt(TAG_LAST_CARRIAGE, r.lastCarriageIndex());
            }
            // lastWitnessTick is session-scoped (combat ticks reset on reload) — not persisted.
            list.add(entry);
        }
        tag.put(TAG_FEELINGS, list);
    }

    /**
     * Read each entry. Missing record sub-keys default safely — crucially
     * {@code CrouchCap} defaults to {@link FeelingRecord#CROUCH_CAP_BASE} (not 0,
     * which would dead-lock crouching) and {@code LastCarriage} to
     * {@link FeelingRecord#NO_CARRIAGE} — so a Phase A {@code {UUID, Feeling}} save
     * loads as a working neutral record.
     */
    public void load(CompoundTag tag) {
        feelings.clear();
        if (tag.contains(TAG_FEELINGS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_FEELINGS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID(TAG_UUID)) {
                    continue;
                }
                float feeling = FeelingRecord.clamp(entry.getFloat(TAG_FEELING));
                float crouchUsed = entry.contains(TAG_CROUCH_USED)
                    ? entry.getFloat(TAG_CROUCH_USED) : 0.0F;
                float crouchCap = entry.contains(TAG_CROUCH_CAP)
                    ? entry.getFloat(TAG_CROUCH_CAP) : FeelingRecord.CROUCH_CAP_BASE;
                int defendCount = entry.contains(TAG_DEFEND_COUNT)
                    ? entry.getInt(TAG_DEFEND_COUNT) : 0;
                int lastCarriage = entry.contains(TAG_LAST_CARRIAGE)
                    ? entry.getInt(TAG_LAST_CARRIAGE) : FeelingRecord.NO_CARRIAGE;
                // lastWitnessTick resets to 0 on load — session-scoped debounce, not persisted.
                feelings.put(entry.getUUID(TAG_UUID), new FeelingRecord(
                    feeling, crouchUsed, crouchCap, defendCount, lastCarriage, 0));
            }
        }
        prune();
    }

    /** Insert / replace an entry, then enforce the {@link #MAX_ENTRIES} cap. */
    private void put(UUID id, FeelingRecord record) {
        feelings.put(id, record);
        prune();
    }

    /** Trim to the {@link #MAX_ENTRIES} entries with the greatest magnitude. */
    private void prune() {
        if (feelings.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<UUID, FeelingRecord>> entries = new ArrayList<>(feelings.entrySet());
        entries.sort(Comparator.comparingDouble(
            (Map.Entry<UUID, FeelingRecord> e) -> e.getValue().magnitude()).reversed());
        for (int i = MAX_ENTRIES; i < entries.size(); i++) {
            feelings.remove(entries.get(i).getKey());
        }
    }
}
