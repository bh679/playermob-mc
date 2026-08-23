package games.brennan.playermob.entity;

import java.util.Locale;

/**
 * Whether a PlayerMob may scavenge — one value per scavenging behaviour (chest/barrel raiding,
 * armor-stand stripping, floor-item collecting). Configured via {@code searchContainers},
 * {@code searchArmorStands} and {@code collectFloorItems} (see
 * {@link games.brennan.playermob.PlayerMobConfig}) and toggled live with
 * {@code /playermob <setting> <enabled|disabled|onlynaturallyspawning>}.
 *
 * <ul>
 *   <li>{@link #ENABLED} — every PlayerMob scavenges (default; the behaviour before this setting
 *       existed).</li>
 *   <li>{@link #DISABLED} — no PlayerMob ever does.</li>
 *   <li>{@link #ONLY_NATURALLY_SPAWNING} — only mobs that arrived on their own: wild / chunk-generation
 *       / mob-spawner spawns and Dungeon-Train events. Mobs a player deliberately placed (spawn egg,
 *       {@code /summon}, dispenser) leave storage alone.</li>
 * </ul>
 *
 * <p>A spawn's origin is classified from its spawn-reason name — version-agnostic because both
 * {@code MobSpawnType} (≤1.21.1) and {@code EntitySpawnReason} (≥26) share the same constant names,
 * read via {@code reason.name()}. Same trick as {@link AutoNameMode#categorize}.</p>
 *
 * <p>Pure (no Minecraft types) so the parsing and the origin rules are unit-tested directly — see
 * {@code ScavengeModeTest}.</p>
 */
public enum ScavengeMode {

    ENABLED, DISABLED, ONLY_NATURALLY_SPAWNING;

    /**
     * Parse a config / command value (case-insensitive), falling back to {@code fallback} on
     * null / blank / unknown. {@code onlynatural} and {@code natural} are accepted as shorthands
     * for {@link #ONLY_NATURALLY_SPAWNING}; {@code true}/{@code on} and {@code false}/{@code off}
     * map to {@link #ENABLED} / {@link #DISABLED} so a boolean-looking value still does the
     * obvious thing.
     */
    public static ScavengeMode fromString(String raw, ScavengeMode fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "enabled", "enable", "true", "on" -> ENABLED;
            case "disabled", "disable", "false", "off" -> DISABLED;
            case "onlynaturallyspawning", "onlynatural", "natural" -> ONLY_NATURALLY_SPAWNING;
            default -> fallback;
        };
    }

    /** The lower-case token used in the config file and command (e.g. {@code "onlynaturallyspawning"}). */
    public String token() {
        return name().toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * Whether a mob of this origin may scavenge under this mode.
     *
     * @param naturalOrigin the mob's recorded origin — see {@link #isNaturalOrigin(String)} and
     *                      {@code PlayerMobEntity#isNaturallySpawned()}
     */
    public boolean allows(boolean naturalOrigin) {
        return switch (this) {
            case ENABLED -> true;
            case DISABLED -> false;
            case ONLY_NATURALLY_SPAWNING -> naturalOrigin;
        };
    }

    /**
     * Classify a spawn as "arrived on its own" from its spawn-reason enum-constant name (e.g.
     * {@code "SPAWN_EGG"}). Only the reasons a player directly drives — spawn egg, {@code /summon},
     * dispenser — count as player-made; everything else (including unknown / future reasons) counts
     * as natural, so the narrow mode never silently disables scavenging for a spawn path this
     * doesn't know about.
     */
    public static boolean isNaturalOrigin(String reasonName) {
        if (reasonName == null) {
            return true;
        }
        return switch (reasonName) {
            case "SPAWN_EGG", "COMMAND", "DISPENSER" -> false;
            default -> true;
        };
    }
}
