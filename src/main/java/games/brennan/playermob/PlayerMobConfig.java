package games.brennan.playermob;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The mod's lone config file — {@code config/playermob.properties}, loaded once at startup from
 * {@link PlayerMob#init(Path)} with the loader-provided config directory. Holds the few spawn
 * tunables worth adjusting server-side; everything else stays a code constant (the mod has no
 * other config).
 *
 * <p>Reads are lock-free: {@link #load} writes the {@code volatile} fields once on the init thread,
 * and the server thread reads them during spawns. A missing file is created with commented defaults;
 * any read/parse failure logs and falls back to defaults — config never breaks mod init.</p>
 */
public final class PlayerMobConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "playermob.properties";

    private static final String KEY_ECHO_FRIEND_CHANCE = "echoFriendChance";
    private static final String KEY_DEBUG_SPAWN_LOG = "debugSpawnLog";
    private static final String KEY_TRAIN_DIG_THROUGH = "trainDigThrough";
    private static final String KEY_TRAIN_FOLLOW_LOVED_PLAYER = "trainFollowLovedPlayer";
    private static final String KEY_NATURAL_SPAWN_ENABLED = "naturalSpawnEnabled";
    private static final String KEY_NATURAL_SPAWN_DEFAULT_SCALE = "naturalSpawnDefaultScale";
    /** Prefix for per-mob replacement-chance keys, e.g. {@code naturalSpawnScale.minecraft:zombie}. */
    private static final String NATURAL_SPAWN_SCALE_PREFIX = "naturalSpawnScale.";
    private static final String KEY_VILLAGE_COMPANION_CHANCE = "villageCompanionChance";

    /** Default chance a Dungeon-Train echo with logged friends also brings back a friend-echo. */
    public static final float DEFAULT_ECHO_FRIEND_CHANCE = 0.40F;
    /** Debug spawn logging ships off — when on it broadcasts a chat line on every DT auto-spawn. */
    public static final boolean DEFAULT_DEBUG_SPAWN_LOG = false;
    /** PlayerMobs dig through fill (ice/dirt/mud/moss/logs) blocking a Dungeon-Train carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_DIG_THROUGH = true;
    /** On a Dungeon Train, a PlayerMob that loves a player aboard heads to that player's carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER = true;
    /** Natural spawning ships OFF — PlayerMobs only appear via egg, {@code /summon}, or Dungeon Train until enabled. */
    public static final boolean DEFAULT_NATURAL_SPAWN_ENABLED = false;
    /**
     * When natural spawning is on, the chance a PlayerMob replaces a listed <em>land</em> mob lacking an
     * explicit override. Water mobs ({@link #WATER_SPAWN_MOBS}) default to {@code 0} instead — a
     * player-shaped pillager standing in for a fish/squid underwater is rarely wanted.
     */
    public static final float DEFAULT_NATURAL_SPAWN_SCALE = 0.8F;
    /**
     * When natural spawning is on, the chance each villager generated with a village also spawns a
     * PlayerMob <em>beside</em> it (additive — the villager is not replaced). {@code 0} disables it.
     */
    public static final float DEFAULT_VILLAGE_COMPANION_CHANCE = 0.25F;

    /**
     * The vanilla mobs that spawn naturally — each gets a generated {@code naturalSpawnScale.<id>} line in
     * the default config and, when natural spawning is on, may be replaced by a PlayerMob at
     * {@link #DEFAULT_NATURAL_SPAWN_SCALE} unless overridden. Ids absent from a given MC version (e.g.
     * {@code bogged} pre-1.21) simply never match a real spawn, so listing them is harmless across versions.
     * Order is preserved for the generated config block (grouped: overworld hostile, nether/end, passive).
     */
    public static final List<String> NATURAL_SPAWN_MOBS = List.of(
        // Overworld hostile
        "minecraft:zombie", "minecraft:zombie_villager", "minecraft:husk", "minecraft:drowned",
        "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:creeper",
        "minecraft:spider", "minecraft:cave_spider", "minecraft:witch", "minecraft:slime",
        "minecraft:enderman", "minecraft:endermite", "minecraft:silverfish", "minecraft:phantom",
        "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:ravager",
        "minecraft:guardian",
        // Nether / End
        "minecraft:blaze", "minecraft:ghast", "minecraft:magma_cube", "minecraft:wither_skeleton",
        "minecraft:piglin", "minecraft:piglin_brute", "minecraft:hoglin", "minecraft:zombified_piglin",
        "minecraft:strider",
        // Passive / ambient
        "minecraft:cow", "minecraft:mooshroom", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
        "minecraft:rabbit", "minecraft:horse", "minecraft:donkey", "minecraft:llama", "minecraft:wolf",
        "minecraft:fox", "minecraft:goat", "minecraft:frog", "minecraft:turtle", "minecraft:panda",
        "minecraft:ocelot", "minecraft:parrot", "minecraft:bat", "minecraft:polar_bear",
        "minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin", "minecraft:axolotl",
        "minecraft:cod", "minecraft:salmon", "minecraft:pufferfish", "minecraft:tropical_fish",
        "minecraft:bee");

    /** Membership view of {@link #NATURAL_SPAWN_MOBS} for the default-scale fallback in {@link #naturalSpawnScale}. */
    private static final Set<String> NATURAL_SPAWN_SET = Set.copyOf(NATURAL_SPAWN_MOBS);

    /**
     * The water-dwelling subset of {@link #NATURAL_SPAWN_MOBS} that defaults to a {@code 0} replacement
     * chance (the rest default to {@link #DEFAULT_NATURAL_SPAWN_SCALE}). Users can still raise these via
     * an explicit {@code naturalSpawnScale.<id>} line or the {@code /playermob naturalspawn} command.
     */
    public static final Set<String> WATER_SPAWN_MOBS = Set.of(
        "minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin", "minecraft:axolotl",
        "minecraft:cod", "minecraft:salmon", "minecraft:pufferfish", "minecraft:tropical_fish",
        "minecraft:guardian");

    private static volatile float echoFriendChance = DEFAULT_ECHO_FRIEND_CHANCE;
    private static volatile boolean debugSpawnLog = DEFAULT_DEBUG_SPAWN_LOG;
    private static volatile boolean trainDigThrough = DEFAULT_TRAIN_DIG_THROUGH;
    private static volatile boolean trainFollowLovedPlayer = DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER;
    private static volatile boolean naturalSpawnEnabled = DEFAULT_NATURAL_SPAWN_ENABLED;
    private static volatile float naturalSpawnDefaultScale = DEFAULT_NATURAL_SPAWN_SCALE;
    /** Per-mob explicit overrides (id → clamped 0–1 chance); immutable, replaced wholesale on {@link #load}. */
    private static volatile Map<String, Float> naturalSpawnScales = Map.of();
    private static volatile float villageCompanionChance = DEFAULT_VILLAGE_COMPANION_CHANCE;

    private PlayerMobConfig() {}

    /** Chance (0–1) a DT echo with logged friends also brings a friend-echo (see {@code maybeSpawnFriendPair}). */
    public static float echoFriendChance() {
        return echoFriendChance;
    }

    /** When true, every DT auto-spawn logs + broadcasts a colour-coded chat message (see {@code DtSpawnDebug}). */
    public static boolean debugSpawnLog() {
        return debugSpawnLog;
    }

    /**
     * When true (default), a PlayerMob riding a Dungeon Train mines soft fill blocking its march —
     * ice, dirt, mud, moss, logs — once it's stuck against it, to pass through a packed carriage.
     * See {@code DungeonTrainEnvironment#digObstructingBlock}.
     */
    public static boolean trainDigThrough() {
        return trainDigThrough;
    }

    /**
     * When true (default), a PlayerMob riding a Dungeon Train that loves a player aboard the same train
     * abandons its fixed march and heads to that player's carriage, idling once it's in the same
     * carriage. See {@code PlayerMobEntity#effectiveTrainMarchDir}.
     */
    public static boolean trainFollowLovedPlayer() {
        return trainFollowLovedPlayer;
    }

    /** Master switch for natural spawning — when false (default) no mob is ever replaced by a PlayerMob. */
    public static boolean naturalSpawnEnabled() {
        return naturalSpawnEnabled;
    }

    /**
     * The chance (0–1) a PlayerMob spawns <em>instead of</em> {@code typeId} on a natural spawn.
     *
     * <p>Returns {@code 0} when natural spawning is off. Otherwise an explicit
     * {@code naturalSpawnScale.<typeId>} override wins; failing that, any mob in
     * {@link #NATURAL_SPAWN_MOBS} falls back to {@link #naturalSpawnDefaultScale}; anything else is {@code 0}.
     * Keyed by the entity-type id string (e.g. {@code "minecraft:zombie"}) so it stays free of the
     * {@code ResourceLocation}/{@code Identifier} split across MC versions.</p>
     */
    public static float naturalSpawnScale(String typeId) {
        return resolveScale(naturalSpawnEnabled, naturalSpawnDefaultScale, naturalSpawnScales, typeId);
    }

    /**
     * Pure resolution of a mob's replacement chance — extracted from {@link #naturalSpawnScale} so the
     * master-off / explicit-override / listed-default / unlisted rules are unit-tested without touching
     * the static state or the filesystem.
     */
    static float resolveScale(boolean enabled, float defaultScale, Map<String, Float> overrides, String typeId) {
        if (!enabled) {
            return 0.0F;
        }
        Float override = overrides.get(typeId);
        if (override != null) {
            return override;
        }
        return listedDefault(typeId, defaultScale);
    }

    /**
     * The generated/fallback chance for a listed mob with no explicit override: {@code defaultScale} for a
     * land mob, {@code 0} for a {@link #WATER_SPAWN_MOBS water} mob, and {@code 0} for anything unlisted.
     * Shared by {@link #resolveScale} and {@link #writeDefault} so the default file matches the runtime
     * fallback exactly.
     */
    static float listedDefault(String typeId, float defaultScale) {
        if (!NATURAL_SPAWN_SET.contains(typeId)) {
            return 0.0F;
        }
        return WATER_SPAWN_MOBS.contains(typeId) ? 0.0F : defaultScale;
    }

    /** The default replacement chance applied to a listed mob with no explicit override (config / commands). */
    public static float naturalSpawnDefaultScale() {
        return naturalSpawnDefaultScale;
    }

    /**
     * Chance (0–1), when natural spawning is on, that each villager generated with a village also spawns a
     * PlayerMob beside it (additive — see {@code NaturalSpawnReplacer.maybeSpawnVillageCompanion}).
     */
    public static float villageCompanionChance() {
        return villageCompanionChance;
    }

    /**
     * Toggle the DT-spawn debug log at runtime (e.g. from {@code /playermob debug spawnlog}). A session
     * override — not written back to the file, which stays the startup default.
     */
    public static void setDebugSpawnLog(boolean enabled) {
        debugSpawnLog = enabled;
    }

    /**
     * Flip the natural-spawn master switch at runtime (e.g. from {@code /playermob naturalspawn on|off}).
     * A session override — not written back to the file, which stays the startup default.
     */
    public static void setNaturalSpawnEnabled(boolean enabled) {
        naturalSpawnEnabled = enabled;
    }

    /**
     * Set a single mob's replacement chance at runtime (e.g. from {@code /playermob naturalspawn <mob> ...}).
     * Copies the immutable override map with the clamped value added/replaced, so concurrent spawn-thread
     * reads always see a complete map. A session override — not written back to the file.
     */
    public static void setNaturalSpawnScale(String typeId, float chance) {
        Map<String, Float> next = new HashMap<>(naturalSpawnScales);
        next.put(typeId, clamp01(chance));
        naturalSpawnScales = Map.copyOf(next);
    }

    /**
     * Load {@code <configDir>/playermob.properties} into the static fields, writing a commented
     * default file first if none exists. Never throws — any failure logs and keeps the defaults.
     */
    public static void load(Path configDir) {
        try {
            Path file = configDir.resolve(FILE);
            if (!Files.exists(file)) {
                writeDefault(file);
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            Values v = parse(props);
            echoFriendChance = v.echoFriendChance();
            debugSpawnLog = v.debugSpawnLog();
            trainDigThrough = v.trainDigThrough();
            trainFollowLovedPlayer = v.trainFollowLovedPlayer();
            naturalSpawnEnabled = v.naturalSpawnEnabled();
            naturalSpawnDefaultScale = v.naturalSpawnDefaultScale();
            naturalSpawnScales = v.naturalSpawnScales();
            villageCompanionChance = v.villageCompanionChance();
            LOGGER.info("[{}] config: {}={}, {}={}, {}={}, {}={}, {}={}, {}={} ({} per-mob override(s)), {}={}",
                PlayerMob.MOD_ID,
                KEY_ECHO_FRIEND_CHANCE, echoFriendChance, KEY_DEBUG_SPAWN_LOG, debugSpawnLog,
                KEY_TRAIN_DIG_THROUGH, trainDigThrough,
                KEY_TRAIN_FOLLOW_LOVED_PLAYER, trainFollowLovedPlayer,
                KEY_NATURAL_SPAWN_ENABLED, naturalSpawnEnabled,
                KEY_NATURAL_SPAWN_DEFAULT_SCALE, naturalSpawnDefaultScale, naturalSpawnScales.size(),
                KEY_VILLAGE_COMPANION_CHANCE, villageCompanionChance);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[{}] failed to load {}; using defaults", PlayerMob.MOD_ID, FILE, e);
        }
    }

    /** Parsed, validated values — split out (pure, no I/O) so the parsing rules are unit-tested. */
    record Values(float echoFriendChance, boolean debugSpawnLog, boolean trainDigThrough,
                  boolean trainFollowLovedPlayer, boolean naturalSpawnEnabled,
                  float naturalSpawnDefaultScale, Map<String, Float> naturalSpawnScales,
                  float villageCompanionChance) {}

    static Values parse(Properties props) {
        return new Values(
            clamp01(parseFloat(props.getProperty(KEY_ECHO_FRIEND_CHANCE), DEFAULT_ECHO_FRIEND_CHANCE)),
            parseBool(props.getProperty(KEY_DEBUG_SPAWN_LOG), DEFAULT_DEBUG_SPAWN_LOG),
            parseBool(props.getProperty(KEY_TRAIN_DIG_THROUGH), DEFAULT_TRAIN_DIG_THROUGH),
            parseBool(props.getProperty(KEY_TRAIN_FOLLOW_LOVED_PLAYER), DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER),
            parseBool(props.getProperty(KEY_NATURAL_SPAWN_ENABLED), DEFAULT_NATURAL_SPAWN_ENABLED),
            clamp01(parseFloat(props.getProperty(KEY_NATURAL_SPAWN_DEFAULT_SCALE), DEFAULT_NATURAL_SPAWN_SCALE)),
            parseScales(props),
            clamp01(parseFloat(props.getProperty(KEY_VILLAGE_COMPANION_CHANCE), DEFAULT_VILLAGE_COMPANION_CHANCE)));
    }

    /**
     * Collect every {@code naturalSpawnScale.<id>} key into an immutable id → clamped-chance map. Only
     * keys with a parseable float are kept; a blank id or unparseable value is dropped so the mob falls
     * through to {@link #naturalSpawnDefaultScale} (or 0 if unlisted) at lookup time.
     */
    static Map<String, Float> parseScales(Properties props) {
        Map<String, Float> map = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(NATURAL_SPAWN_SCALE_PREFIX)) {
                continue;
            }
            String id = name.substring(NATURAL_SPAWN_SCALE_PREFIX.length()).trim();
            if (id.isEmpty()) {
                continue;
            }
            float parsed = parseFloat(props.getProperty(name), Float.NaN);
            if (!Float.isNaN(parsed)) {
                map.put(id, clamp01(parsed));
            }
        }
        return Map.copyOf(map);
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    private static void writeDefault(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        StringBuilder body = new StringBuilder()
            .append("# PlayerMob configuration.\n")
            .append("#\n")
            .append("# echoFriendChance: chance (0.0-1.0) that a Dungeon-Train echo which had logged\n")
            .append("#   friends also brings back a friend-echo of one of them. Default 0.4.\n")
            .append("# debugSpawnLog: when true, broadcasts a colour-coded chat message (and logs a line)\n")
            .append("#   on every Dungeon-Train PlayerMob auto-spawn — yellow=friend, purple=echo,\n")
            .append("#   green=echo+friend. Default false.\n")
            .append("# trainDigThrough: when true, a PlayerMob riding a Dungeon Train mines soft fill\n")
            .append("#   (ice, dirt, mud, moss, logs) blocking its march once it's stuck against it, to\n")
            .append("#   pass through a packed carriage. Default true.\n")
            .append("# trainFollowLovedPlayer: when true, a PlayerMob on a Dungeon Train that loves a player\n")
            .append("#   aboard the same train abandons its fixed march to head to that player's carriage,\n")
            .append("#   idling once it's in the same carriage. Default true.\n")
            .append(KEY_ECHO_FRIEND_CHANCE).append("=").append(DEFAULT_ECHO_FRIEND_CHANCE).append("\n")
            .append(KEY_DEBUG_SPAWN_LOG).append("=").append(DEFAULT_DEBUG_SPAWN_LOG).append("\n")
            .append(KEY_TRAIN_DIG_THROUGH).append("=").append(DEFAULT_TRAIN_DIG_THROUGH).append("\n")
            .append(KEY_TRAIN_FOLLOW_LOVED_PLAYER).append("=").append(DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER).append("\n")
            .append("#\n")
            .append("# --- Natural spawning (opt-in) ------------------------------------------------\n")
            .append("# naturalSpawnEnabled: master switch. When false (default), PlayerMobs only appear\n")
            .append("#   via spawn egg, /summon, or Dungeon Train — never naturally. Set true to let the\n")
            .append("#   per-mob chances below take effect.\n")
            .append("# naturalSpawnDefaultScale: the chance (0.0-1.0) used for any land mob below whose line\n")
            .append("#   is deleted. Default 0.8. Water mobs (fish, squid, dolphin, axolotl, guardian, …)\n")
            .append("#   default to 0.0 instead — raise their line to spawn PlayerMobs underwater.\n")
            .append("#\n")
            .append("# naturalSpawnScale.<id>: chance (0.0-1.0) a PlayerMob spawns INSTEAD of that mob on a\n")
            .append("#   natural spawn (the mob itself is then suppressed). 0.0 = never replace it; 1.0 =\n")
            .append("#   always. Delete a line to fall back to naturalSpawnDefaultScale. Only takes effect\n")
            .append("#   while naturalSpawnEnabled=true.\n")
            .append("#\n")
            .append("# villageCompanionChance: chance (0.0-1.0), while naturalSpawnEnabled=true, that each\n")
            .append("#   villager generated with a village ALSO spawns a PlayerMob beside it (additive — the\n")
            .append("#   villager is not replaced). Default 0.25. 0.0 disables it.\n")
            .append(KEY_NATURAL_SPAWN_ENABLED).append("=").append(DEFAULT_NATURAL_SPAWN_ENABLED).append("\n")
            .append(KEY_NATURAL_SPAWN_DEFAULT_SCALE).append("=").append(DEFAULT_NATURAL_SPAWN_SCALE).append("\n")
            .append(KEY_VILLAGE_COMPANION_CHANCE).append("=").append(DEFAULT_VILLAGE_COMPANION_CHANCE).append("\n");
        for (String id : NATURAL_SPAWN_MOBS) {
            body.append(NATURAL_SPAWN_SCALE_PREFIX).append(id).append("=")
                .append(listedDefault(id, DEFAULT_NATURAL_SPAWN_SCALE)).append("\n");
        }
        Files.writeString(file, body.toString());
    }
}
