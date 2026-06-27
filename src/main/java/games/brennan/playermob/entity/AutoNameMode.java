package games.brennan.playermob.entity;

import java.util.Locale;

/**
 * Which spawns get an automatic skin-source nameplate (see {@code SkinDisplayName} and
 * {@link games.brennan.playermob.PlayerMobConfig#autoNameMode()}). Configured via {@code autoNameMode}
 * and toggled live with {@code /playermob autoname <off|natural|egg|all>}.
 *
 * <p>A spawn is classified into a {@link Category} from its spawn-reason name — version-agnostic because
 * both {@code MobSpawnType} (≤1.21.1) and {@code EntitySpawnReason} (≥26) share the same constant names,
 * read via {@code reason.name()}. The mode then decides whether that category is named:</p>
 * <ul>
 *   <li>{@link #OFF} — never (default).</li>
 *   <li>{@link #NATURAL} — only {@link Category#NATURAL} (wild / chunk-generation spawns).</li>
 *   <li>{@link #EGG} — only {@link Category#EGG} (spawn eggs, mob spawners, {@code /summon}, dispensers).</li>
 *   <li>{@link #ALL} — every spawn <em>except</em> {@link Category#EVENT}: Dungeon-Train spawns keep their
 *       own naming (echoes already read "Echo of …") and are never relabelled.</li>
 * </ul>
 *
 * <p>Pure (no Minecraft types) so the classification and coverage rules are unit-tested directly.</p>
 */
public enum AutoNameMode {

    OFF, NATURAL, EGG, ALL;

    /** Coarse spawn class derived from the raw spawn-reason name. */
    public enum Category { NATURAL, EGG, EVENT, OTHER }

    /** Parse a config / command value (case-insensitive), falling back to {@code fallback} on null/unknown. */
    public static AutoNameMode fromString(String raw, AutoNameMode fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "natural" -> NATURAL;
            case "egg" -> EGG;
            case "all" -> ALL;
            default -> fallback;
        };
    }

    /**
     * Classify a spawn by its reason-enum constant name (e.g. {@code "SPAWN_EGG"}). Unknown / future
     * names fall into {@link Category#OTHER}, so {@link #ALL} still covers them while the narrow modes
     * don't.
     */
    public static Category categorize(String reasonName) {
        if (reasonName == null) {
            return Category.OTHER;
        }
        return switch (reasonName) {
            case "NATURAL", "CHUNK_GENERATION" -> Category.NATURAL;
            case "SPAWN_EGG", "SPAWNER", "COMMAND", "DISPENSER" -> Category.EGG;
            case "EVENT" -> Category.EVENT;
            default -> Category.OTHER;
        };
    }

    /** Whether this mode auto-names a spawn of the given category. */
    public boolean covers(Category category) {
        return switch (this) {
            case OFF -> false;
            case NATURAL -> category == Category.NATURAL;
            case EGG -> category == Category.EGG;
            case ALL -> category != Category.EVENT;
        };
    }

    /** The lower-case token used in the config file and command (e.g. {@code "natural"}). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
