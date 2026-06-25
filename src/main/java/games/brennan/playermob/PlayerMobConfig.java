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
    private static final String KEY_ATTACK_RANGE_MULTIPLIER = "attackRangeMultiplier";
    private static final String KEY_RANGED_ATTACK_RANGE_MULTIPLIER = "rangedAttackRangeMultiplier";
    private static final String KEY_NATURAL_SPAWN_ENABLED = "naturalSpawnEnabled";
    /** Prefix for per-mob companion-chance keys, e.g. {@code naturalSpawnScale.minecraft:zombie}. */
    private static final String NATURAL_SPAWN_SCALE_PREFIX = "naturalSpawnScale.";

    private static final String KEY_REQUIRE_ARROWS = "requireArrows";
    private static final String KEY_SEEK_ARROWS_WHEN_EMPTY = "seekArrowsWhenEmpty";
    private static final String KEY_RANGED_ENGAGE_DISTANCE = "rangedEngageDistance";
    private static final String KEY_MELEE_ENGAGE_DISTANCE = "meleeEngageDistance";

    /** Default chance a Dungeon-Train echo with logged friends also brings back a friend-echo. */
    public static final float DEFAULT_ECHO_FRIEND_CHANCE = 0.40F;
    /** Debug spawn logging ships off — when on it broadcasts a chat line on every DT auto-spawn. */
    public static final boolean DEFAULT_DEBUG_SPAWN_LOG = false;
    /** PlayerMobs dig through fill (ice/dirt/mud/moss/logs) blocking a Dungeon-Train carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_DIG_THROUGH = true;
    /** On a Dungeon Train, a PlayerMob that loves a player aboard heads to that player's carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER = true;
    /**
     * Global multiplier on the trait-based distance at which an aggressive PlayerMob proactively attacks a
     * player. 1.0 = unchanged; applies to all such mobs regardless of weapon.
     */
    public static final float DEFAULT_ATTACK_RANGE_MULTIPLIER = 1.0F;
    /**
     * Extra multiplier applied on top of {@link #DEFAULT_ATTACK_RANGE_MULTIPLIER} when the mob holds a ranged
     * weapon (bow/crossbow) with ammo, so it opens fire from further out. Default 2.0 (×2 reach).
     */
    public static final float DEFAULT_RANGED_ATTACK_RANGE_MULTIPLIER = 2.0F;
    /** Natural spawning ships OFF — PlayerMobs only appear via egg, {@code /summon}, or Dungeon Train until enabled. */
    public static final boolean DEFAULT_NATURAL_SPAWN_ENABLED = false;
    /** Ranged weapons need real arrows in the mob's inventory; on by default. Off restores vanilla infinite ammo. */
    public static final boolean DEFAULT_REQUIRE_ARROWS = true;
    /** Out of arrows mid-fight, a PlayerMob fetches a nearby dropped arrow (enemy not too close); on by default. */
    public static final boolean DEFAULT_SEEK_ARROWS_WHEN_EMPTY = true;
    /** Beyond this many blocks a PlayerMob prefers a ranged weapon (default 8). */
    public static final float DEFAULT_RANGED_ENGAGE_DISTANCE = 8.0F;
    /** Within this many blocks a PlayerMob draws a melee weapon (default 4); must stay below the ranged distance. */
    public static final float DEFAULT_MELEE_ENGAGE_DISTANCE = 4.0F;

    /**
     * The mob groups natural spawning understands. Each carries the default chance that, when natural
     * spawning is on, a PlayerMob spawns <em>beside</em> a member mob (additive — the mob is never
     * replaced), plus the member entity ids. {@link #NATURAL_SPAWN_MOBS} and {@link #MOB_GROUP} are
     * derived from these lists so the catalogue and the grouping can't drift. Ids absent from a given MC
     * version (e.g. {@code bogged} pre-1.21) simply never match a real spawn, so listing them is harmless.
     */
    public enum SpawnGroup {
        HOSTILE(0.0F, List.of(
            "minecraft:zombie", "minecraft:zombie_villager", "minecraft:husk", "minecraft:drowned",
            "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:creeper",
            "minecraft:spider", "minecraft:cave_spider", "minecraft:witch", "minecraft:slime",
            "minecraft:enderman", "minecraft:endermite", "minecraft:silverfish", "minecraft:phantom",
            "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:ravager",
            "minecraft:guardian")),
        NETHER(0.05F, List.of(
            "minecraft:blaze", "minecraft:ghast", "minecraft:magma_cube", "minecraft:wither_skeleton",
            "minecraft:piglin", "minecraft:piglin_brute", "minecraft:hoglin", "minecraft:zombified_piglin",
            "minecraft:strider")),
        ANIMALS(0.15F, List.of(
            "minecraft:cow", "minecraft:mooshroom", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
            "minecraft:rabbit", "minecraft:horse", "minecraft:donkey", "minecraft:llama", "minecraft:goat",
            "minecraft:frog", "minecraft:turtle", "minecraft:panda", "minecraft:polar_bear")),
        FRIENDLY(0.15F, List.of(
            "minecraft:wolf", "minecraft:fox", "minecraft:ocelot", "minecraft:parrot", "minecraft:bat",
            "minecraft:bee")),
        WATER(0.0F, List.of(
            "minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin", "minecraft:axolotl",
            "minecraft:cod", "minecraft:salmon", "minecraft:pufferfish", "minecraft:tropical_fish")),
        VILLAGER(0.25F, List.of(
            "minecraft:villager", "minecraft:iron_golem"));

        public final float defaultScale;
        public final List<String> mobs;

        SpawnGroup(float defaultScale, List<String> mobs) {
            this.defaultScale = defaultScale;
            this.mobs = mobs;
        }

        /** Case-insensitive lookup for the {@code /playermob naturalspawn group <name>} command; null if unknown. */
        public static SpawnGroup byName(String name) {
            for (SpawnGroup g : values()) {
                if (g.name().equalsIgnoreCase(name)) {
                    return g;
                }
            }
            return null;
        }
    }

    /**
     * Every natural-spawn mob id, in group order — each gets a generated {@code naturalSpawnScale.<id>}
     * line in the default config (at its group default). Derived from {@link SpawnGroup}.
     */
    public static final List<String> NATURAL_SPAWN_MOBS = buildMobList();

    /** {@code id → group} for the default + group-membership lookups. Derived from {@link SpawnGroup}. */
    private static final Map<String, SpawnGroup> MOB_GROUP = buildMobGroups();

    private static List<String> buildMobList() {
        List<String> all = new java.util.ArrayList<>();
        for (SpawnGroup g : SpawnGroup.values()) {
            all.addAll(g.mobs);
        }
        return List.copyOf(all);
    }

    private static Map<String, SpawnGroup> buildMobGroups() {
        Map<String, SpawnGroup> map = new HashMap<>();
        for (SpawnGroup g : SpawnGroup.values()) {
            for (String id : g.mobs) {
                map.put(id, g);
            }
        }
        return Map.copyOf(map);
    }

    /** The group {@code typeId} belongs to, or {@code null} if it isn't a natural-spawn mob. */
    public static SpawnGroup groupOf(String typeId) {
        return MOB_GROUP.get(typeId);
    }

    private static volatile float echoFriendChance = DEFAULT_ECHO_FRIEND_CHANCE;
    private static volatile boolean debugSpawnLog = DEFAULT_DEBUG_SPAWN_LOG;
    private static volatile boolean trainDigThrough = DEFAULT_TRAIN_DIG_THROUGH;
    private static volatile boolean trainFollowLovedPlayer = DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER;
    private static volatile float attackRangeMultiplier = DEFAULT_ATTACK_RANGE_MULTIPLIER;
    private static volatile float rangedAttackRangeMultiplier = DEFAULT_RANGED_ATTACK_RANGE_MULTIPLIER;
    private static volatile boolean naturalSpawnEnabled = DEFAULT_NATURAL_SPAWN_ENABLED;
    private static volatile boolean requireArrows = DEFAULT_REQUIRE_ARROWS;
    private static volatile boolean seekArrowsWhenEmpty = DEFAULT_SEEK_ARROWS_WHEN_EMPTY;
    private static volatile float rangedEngageDistance = DEFAULT_RANGED_ENGAGE_DISTANCE;
    private static volatile float meleeEngageDistance = DEFAULT_MELEE_ENGAGE_DISTANCE;
    /** Per-mob explicit overrides (id → clamped 0–1 chance); immutable, replaced wholesale on {@link #load}. */
    private static volatile Map<String, Float> naturalSpawnScales = Map.of();

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

    /**
     * Global multiplier on an aggressive PlayerMob's trait-based attack-acquisition distance toward a player
     * (1.0 = unchanged). Stacks with {@link #rangedAttackRangeMultiplier()} for ranged-armed mobs. See
     * {@code PlayerMobEntity#reactionToward}.
     */
    public static float attackRangeMultiplier() {
        return attackRangeMultiplier;
    }

    /**
     * Extra multiplier on the attack-acquisition distance when the mob has a ranged weapon with ammo
     * (default 2.0). Effective ranged reach scale = {@link #attackRangeMultiplier()} × this. See
     * {@code PlayerMobEntity#hasRangedWeaponWithAmmo}.
     */
    public static float rangedAttackRangeMultiplier() {
        return rangedAttackRangeMultiplier;
    }

    /** Master switch for natural spawning — when false (default) no PlayerMob ever spawns alongside a mob. */
    public static boolean naturalSpawnEnabled() {
        return naturalSpawnEnabled;
    }

    /**
     * When true (default), a PlayerMob only fires a bow/crossbow if it carries real arrows, consuming one per
     * shot and falling back to melee when empty. When false, ranged weapons have vanilla infinite ammo.
     * See {@code PlayerMobEntity#hasRangedAmmo} / {@code RangedAmmo}.
     */
    public static boolean requireArrows() {
        return requireArrows;
    }

    /**
     * When true (default), a PlayerMob that runs out of arrows mid-fight walks to a nearby dropped arrow to
     * restock (only if the enemy isn't too close), otherwise it closes to melee. See {@code SeekAmmoGoal}.
     * No effect when {@link #requireArrows()} is off.
     */
    public static boolean seekArrowsWhenEmpty() {
        return seekArrowsWhenEmpty;
    }

    /** Distance (blocks) beyond which a PlayerMob prefers a ranged weapon over melee. See {@code equipBestWeaponForTarget}. */
    public static float rangedEngageDistance() {
        return rangedEngageDistance;
    }

    /** Distance (blocks) within which a PlayerMob draws melee instead of ranged. Always below {@link #rangedEngageDistance()}. */
    public static float meleeEngageDistance() {
        return meleeEngageDistance;
    }

    /**
     * The chance (0–1) a PlayerMob spawns <em>alongside</em> {@code typeId} on a natural spawn (additive —
     * the mob is never replaced).
     *
     * <p>Returns {@code 0} when natural spawning is off. Otherwise an explicit
     * {@code naturalSpawnScale.<typeId>} override wins; failing that, a grouped mob falls back to its
     * {@link SpawnGroup#defaultScale group default}; anything ungrouped is {@code 0}. Keyed by the
     * entity-type id string (e.g. {@code "minecraft:zombie"}) so it stays free of the
     * {@code ResourceLocation}/{@code Identifier} split across MC versions.</p>
     */
    public static float naturalSpawnScale(String typeId) {
        return resolveScale(naturalSpawnEnabled, naturalSpawnScales, typeId);
    }

    /**
     * Pure resolution of a mob's companion chance — extracted from {@link #naturalSpawnScale} so the
     * master-off / explicit-override / group-default / ungrouped rules are unit-tested without touching
     * the static state or the filesystem.
     */
    static float resolveScale(boolean enabled, Map<String, Float> overrides, String typeId) {
        if (!enabled) {
            return 0.0F;
        }
        Float override = overrides.get(typeId);
        if (override != null) {
            return override;
        }
        return listedDefault(typeId);
    }

    /**
     * The generated/fallback chance for a grouped mob with no explicit override — its
     * {@link SpawnGroup#defaultScale group default}, or {@code 0} if ungrouped. Shared by
     * {@link #resolveScale} and {@link #writeDefault} so the default file matches the runtime fallback.
     */
    static float listedDefault(String typeId) {
        SpawnGroup group = MOB_GROUP.get(typeId);
        return group == null ? 0.0F : group.defaultScale;
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
     * Flip the require-arrows / unlimited-ammo gate at runtime (e.g. from {@code /playermob unlimitedammo on|off}).
     * {@code true} = consume inventory ammo (default); {@code false} = global unlimited ammo. A session override —
     * not written back to the file, which stays the startup default.
     */
    public static void setRequireArrows(boolean required) {
        requireArrows = required;
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
     * Set every mob in {@code group} to {@code chance} at runtime (e.g. from
     * {@code /playermob naturalspawn group <group> ...}). One copy-on-write of the override map, so spawn
     * reads always see a complete map. A session override — not written back to the file.
     */
    public static void setGroupScale(SpawnGroup group, float chance) {
        float clamped = clamp01(chance);
        Map<String, Float> next = new HashMap<>(naturalSpawnScales);
        for (String id : group.mobs) {
            next.put(id, clamped);
        }
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
            attackRangeMultiplier = v.attackRangeMultiplier();
            rangedAttackRangeMultiplier = v.rangedAttackRangeMultiplier();
            naturalSpawnEnabled = v.naturalSpawnEnabled();
            requireArrows = v.requireArrows();
            seekArrowsWhenEmpty = v.seekArrowsWhenEmpty();
            rangedEngageDistance = v.rangedEngageDistance();
            meleeEngageDistance = v.meleeEngageDistance();
            naturalSpawnScales = v.naturalSpawnScales();
            LOGGER.info("[{}] config: {}={}, {}={}, {}={}, {}={}, {}={}, {}={}, {}={} ({} per-mob override(s))",
                PlayerMob.MOD_ID,
                KEY_ECHO_FRIEND_CHANCE, echoFriendChance, KEY_DEBUG_SPAWN_LOG, debugSpawnLog,
                KEY_TRAIN_DIG_THROUGH, trainDigThrough,
                KEY_TRAIN_FOLLOW_LOVED_PLAYER, trainFollowLovedPlayer,
                KEY_ATTACK_RANGE_MULTIPLIER, attackRangeMultiplier,
                KEY_RANGED_ATTACK_RANGE_MULTIPLIER, rangedAttackRangeMultiplier,
                KEY_NATURAL_SPAWN_ENABLED, naturalSpawnEnabled, naturalSpawnScales.size());
            LOGGER.info("[{}] combat config: {}={}, {}={}, {}={}, {}={}",
                PlayerMob.MOD_ID,
                KEY_REQUIRE_ARROWS, requireArrows, KEY_SEEK_ARROWS_WHEN_EMPTY, seekArrowsWhenEmpty,
                KEY_RANGED_ENGAGE_DISTANCE, rangedEngageDistance,
                KEY_MELEE_ENGAGE_DISTANCE, meleeEngageDistance);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[{}] failed to load {}; using defaults", PlayerMob.MOD_ID, FILE, e);
        }
    }

    /** Parsed, validated values — split out (pure, no I/O) so the parsing rules are unit-tested. */
    record Values(float echoFriendChance, boolean debugSpawnLog, boolean trainDigThrough,
                  boolean trainFollowLovedPlayer, float attackRangeMultiplier,
                  float rangedAttackRangeMultiplier, boolean naturalSpawnEnabled,
                  boolean requireArrows, boolean seekArrowsWhenEmpty,
                  float rangedEngageDistance, float meleeEngageDistance,
                  Map<String, Float> naturalSpawnScales) {}

    static Values parse(Properties props) {
        float[] engage = parseEngageDistances(props);
        return new Values(
            clamp01(parseFloat(props.getProperty(KEY_ECHO_FRIEND_CHANCE), DEFAULT_ECHO_FRIEND_CHANCE)),
            parseBool(props.getProperty(KEY_DEBUG_SPAWN_LOG), DEFAULT_DEBUG_SPAWN_LOG),
            parseBool(props.getProperty(KEY_TRAIN_DIG_THROUGH), DEFAULT_TRAIN_DIG_THROUGH),
            parseBool(props.getProperty(KEY_TRAIN_FOLLOW_LOVED_PLAYER), DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER),
            clampMultiplier(parseFloat(props.getProperty(KEY_ATTACK_RANGE_MULTIPLIER), DEFAULT_ATTACK_RANGE_MULTIPLIER)),
            clampMultiplier(parseFloat(props.getProperty(KEY_RANGED_ATTACK_RANGE_MULTIPLIER), DEFAULT_RANGED_ATTACK_RANGE_MULTIPLIER)),
            parseBool(props.getProperty(KEY_NATURAL_SPAWN_ENABLED), DEFAULT_NATURAL_SPAWN_ENABLED),
            parseBool(props.getProperty(KEY_REQUIRE_ARROWS), DEFAULT_REQUIRE_ARROWS),
            parseBool(props.getProperty(KEY_SEEK_ARROWS_WHEN_EMPTY), DEFAULT_SEEK_ARROWS_WHEN_EMPTY),
            engage[0], engage[1],
            parseScales(props));
    }

    /**
     * Parse the ranged/melee engage distances as a {@code {ranged, melee}} pair, enforcing the invariant
     * {@code 0 < melee < ranged}. If either is unparseable or the band is inverted/degenerate, BOTH fall back
     * to their defaults (8 / 4) so the combat switch always has a sane hysteresis band — fail-safe, never throws.
     */
    static float[] parseEngageDistances(Properties props) {
        float ranged = parseFloat(props.getProperty(KEY_RANGED_ENGAGE_DISTANCE), DEFAULT_RANGED_ENGAGE_DISTANCE);
        float melee = parseFloat(props.getProperty(KEY_MELEE_ENGAGE_DISTANCE), DEFAULT_MELEE_ENGAGE_DISTANCE);
        if (!(melee > 0.0F && melee < ranged)) {
            return new float[] {DEFAULT_RANGED_ENGAGE_DISTANCE, DEFAULT_MELEE_ENGAGE_DISTANCE};
        }
        return new float[] {ranged, melee};
    }

    /**
     * Collect every {@code naturalSpawnScale.<id>} key into an immutable id → clamped-chance map. Only
     * keys with a parseable float are kept; a blank id or unparseable value is dropped so the mob falls
     * through to its group default (or 0 if ungrouped) at lookup time.
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

    /** Largest a range multiplier may grow to — guards against absurd config (engagement is also capped by the mob's FOLLOW_RANGE + line-of-sight). */
    public static final float MAX_RANGE_MULTIPLIER = 64.0F;

    /** Clamp a range multiplier to {@code [0, MAX_RANGE_MULTIPLIER]}; 0 disables proactive distance-attack, and it never goes negative. */
    private static float clampMultiplier(float v) {
        return v < 0.0F ? 0.0F : (v > MAX_RANGE_MULTIPLIER ? MAX_RANGE_MULTIPLIER : v);
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
            .append("# attackRangeMultiplier: global multiplier on the trait-based distance at which an\n")
            .append("#   aggressive PlayerMob proactively attacks a player. 1.0 = unchanged; applies to ALL\n")
            .append("#   such mobs. (Engagement is still capped by the mob's follow range + line-of-sight.)\n")
            .append("# rangedAttackRangeMultiplier: extra multiplier applied ON TOP of attackRangeMultiplier\n")
            .append("#   when the mob holds a ranged weapon (bow/crossbow) with ammo, so it opens fire from\n")
            .append("#   further out. Default 2.0. Effective ranged reach = attackRangeMultiplier x this.\n")
            .append("# requireArrows: when true, a PlayerMob only fires a bow/crossbow if it carries ammo it can\n")
            .append("#   use (arrows for bows; arrows or firework rockets for crossbows), consuming one per shot\n")
            .append("#   and falling back to melee when empty. Set false for global unlimited ammo. Toggle live\n")
            .append("#   with /playermob unlimitedammo on|off (session override). Default true.\n")
            .append("# seekArrowsWhenEmpty: when true, a PlayerMob out of arrows mid-fight walks to a nearby\n")
            .append("#   dropped arrow to restock (only if the enemy isn't too close), otherwise it closes to\n")
            .append("#   melee. No effect when requireArrows=false. Default true.\n")
            .append("# rangedEngageDistance / meleeEngageDistance: a PlayerMob prefers a ranged weapon beyond\n")
            .append("#   rangedEngageDistance blocks and melee within meleeEngageDistance blocks (the band\n")
            .append("#   between is hysteresis). meleeEngageDistance must be > 0 and < rangedEngageDistance,\n")
            .append("#   else both reset to 8 / 4. Defaults 8.0 / 4.0.\n")
            .append(KEY_ECHO_FRIEND_CHANCE).append("=").append(DEFAULT_ECHO_FRIEND_CHANCE).append("\n")
            .append(KEY_DEBUG_SPAWN_LOG).append("=").append(DEFAULT_DEBUG_SPAWN_LOG).append("\n")
            .append(KEY_TRAIN_DIG_THROUGH).append("=").append(DEFAULT_TRAIN_DIG_THROUGH).append("\n")
            .append(KEY_TRAIN_FOLLOW_LOVED_PLAYER).append("=").append(DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER).append("\n")
            .append(KEY_ATTACK_RANGE_MULTIPLIER).append("=").append(DEFAULT_ATTACK_RANGE_MULTIPLIER).append("\n")
            .append(KEY_RANGED_ATTACK_RANGE_MULTIPLIER).append("=").append(DEFAULT_RANGED_ATTACK_RANGE_MULTIPLIER).append("\n")
            .append(KEY_REQUIRE_ARROWS).append("=").append(DEFAULT_REQUIRE_ARROWS).append("\n")
            .append(KEY_SEEK_ARROWS_WHEN_EMPTY).append("=").append(DEFAULT_SEEK_ARROWS_WHEN_EMPTY).append("\n")
            .append(KEY_RANGED_ENGAGE_DISTANCE).append("=").append(DEFAULT_RANGED_ENGAGE_DISTANCE).append("\n")
            .append(KEY_MELEE_ENGAGE_DISTANCE).append("=").append(DEFAULT_MELEE_ENGAGE_DISTANCE).append("\n")
            .append("#\n")
            .append("# --- Natural spawning (opt-in) ------------------------------------------------\n")
            .append("# naturalSpawnEnabled: master switch. When false (default), PlayerMobs only appear\n")
            .append("#   via spawn egg, /summon, or Dungeon Train — never naturally. Set true to let the\n")
            .append("#   per-mob chances below take effect.\n")
            .append("#\n")
            .append("# naturalSpawnScale.<id>: chance (0.0-1.0) that, when that mob spawns naturally, a\n")
            .append("#   PlayerMob ALSO spawns beside it (additive — the mob is NOT replaced). 0.0 = never;\n")
            .append("#   1.0 = always. Each line below defaults to its group's chance:\n")
            .append("#     Hostile 0.0   Nether 0.05   Animals 0.15   Friendly 0.15   Water 0.0   Villager 0.25\n")
            .append("#   Edit individual lines, or set a whole group live with\n")
            .append("#   /playermob naturalspawn group <group> <chance>. Delete a line to fall back to its\n")
            .append("#   group default. Only takes effect while naturalSpawnEnabled=true.\n")
            .append(KEY_NATURAL_SPAWN_ENABLED).append("=").append(DEFAULT_NATURAL_SPAWN_ENABLED).append("\n");
        for (SpawnGroup group : SpawnGroup.values()) {
            body.append("# --- ").append(group.name().charAt(0))
                .append(group.name().substring(1).toLowerCase(java.util.Locale.ROOT))
                .append(" (").append(group.defaultScale).append(") ---\n");
            for (String id : group.mobs) {
                body.append(NATURAL_SPAWN_SCALE_PREFIX).append(id).append("=")
                    .append(group.defaultScale).append("\n");
            }
        }
        Files.writeString(file, body.toString());
    }
}
