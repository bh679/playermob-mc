package games.brennan.playermob.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.UUID;

/**
 * Version-bridging accessors for the {@link CompoundTag} / {@link ListTag} reading
 * API, which was overhauled in MC 26.x.
 *
 * <p>Pre-26 the typed getters returned the value directly ({@code getString} →
 * {@code String}, {@code getCompound} → {@code CompoundTag}), there was a
 * two-argument {@code contains(name, typeId)} type-checked presence test, a
 * two-argument {@code getList(name, typeId)}, and dedicated {@code putUUID} /
 * {@code getUUID} / {@code hasUUID} helpers (an {@code int[4]} under the hood).</p>
 *
 * <p>In 26.x the typed getters return {@link java.util.Optional} (with new
 * {@code getXxxOr(name, default)} convenience variants), {@code contains} is
 * single-argument (no type filter), {@code getList} is {@code getListOrEmpty(name)},
 * {@code ListTag.getCompound(i)} returns {@code Optional} (with {@code getCompoundOrEmpty}),
 * and the UUID helpers were removed in favour of {@code UUIDUtil.CODEC}
 * (the same {@code int[4]} encoding, so it round-trips files written by the old
 * {@code putUUID}).</p>
 *
 * <p>Every method keeps the <em>pre-26 behaviour</em> exactly on 1.20.1 / 1.21.1 —
 * the original direct getters — and reproduces it on 26.x via the new API. The
 * type-id parameters on {@link #containsOfType} / {@link #getListOfType} are kept in
 * the signature so call sites read identically; on 26.x the type filter is dropped
 * (the new API doesn't expose it), which is safe for this mod's well-typed keys.</p>
 *
 * <p>Stateless utility — every method static.</p>
 */
public final class NbtCompat {

    private NbtCompat() {}

    /**
     * "Any numeric" NBT type id, for {@link #containsOfType} type filters. 26.x removed the
     * {@code Tag.TAG_ANY_NUMERIC} constant (its typed getters return {@code Optional} instead of
     * coercing), so call sites reference this version-safe constant; on 26.x the filter is unused.
     */
    //? if >=26 {
    /*public static final int ANY_NUMERIC = 99;
    *///?} else {
    public static final int ANY_NUMERIC = net.minecraft.nbt.Tag.TAG_ANY_NUMERIC;
    //?}

    // ---- UUID (int[4] encoding; back-compatible with pre-26 putUUID/getUUID) ----

    /** Store {@code value} under {@code key} as the canonical {@code int[4]} UUID encoding. */
    public static void putUUID(CompoundTag tag, String key, UUID value) {
        //? if >=26 {
        /*tag.store(key, net.minecraft.core.UUIDUtil.CODEC, value);
        *///?} else {
        tag.putUUID(key, value);
        //?}
    }

    /** True if {@code key} holds a UUID ({@code int[4]}). */
    public static boolean hasUUID(CompoundTag tag, String key) {
        //? if >=26 {
        /*return tag.read(key, net.minecraft.core.UUIDUtil.CODEC).isPresent();
        *///?} else {
        return tag.hasUUID(key);
        //?}
    }

    /** Read the UUID under {@code key}; caller must have checked {@link #hasUUID}. */
    public static UUID getUUID(CompoundTag tag, String key) {
        //? if >=26 {
        /*return tag.read(key, net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
        *///?} else {
        return tag.getUUID(key);
        //?}
    }

    // ---- typed presence / list / element ----------------------------------

    /**
     * Whether {@code key} is present (pre-26: present <em>and</em> of NBT type
     * {@code typeId}). On 26.x the type filter is unavailable, so this is a bare
     * presence test — fine for the mod's keys, which never collide on type.
     */
    public static boolean containsOfType(CompoundTag tag, String key, int typeId) {
        //? if >=26 {
        /*return tag.contains(key);
        *///?} else {
        return tag.contains(key, typeId);
        //?}
    }

    /** Bare presence test ({@code contains(name)} on every version). */
    public static boolean contains(CompoundTag tag, String key) {
        return tag.contains(key);
    }

    /** The list under {@code key}, or an empty list (pre-26 filtered to element {@code typeId}). */
    public static ListTag getListOfType(CompoundTag tag, String key, int typeId) {
        //? if >=26 {
        /*return tag.getListOrEmpty(key);
        *///?} else {
        return tag.getList(key, typeId);
        //?}
    }

    /** The compound element at index {@code i} of {@code list}, or an empty compound. */
    public static CompoundTag compoundAt(ListTag list, int i) {
        //? if >=26 {
        /*return list.getCompoundOrEmpty(i);
        *///?} else {
        return list.getCompound(i);
        //?}
    }

    // ---- scalar reads with default ----------------------------------------

    /** {@code key}'s string, or {@code def} if absent. */
    public static String getStringOr(CompoundTag tag, String key, String def) {
        //? if >=26 {
        /*return tag.getStringOr(key, def);
        *///?} else {
        return tag.contains(key, net.minecraft.nbt.Tag.TAG_STRING) ? tag.getString(key) : def;
        //?}
    }

    /** {@code key}'s int, or {@code def} if absent. */
    public static int getIntOr(CompoundTag tag, String key, int def) {
        //? if >=26 {
        /*return tag.getIntOr(key, def);
        *///?} else {
        return tag.contains(key, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) : def;
        //?}
    }

    /** {@code key}'s long, or {@code def} if absent. */
    public static long getLongOr(CompoundTag tag, String key, long def) {
        //? if >=26 {
        /*return tag.getLongOr(key, def);
        *///?} else {
        return tag.contains(key, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? tag.getLong(key) : def;
        //?}
    }

    /** {@code key}'s float, or {@code def} if absent. */
    public static float getFloatOr(CompoundTag tag, String key, float def) {
        //? if >=26 {
        /*return tag.getFloatOr(key, def);
        *///?} else {
        return tag.contains(key, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? tag.getFloat(key) : def;
        //?}
    }

    /** {@code key}'s boolean, or {@code def} if absent. */
    public static boolean getBooleanOr(CompoundTag tag, String key, boolean def) {
        //? if >=26 {
        /*return tag.getBooleanOr(key, def);
        *///?} else {
        // Pre-26 getBoolean already returns false for a missing key; honour an explicit default.
        return tag.contains(key) ? tag.getBoolean(key) : def;
        //?}
    }

    /** The compound under {@code key}, or an empty compound if absent. */
    public static CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        //? if >=26 {
        /*return tag.getCompoundOrEmpty(key);
        *///?} else {
        return tag.getCompound(key);
        //?}
    }
}
