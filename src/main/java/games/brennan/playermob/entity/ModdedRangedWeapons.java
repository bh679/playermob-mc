package games.brennan.playermob.entity;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * An admin-configured registry of <b>modded ranged weapons</b> — firearms from other mods (MusketMod's
 * {@code musketmod:musket}, gun-mod rifles, etc.) that a {@link PlayerMobEntity} should treat as ranged
 * weapons even though they're not vanilla {@code BowItem}/{@code CrossbowItem}s. It answers the three
 * questions the weapon-aware combat code needs: <em>is this item one of them?</em>, <em>does this stack
 * count as its ammo?</em>, and <em>how long should the mob hold to load/aim it?</em>
 *
 * <p>The config-driven companion to the hardcoded vanilla {@code instanceof} checks in
 * {@link ItemPickupPolicy#weaponCategory} / {@link RangedAmmo}, exactly as {@link WantedItemList} is the
 * config companion to the built-in pickup categories. Firing itself is not this class's job — the mob drives
 * the modded item's own {@code use}/{@code onUseTick} logic through a fake player (see
 * {@code ModdedRangedAttackGoal}), so each mod fires its real projectile and consumes its real ammo.</p>
 *
 * <p>Parsed once from the {@code moddedRangedWeapons} config line (see {@code PlayerMobConfig}). Entries are
 * comma-separated; each entry binds a <b>weapon</b> matcher to its <b>ammo</b> matcher with {@code >}, and may
 * append an optional hold-ticks override:</p>
 *
 * <pre>{@code
 * moddedRangedWeapons=musketmod:musket>musketmod:cartridge, musketmod:pistol>musketmod:cartridge>25
 * }</pre>
 *
 * <p>Each side of a {@code >} is itself a {@link WantedItemList} token list — an item id or a {@code #}-prefixed
 * item tag (a bare path defaults to {@code minecraft}), so a side may name several ids/tags separated by spaces.
 * A malformed entry (missing the ammo half, or an all-junk side) is skipped rather than failing the whole list —
 * config never breaks mod init. Immutable after {@link #parse}.</p>
 */
public final class ModdedRangedWeapons {

    /** The do-nothing registry — matches no weapon. The default when {@code moddedRangedWeapons} is blank. */
    public static final ModdedRangedWeapons EMPTY = new ModdedRangedWeapons(List.of());

    /**
     * Reload time in ticks a mob spends loading a modded firearm before each shot when an entry omits an explicit
     * override. The mob "uses" the weapon for this long so the mod runs its real staged reload (animation +
     * sounds); it must be at least the weapon's own reload duration or the shot won't be loaded in time. 45t
     * (~2.25s) covers a default MusketMod musket (3 stages × 10t, ~40t for a mob). Tune per weapon with the
     * optional third config field.
     */
    public static final int DEFAULT_HOLD_TICKS = 45;

    private static final int MIN_HOLD_TICKS = 5;
    private static final int MAX_HOLD_TICKS = 200;

    /** One weapon→ammo binding: which items are this firearm, what ammo it fires, and its load/aim hold. */
    private record Binding(WantedItemList weapon, WantedItemList ammo, int holdTicks) {}

    private final List<Binding> bindings;

    private ModdedRangedWeapons(List<Binding> bindings) {
        this.bindings = bindings;
    }

    /**
     * Parse a config line into a registry. {@code null} / blank yields {@link #EMPTY}. Entries split on commas;
     * each entry splits on {@code >} into {@code weapon>ammo[>holdTicks]}. Entries missing the ammo half, with an
     * unparseable side, or with no valid id/tag on either side are dropped (fail-safe — never throws).
     */
    public static ModdedRangedWeapons parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return EMPTY;
        }
        List<Binding> out = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(">");
            if (parts.length < 2) {
                continue; // need both a weapon and its ammo
            }
            WantedItemList weapon = WantedItemList.parse(parts[0]);
            WantedItemList ammo = WantedItemList.parse(parts[1]);
            if (weapon.isEmpty() || ammo.isEmpty()) {
                continue; // all-junk side — skip this entry, keep the rest
            }
            int hold = DEFAULT_HOLD_TICKS;
            if (parts.length >= 3) {
                hold = clampHold(parseInt(parts[2], DEFAULT_HOLD_TICKS));
            }
            out.add(new Binding(weapon, ammo, hold));
        }
        return out.isEmpty() ? EMPTY : new ModdedRangedWeapons(List.copyOf(out));
    }

    /** True if {@code stack} is a configured modded ranged weapon (matches any binding's weapon side). */
    public boolean isRangedWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Binding b : bindings) {
            if (b.weapon.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code candidate} is ammo for <em>any</em> configured modded weapon (weapon-agnostic). Used so a
     * PlayerMob hoards modded cartridges/magazines off the floor and out of chests the way it hoards arrows —
     * otherwise it could draw a firearm it can never feed.
     */
    public boolean isModdedAmmo(ItemStack candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (Binding b : bindings) {
            if (b.ammo.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code candidate} is ammo the given modded {@code weapon} accepts (per its binding). */
    public boolean ammoMatches(ItemStack weapon, ItemStack candidate) {
        if (weapon.isEmpty() || candidate.isEmpty()) {
            return false;
        }
        for (Binding b : bindings) {
            if (b.weapon.matches(weapon) && b.ammo.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The load/aim hold in ticks for {@code weapon} — its binding's override or {@link #DEFAULT_HOLD_TICKS}.
     * Returns the default for a stack that isn't a configured modded weapon.
     */
    public int holdTicks(ItemStack weapon) {
        for (Binding b : bindings) {
            if (b.weapon.matches(weapon)) {
                return b.holdTicks;
            }
        }
        return DEFAULT_HOLD_TICKS;
    }

    /** True when no binding is configured (i.e. {@link #EMPTY} or an all-junk line). */
    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    /** Number of parsed bindings — exposed for unit tests. */
    int size() {
        return bindings.size();
    }

    /** Parse an int, falling back to {@code fallback} on any malformed value (fail-safe). */
    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampHold(int ticks) {
        if (ticks < MIN_HOLD_TICKS) return MIN_HOLD_TICKS;
        if (ticks > MAX_HOLD_TICKS) return MAX_HOLD_TICKS;
        return ticks;
    }
}
