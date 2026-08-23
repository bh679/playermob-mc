package games.brennan.playermob;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.entity.AutoNameMode;
import games.brennan.playermob.entity.ModdedRangedWeapons;
import games.brennan.playermob.entity.WantedItemList;
import org.slf4j.Logger;

import java.io.IOException;
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
    private static final String KEY_REINCARNATION_DIFFICULTY_ISOLATION = "reincarnationDifficultyIsolation";
    private static final String KEY_DEBUG_SPAWN_LOG = "debugSpawnLog";
    private static final String KEY_TRAIN_DIG_THROUGH = "trainDigThrough";
    private static final String KEY_TRAIN_FOLLOW_LOVED_PLAYER = "trainFollowLovedPlayer";
    private static final String KEY_NATURAL_SPAWN_ENABLED = "naturalSpawnEnabled";
    private static final String KEY_AUTO_NAME_MODE = "autoNameMode";
    /** Legacy v0.65.0 boolean key, read for back-compat only ({@code true} → {@link AutoNameMode#NATURAL}). */
    private static final String KEY_AUTO_NAME_NATURAL_SPAWNS = "autoNameNaturalSpawns";
    /** Prefix for per-mob companion-chance keys, e.g. {@code naturalSpawnScale.minecraft:zombie}. */
    private static final String NATURAL_SPAWN_SCALE_PREFIX = "naturalSpawnScale.";

    private static final String KEY_REQUIRE_ARROWS = "requireArrows";
    private static final String KEY_SEEK_ARROWS_WHEN_EMPTY = "seekArrowsWhenEmpty";
    private static final String KEY_RANGED_ENGAGE_DISTANCE = "rangedEngageDistance";
    private static final String KEY_MELEE_ENGAGE_DISTANCE = "meleeEngageDistance";
    private static final String KEY_TNT_COMBAT = "tntCombat";
    private static final String KEY_END_CRYSTAL_COMBAT = "endCrystalCombat";
    private static final String KEY_EXTINGUISH_WITH_BUCKET = "extinguishWithBucket";
    private static final String KEY_HUNT_FOR_FOOD = "huntForFood";
    private static final String KEY_EXACT_NAMES = "exactNames";

    private static final String KEY_EXTRA_PICKUP_ITEMS = "extraPickupItems";
    private static final String KEY_MODDED_RANGED_WEAPONS = "moddedRangedWeapons";

    private static final String KEY_SKIN_SOURCE_BUNDLED = "skinSourceBundled";
    private static final String KEY_SKIN_SOURCE_ONLINE = "skinSourceOnline";
    private static final String KEY_SKIN_SOURCE_LOCAL = "skinSourceLocal";
    private static final String KEY_CUSTOM_SKIN_CHANCE = "customSkinChance";

    /** Default chance a Dungeon-Train echo with logged friends also brings back a friend-echo. */
    public static final float DEFAULT_ECHO_FRIEND_CHANCE = 0.40F;
    /**
     * Echoes are partitioned by the vanilla difficulty their life was lived on; on by default. Off
     * pools every difficulty together — the behaviour before the partition existed.
     */
    public static final boolean DEFAULT_REINCARNATION_DIFFICULTY_ISOLATION = true;
    /** Debug spawn logging ships off — when on it broadcasts a chat line on every DT auto-spawn. */
    public static final boolean DEFAULT_DEBUG_SPAWN_LOG = false;
    /** PlayerMobs dig through fill (ice/dirt/mud/moss/logs) blocking a Dungeon-Train carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_DIG_THROUGH = true;
    /** On a Dungeon Train, a PlayerMob that loves a player aboard heads to that player's carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER = true;
    /** Natural spawning ships OFF — PlayerMobs only appear via egg, {@code /summon}, or Dungeon Train until enabled. */
    public static final boolean DEFAULT_NATURAL_SPAWN_ENABLED = false;
    /** Auto-naming ships OFF — opt in (per spawn category) to label PlayerMobs with their skin source. */
    public static final AutoNameMode DEFAULT_AUTO_NAME_MODE = AutoNameMode.OFF;
    /**
     * Command {@code <name>} lookups fall back to the nearest PlayerMob when no name matches; off by default.
     * On, a non-matching name cancels the command instead.
     */
    public static final boolean DEFAULT_EXACT_NAMES = false;
    /** Ranged weapons need real arrows in the mob's inventory; on by default. Off restores vanilla infinite ammo. */
    public static final boolean DEFAULT_REQUIRE_ARROWS = true;
    /** Out of arrows mid-fight, a PlayerMob fetches a nearby dropped arrow (enemy not too close); on by default. */
    public static final boolean DEFAULT_SEEK_ARROWS_WHEN_EMPTY = true;
    /** Beyond this many blocks a PlayerMob prefers a ranged weapon (default 8). */
    public static final float DEFAULT_RANGED_ENGAGE_DISTANCE = 8.0F;
    /** Within this many blocks a PlayerMob draws a melee weapon (default 4); must stay below the ranged distance. */
    public static final float DEFAULT_MELEE_ENGAGE_DISTANCE = 4.0F;
    /** A PlayerMob carrying TNT + a way to light it bombs its target instead of fighting; on by default (needs mobGriefing). */
    public static final boolean DEFAULT_TNT_COMBAT = true;
    /** A PlayerMob carrying end crystals + obsidian + a solid cover block bombs its target with crystals; on by default (needs mobGriefing). */
    public static final boolean DEFAULT_END_CRYSTAL_COMBAT = true;
    /** A PlayerMob on fire and holding a water bucket douses itself with it; on by default. */
    public static final boolean DEFAULT_EXTINGUISH_WITH_BUCKET = true;
    /** A hungry PlayerMob hunts nearby food animals (cow/pig/chicken/sheep/rabbit); on by default. */
    public static final boolean DEFAULT_HUNT_FOR_FOOD = true;
    /** No extra pickup items by default — the mob wants only its hardcoded gear/ammo/valuable/consumable set. */
    public static final String DEFAULT_EXTRA_PICKUP_ITEMS = "";
    /** No modded ranged weapons recognised by default — only vanilla bows/crossbows are ranged out of the box. */
    public static final String DEFAULT_MODDED_RANGED_WEAPONS = "";
    /** Bundled default skins are an eligible random source by default. */
    public static final boolean DEFAULT_SKIN_SOURCE_BUNDLED = true;
    /** Online skins (datapack / by-name / external registry) are an eligible random source by default. */
    public static final boolean DEFAULT_SKIN_SOURCE_ONLINE = true;
    /** Local-folder skins ({@code config/playermob/skins}) are an eligible random source by default. */
    public static final boolean DEFAULT_SKIN_SOURCE_LOCAL = true;
    /** Default chance (0–1) a spawn overrides its bundled base look with a custom (online/local) skin. */
    public static final float DEFAULT_CUSTOM_SKIN_CHANCE = 0.40F;

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
    private static volatile boolean reincarnationDifficultyIsolation = DEFAULT_REINCARNATION_DIFFICULTY_ISOLATION;
    private static volatile boolean debugSpawnLog = DEFAULT_DEBUG_SPAWN_LOG;
    private static volatile boolean trainDigThrough = DEFAULT_TRAIN_DIG_THROUGH;
    private static volatile boolean trainFollowLovedPlayer = DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER;
    private static volatile boolean naturalSpawnEnabled = DEFAULT_NATURAL_SPAWN_ENABLED;
    private static volatile AutoNameMode autoNameMode = DEFAULT_AUTO_NAME_MODE;
    private static volatile boolean requireArrows = DEFAULT_REQUIRE_ARROWS;
    private static volatile boolean seekArrowsWhenEmpty = DEFAULT_SEEK_ARROWS_WHEN_EMPTY;
    private static volatile float rangedEngageDistance = DEFAULT_RANGED_ENGAGE_DISTANCE;
    private static volatile float meleeEngageDistance = DEFAULT_MELEE_ENGAGE_DISTANCE;
    private static volatile boolean tntCombat = DEFAULT_TNT_COMBAT;
    private static volatile boolean endCrystalCombat = DEFAULT_END_CRYSTAL_COMBAT;
    private static volatile boolean extinguishWithBucket = DEFAULT_EXTINGUISH_WITH_BUCKET;
    private static volatile boolean huntForFood = DEFAULT_HUNT_FOR_FOOD;
    private static volatile boolean exactNames = DEFAULT_EXACT_NAMES;
    /** Admin-configured extra items the mob always wants; replaced wholesale on {@link #load}. */
    private static volatile WantedItemList extraPickups = WantedItemList.EMPTY;
    /** Admin-configured modded ranged weapons + their ammo; replaced wholesale on {@link #load}. */
    private static volatile ModdedRangedWeapons moddedRanged = ModdedRangedWeapons.EMPTY;
    /** Per-mob explicit overrides (id → clamped 0–1 chance); immutable, replaced wholesale on {@link #load}. */
    private static volatile Map<String, Float> naturalSpawnScales = Map.of();
    private static volatile boolean skinSourceBundled = DEFAULT_SKIN_SOURCE_BUNDLED;
    private static volatile boolean skinSourceOnline = DEFAULT_SKIN_SOURCE_ONLINE;
    private static volatile boolean skinSourceLocal = DEFAULT_SKIN_SOURCE_LOCAL;
    private static volatile float customSkinChance = DEFAULT_CUSTOM_SKIN_CHANCE;

    private PlayerMobConfig() {}

    /** Chance (0–1) a DT echo with logged friends also brings a friend-echo (see {@code maybeSpawnFriendPair}). */
    public static float echoFriendChance() {
        return echoFriendChance;
    }

    /** When true, every DT auto-spawn logs + broadcasts a colour-coded chat message (see {@code DtSpawnDebug}). */
    /**
     * When true (default), a past life is only ever reincarnated on the vanilla difficulty it was lived
     * on — see {@code ReincarnationDifficulty}. Lives logged before the partition existed count as
     * {@code normal}. Off pools every difficulty together, as builds before this did.
     */
    public static boolean reincarnationDifficultyIsolation() {
        return reincarnationDifficultyIsolation;
    }

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

    /** Master switch for natural spawning — when false (default) no PlayerMob ever spawns alongside a mob. */
    public static boolean naturalSpawnEnabled() {
        return naturalSpawnEnabled;
    }

    /**
     * Which spawns get a nameplate derived from their skin source — the local-folder filename (no
     * extension) for a local skin, or the {@code displayName} for an online / registry skin (a bundled
     * default skin has no source name and stays unnamed). {@link AutoNameMode#OFF} by default; toggle live
     * with {@code /playermob autoname <off|natural|egg|all>}. See {@code PlayerMobEntity#finalizeSpawn} and
     * {@code SkinDisplayName}.
     */
    public static AutoNameMode autoNameMode() {
        return autoNameMode;
    }

    /**
     * When true, a {@code /playermob} subcommand whose {@code <name>} argument matches no loaded
     * PlayerMob is cancelled outright instead of falling back to the nearest one. Off by default
     * (the nearest-mob fallback stands) — turn it on when commands are issued by automation and a
     * stale or misspelled name must never command the wrong mob. See {@code ReincarnateCommand#resolveMob}.
     */
    public static boolean exactNames() {
        return exactNames;
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

    /**
     * When true (default), a PlayerMob carrying TNT and a way to light it (flint &amp; steel, fire charge,
     * redstone block, lever, button, or pressure plate) bombs the enemy it is fighting instead of trading
     * bow/melee blows, until it runs out of TNT. Also requires the {@code mobGriefing} gamerule (it places
     * and primes real TNT). See {@code TntCombatGoal}.
     */
    public static boolean tntCombat() {
        return tntCombat;
    }

    /**
     * When true (default), a PlayerMob carrying end crystals, obsidian (for the crystal's base), and solid blocks (for
     * cover) bombs the enemy it is fighting with end crystals instead of trading bow/melee blows: it seats a crystal on
     * an obsidian base, stacks a 2-block-tall cover between itself and the crystal, crouches behind it (raising a shield
     * if it has one), and punches the crystal to set it off — repeating until out of the kit. Also requires the
     * {@code mobGriefing} gamerule (it places blocks and triggers real explosions). See {@code EndCrystalCombatGoal}.
     */
    public static boolean endCrystalCombat() {
        return endCrystalCombat;
    }

    /**
     * When true (default), a PlayerMob that's on fire and holding a water bucket empties it onto the
     * ground, jumps in to douse itself, then — a short while after the fire is out — scoops the
     * water back into the bucket. See {@code FireBucketGoal}.
     */
    public static boolean extinguishWithBucket() {
        return extinguishWithBucket;
    }

    /**
     * When true (default), a PlayerMob that {@code wantsFood()} (no food carried, or hurt and low on
     * carried nutrition) hunts a nearby adult food animal (cow/pig/chicken/sheep/rabbit) as its attack
     * target, letting the existing weapon-aware attack goal do the kill. See {@code HuntForFoodGoal}.
     */
    public static boolean huntForFood() {
        return huntForFood;
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
     * Extra items the mob always picks up (off the floor and out of chests) and hoards in its backpack,
     * beyond the hardcoded gear/ammo/valuable/consumable categories. Configured via {@code extraPickupItems}
     * as item ids and/or {@code #}-prefixed item tags. See {@code PlayerMobEntity#isExtraWanted}.
     */
    public static WantedItemList extraPickups() {
        return extraPickups;
    }

    /**
     * Admin-configured modded ranged weapons (firearms from other mods) and the ammo each fires. Lets a mob
     * classify, draw, and fire non-vanilla ranged weapons — e.g. MusketMod muskets. Configured via
     * {@code moddedRangedWeapons} as {@code weapon>ammo[>holdTicks]} entries. Empty by default. See
     * {@code ModdedRangedAttackGoal} and {@code PlayerMobEntity#hasRangedAmmo}.
     */
    public static ModdedRangedWeapons moddedRanged() {
        return moddedRanged;
    }

    /**
     * Whether bundled default skins may be chosen by the spawn-time random roll (default true). One of the
     * three skin sources — see {@code SkinSourceSelector}. Bundled is also the always-safe fallback when no
     * other source has any skin. Toggle live with {@code /playermob skin source bundled on|off}.
     */
    public static boolean skinSourceBundled() {
        return skinSourceBundled;
    }

    /**
     * Whether online skins (datapack / by-name / external {@code SkinSources} registry) may be chosen by the
     * spawn-time random roll (default true). Toggle live with {@code /playermob skin source online on|off}.
     */
    public static boolean skinSourceOnline() {
        return skinSourceOnline;
    }

    /**
     * Whether local-folder skins ({@code config/playermob/skins}) may be chosen by the spawn-time random
     * roll (default true). Toggle live with {@code /playermob skin source local on|off}.
     */
    public static boolean skinSourceLocal() {
        return skinSourceLocal;
    }

    /**
     * The chance (0–1) a spawning PlayerMob overrides its bundled (vanilla default) base look with a
     * "custom" skin drawn from the combined enabled online + local pool (default 0.40). {@code 0.0} keeps
     * every mob on a bundled default; {@code 1.0} always picks a custom skin when a custom pool exists.
     * When no custom skin is available the mob keeps its bundled look regardless — bundled is the safety
     * fallback (see {@code SkinSourceSelector}). Toggle live with {@code /playermob skin chance <0.0-1.0>}.
     */
    public static float customSkinChance() {
        return customSkinChance;
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
     * Set the auto-name mode at runtime (e.g. from {@code /playermob autoname <off|natural|egg|all>}).
     * A session override — not written back to the file, which stays the startup default.
     */
    public static void setAutoNameMode(AutoNameMode mode) {
        autoNameMode = mode == null ? DEFAULT_AUTO_NAME_MODE : mode;
    }

    /**
     * Flip exact-name command matching at runtime (e.g. from {@code /playermob exactnames on|off}).
     * {@code true} = a {@code <name>} that matches nothing cancels the command; {@code false} (default)
     * = fall back to the nearest PlayerMob. A session override — not written back to the file, which
     * stays the startup default.
     */
    public static void setExactNames(boolean enabled) {
        exactNames = enabled;
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
     * Toggle TNT-combat at runtime (e.g. from {@code /playermob tntcombat on|off}). A session override —
     * not written back to the file, which stays the startup default.
     */
    public static void setTntCombat(boolean enabled) {
        tntCombat = enabled;
    }

    /**
     * Toggle end-crystal combat at runtime (e.g. from {@code /playermob endcrystalcombat on|off}). A session override —
     * not written back to the file, which stays the startup default.
     */
    public static void setEndCrystalCombat(boolean enabled) {
        endCrystalCombat = enabled;
    }

    /**
     * Toggle bucket self-extinguishing at runtime (e.g. from {@code /playermob extinguishwithbucket on|off}).
     * A session override — not written back to the file, which stays the startup default.
     */
    public static void setExtinguishWithBucket(boolean enabled) {
        extinguishWithBucket = enabled;
    }

    /**
     * Toggle animal-hunting at runtime (e.g. from {@code /playermob huntforfood on|off}). A session
     * override — not written back to the file, which stays the startup default.
     */
    public static void setHuntForFood(boolean enabled) {
        huntForFood = enabled;
    }

    /**
     * Toggle a skin source at runtime (e.g. from {@code /playermob skin source <bundled|online|local> on|off}).
     * A session override — not written back to the file, which stays the startup default.
     */
    public static void setSkinSourceBundled(boolean enabled) {
        skinSourceBundled = enabled;
    }

    /** @see #setSkinSourceBundled(boolean) */
    public static void setSkinSourceOnline(boolean enabled) {
        skinSourceOnline = enabled;
    }

    /** @see #setSkinSourceBundled(boolean) */
    public static void setSkinSourceLocal(boolean enabled) {
        skinSourceLocal = enabled;
    }

    /**
     * Set the custom-skin override chance at runtime (e.g. from {@code /playermob skin chance <0.0-1.0>}),
     * clamped to 0–1. A session override — not written back to the file, which stays the startup default.
     */
    public static void setCustomSkinChance(float chance) {
        customSkinChance = clamp01(chance);
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
            Properties props = parseProperties(Files.readAllLines(file));
            Values v = parse(props);
            echoFriendChance = v.echoFriendChance();
            reincarnationDifficultyIsolation = v.reincarnationDifficultyIsolation();
            debugSpawnLog = v.debugSpawnLog();
            trainDigThrough = v.trainDigThrough();
            trainFollowLovedPlayer = v.trainFollowLovedPlayer();
            naturalSpawnEnabled = v.naturalSpawnEnabled();
            autoNameMode = v.autoNameMode();
            requireArrows = v.requireArrows();
            seekArrowsWhenEmpty = v.seekArrowsWhenEmpty();
            rangedEngageDistance = v.rangedEngageDistance();
            meleeEngageDistance = v.meleeEngageDistance();
            tntCombat = v.tntCombat();
            endCrystalCombat = v.endCrystalCombat();
            extinguishWithBucket = v.extinguishWithBucket();
            huntForFood = v.huntForFood();
            exactNames = v.exactNames();
            extraPickups = v.extraPickups();
            moddedRanged = v.moddedRanged();
            naturalSpawnScales = v.naturalSpawnScales();
            skinSourceBundled = v.skinSourceBundled();
            skinSourceOnline = v.skinSourceOnline();
            skinSourceLocal = v.skinSourceLocal();
            customSkinChance = v.customSkinChance();
            LOGGER.info("[{}] config: {}={}, {}={}, {}={}, {}={}, {}={} ({} per-mob override(s))",
                PlayerMob.MOD_ID,
                KEY_ECHO_FRIEND_CHANCE, echoFriendChance, KEY_DEBUG_SPAWN_LOG, debugSpawnLog,
                KEY_TRAIN_DIG_THROUGH, trainDigThrough,
                KEY_TRAIN_FOLLOW_LOVED_PLAYER, trainFollowLovedPlayer,
                KEY_NATURAL_SPAWN_ENABLED, naturalSpawnEnabled, naturalSpawnScales.size());
            LOGGER.info("[{}] combat config: {}={}, {}={}, {}={}, {}={}, {}={}, {}={}, {}={}, {}={}",
                PlayerMob.MOD_ID,
                KEY_REQUIRE_ARROWS, requireArrows, KEY_SEEK_ARROWS_WHEN_EMPTY, seekArrowsWhenEmpty,
                KEY_RANGED_ENGAGE_DISTANCE, rangedEngageDistance,
                KEY_MELEE_ENGAGE_DISTANCE, meleeEngageDistance,
                KEY_TNT_COMBAT, tntCombat,
                KEY_END_CRYSTAL_COMBAT, endCrystalCombat,
                KEY_EXTINGUISH_WITH_BUCKET, extinguishWithBucket,
                KEY_HUNT_FOR_FOOD, huntForFood);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[{}] failed to load {}; using defaults", PlayerMob.MOD_ID, FILE, e);
        }
    }

    /** Parsed, validated values — split out (pure, no I/O) so the parsing rules are unit-tested. */
    record Values(float echoFriendChance, boolean reincarnationDifficultyIsolation,
                  boolean debugSpawnLog, boolean trainDigThrough,
                  boolean trainFollowLovedPlayer, boolean naturalSpawnEnabled,
                  AutoNameMode autoNameMode,
                  boolean requireArrows, boolean seekArrowsWhenEmpty,
                  float rangedEngageDistance, float meleeEngageDistance, boolean tntCombat,
                  boolean endCrystalCombat, boolean extinguishWithBucket, boolean huntForFood,
                  boolean exactNames,
                  WantedItemList extraPickups,
                  ModdedRangedWeapons moddedRanged,
                  Map<String, Float> naturalSpawnScales,
                  boolean skinSourceBundled, boolean skinSourceOnline, boolean skinSourceLocal,
                  float customSkinChance) {}

    static Values parse(Properties props) {
        float[] engage = parseEngageDistances(props);
        return new Values(
            clamp01(parseFloat(props.getProperty(KEY_ECHO_FRIEND_CHANCE), DEFAULT_ECHO_FRIEND_CHANCE)),
            parseBool(props.getProperty(KEY_REINCARNATION_DIFFICULTY_ISOLATION),
                DEFAULT_REINCARNATION_DIFFICULTY_ISOLATION),
            parseBool(props.getProperty(KEY_DEBUG_SPAWN_LOG), DEFAULT_DEBUG_SPAWN_LOG),
            parseBool(props.getProperty(KEY_TRAIN_DIG_THROUGH), DEFAULT_TRAIN_DIG_THROUGH),
            parseBool(props.getProperty(KEY_TRAIN_FOLLOW_LOVED_PLAYER), DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER),
            parseBool(props.getProperty(KEY_NATURAL_SPAWN_ENABLED), DEFAULT_NATURAL_SPAWN_ENABLED),
            parseAutoNameMode(props),
            parseBool(props.getProperty(KEY_REQUIRE_ARROWS), DEFAULT_REQUIRE_ARROWS),
            parseBool(props.getProperty(KEY_SEEK_ARROWS_WHEN_EMPTY), DEFAULT_SEEK_ARROWS_WHEN_EMPTY),
            engage[0], engage[1],
            parseBool(props.getProperty(KEY_TNT_COMBAT), DEFAULT_TNT_COMBAT),
            parseBool(props.getProperty(KEY_END_CRYSTAL_COMBAT), DEFAULT_END_CRYSTAL_COMBAT),
            parseBool(props.getProperty(KEY_EXTINGUISH_WITH_BUCKET), DEFAULT_EXTINGUISH_WITH_BUCKET),
            parseBool(props.getProperty(KEY_HUNT_FOR_FOOD), DEFAULT_HUNT_FOR_FOOD),
            parseBool(props.getProperty(KEY_EXACT_NAMES), DEFAULT_EXACT_NAMES),
            WantedItemList.parse(props.getProperty(KEY_EXTRA_PICKUP_ITEMS, DEFAULT_EXTRA_PICKUP_ITEMS)),
            ModdedRangedWeapons.parse(props.getProperty(KEY_MODDED_RANGED_WEAPONS, DEFAULT_MODDED_RANGED_WEAPONS)),
            parseScales(props),
            parseBool(props.getProperty(KEY_SKIN_SOURCE_BUNDLED), DEFAULT_SKIN_SOURCE_BUNDLED),
            parseBool(props.getProperty(KEY_SKIN_SOURCE_ONLINE), DEFAULT_SKIN_SOURCE_ONLINE),
            parseBool(props.getProperty(KEY_SKIN_SOURCE_LOCAL), DEFAULT_SKIN_SOURCE_LOCAL),
            clamp01(parseFloat(props.getProperty(KEY_CUSTOM_SKIN_CHANCE), DEFAULT_CUSTOM_SKIN_CHANCE)));
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
     * Parse the config file body into {@link Properties}, splitting each entry on the <em>first</em>
     * {@code '='} only. This is deliberately NOT {@link Properties#load} — the per-mob keys contain a
     * colon ({@code naturalSpawnScale.minecraft:zombie}), and {@code Properties.load} treats {@code ':'}
     * (and {@code '='}) as a key/value separator, so it would mis-parse every per-mob line into the junk
     * key {@code naturalSpawnScale.minecraft} and silently drop the override. Comments ({@code #}/{@code !})
     * and blank lines are skipped; key and value are trimmed (matching {@code Properties} semantics). The
     * config uses no line-continuations or unicode escapes, so a first-{@code '='} split is sufficient.
     */
    static Properties parseProperties(List<String> lines) {
        Properties props = new Properties();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            if (key.isEmpty()) {
                continue;
            }
            props.setProperty(key, line.substring(eq + 1).trim());
        }
        return props;
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

    /**
     * Resolve the auto-name mode. The {@code autoNameMode} key wins; if it's absent (or blank) the legacy
     * v0.65.0 boolean {@code autoNameNaturalSpawns=true} maps to {@link AutoNameMode#NATURAL}, so an existing
     * config keeps working. An unrecognised {@code autoNameMode} value falls back to the default.
     */
    static AutoNameMode parseAutoNameMode(Properties props) {
        String raw = props.getProperty(KEY_AUTO_NAME_MODE);
        if (raw != null && !raw.isBlank()) {
            return AutoNameMode.fromString(raw, DEFAULT_AUTO_NAME_MODE);
        }
        boolean legacyNatural = parseBool(props.getProperty(KEY_AUTO_NAME_NATURAL_SPAWNS), false);
        return legacyNatural ? AutoNameMode.NATURAL : DEFAULT_AUTO_NAME_MODE;
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
            .append("# tntCombat: when true, a PlayerMob carrying TNT and a way to light it (flint & steel, fire\n")
            .append("#   charge, redstone block, lever, button, or pressure plate) runs up to its target, drops a\n")
            .append("#   TNT block, lights it, and sprints clear — repeating until out of TNT, then reverting to\n")
            .append("#   normal bow/melee combat. Also requires the mobGriefing gamerule (it places + primes real\n")
            .append("#   TNT). Toggle live with /playermob tntcombat on|off (session override). Default true.\n")
            .append("# endCrystalCombat: when true, a PlayerMob carrying end crystals, obsidian (for the crystal's\n")
            .append("#   base), and solid blocks (for cover) bombs its target with end crystals: it seats a crystal on\n")
            .append("#   an obsidian base, stacks a 2-block-tall cover between itself and the crystal, crouches behind\n")
            .append("#   it (raising a shield if it has one), and punches the crystal to detonate it — repeating until\n")
            .append("#   out of the kit, then reverting to normal bow/melee combat. Also requires the mobGriefing\n")
            .append("#   gamerule (it places blocks + triggers real explosions). Toggle live with\n")
            .append("#   /playermob endcrystalcombat on|off (session override). Default true.\n")
            .append("# extinguishWithBucket: when true, a PlayerMob that catches fire while holding a water\n")
            .append("#   bucket empties it onto the ground, jumps in to douse itself, then a short while after\n")
            .append("#   the fire is out scoops the water back into the bucket. Toggle live with\n")
            .append("#   /playermob extinguishwithbucket on|off (session override). Default true.\n")
            .append("# huntForFood: when true, a hungry PlayerMob (no food carried, or hurt and low on carried\n")
            .append("#   nutrition) hunts a nearby adult food animal (cow, pig, chicken, sheep, rabbit) as its\n")
            .append("#   attack target, letting its weapon do the kill, then eats the drops. Set false to leave\n")
            .append("#   animals alone entirely. Toggle live with /playermob huntforfood on|off (session\n")
            .append("#   override). Default true.\n")
            .append("# exactNames: when true, a /playermob subcommand whose <name> argument matches no\n")
            .append("#   loaded PlayerMob is cancelled with an error, instead of falling back to the nearest\n")
            .append("#   PlayerMob. Turn it on when orders are issued by automation (chat bots, command\n")
            .append("#   blocks) and a stale or misspelled name must never command the wrong mob. Toggle live\n")
            .append("#   with /playermob exactnames on|off (session override). Default false.\n")
            .append("# extraPickupItems: extra items the mob ALWAYS picks up (off the floor and out of chests)\n")
            .append("#   and hoards in its backpack, beyond the built-in weapons/armor/ammo/valuables/food it\n")
            .append("#   already grabs. List item ids and/or item tags, separated by commas or spaces. Prefix a\n")
            .append("#   tag with #; a bare path with no namespace assumes minecraft. Empty by default.\n")
            .append("#   Example: extraPickupItems=#scguns:ammo, scguns:assault_rifle, minecraft:diamond_block\n")
            .append("# moddedRangedWeapons: firearms from other mods a PlayerMob should treat as ranged weapons\n")
            .append("#   (not just vanilla bows/crossbows) — it draws them at range, reloads them over time, and\n")
            .append("#   fires them with the mod's own logic (real bullets, real ammo). Comma-separated entries;\n")
            .append("#   each binds a weapon to its ammo with '>', with an optional third field for the reload\n")
            .append("#   time in ticks: weapon>ammo>reloadTicks. Each side is an item id or a #-prefixed item tag\n")
            .append("#   (bare path assumes minecraft), and a side may list several ids/tags separated by spaces\n")
            .append("#   to share one ammo. The mob must carry the ammo to fire (like arrows for a bow). Empty by\n")
            .append("#   default. Example covering every MusketMod firearm with one cartridge binding:\n")
            .append("#   moddedRangedWeapons=musketmod:musket musketmod:musket_with_bayonet musketmod:musket_with_scope musketmod:blunderbuss musketmod:pistol>musketmod:cartridge\n")
            .append("# skinSourceBundled / skinSourceOnline / skinSourceLocal: which skin sources a random\n")
            .append("#   PlayerMob may draw from. bundled = the built-in default skins; online = datapack /\n")
            .append("#   by-name / external-mod skins; local = PNG files you drop in config/playermob/skins.\n")
            .append("#   All on by default. Bundled is always the fallback when no other source has any skin.\n")
            .append("#   Toggle live with /playermob skin source <bundled|online|local> on|off (session override).\n")
            .append("# customSkinChance: chance (0.0-1.0) a spawning PlayerMob overrides its bundled (vanilla\n")
            .append("#   default) base look with a custom skin drawn from the online + local pool. 0.0 = always\n")
            .append("#   a bundled default; 1.0 = always custom when a custom skin is available (a mob with no\n")
            .append("#   custom skin available keeps its bundled look regardless). Toggle live with\n")
            .append("#   /playermob skin chance <0.0-1.0> (session override). Default 0.4.\n")
            .append("# reincarnationDifficultyIsolation: when true (default), a past life is only ever\n")
            .append("#   reincarnated on the vanilla difficulty it was lived on — echoes of a Hard run stay in\n")
            .append("#   Hard, Peaceful in Peaceful. Lives logged before this existed count as normal. Set false\n")
            .append("#   to pool every difficulty together, as older builds did.\n")
            .append(KEY_ECHO_FRIEND_CHANCE).append("=").append(DEFAULT_ECHO_FRIEND_CHANCE).append("\n")
            .append(KEY_REINCARNATION_DIFFICULTY_ISOLATION).append("=")
            .append(DEFAULT_REINCARNATION_DIFFICULTY_ISOLATION).append("\n")
            .append(KEY_DEBUG_SPAWN_LOG).append("=").append(DEFAULT_DEBUG_SPAWN_LOG).append("\n")
            .append(KEY_TRAIN_DIG_THROUGH).append("=").append(DEFAULT_TRAIN_DIG_THROUGH).append("\n")
            .append(KEY_TRAIN_FOLLOW_LOVED_PLAYER).append("=").append(DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER).append("\n")
            .append(KEY_REQUIRE_ARROWS).append("=").append(DEFAULT_REQUIRE_ARROWS).append("\n")
            .append(KEY_SEEK_ARROWS_WHEN_EMPTY).append("=").append(DEFAULT_SEEK_ARROWS_WHEN_EMPTY).append("\n")
            .append(KEY_RANGED_ENGAGE_DISTANCE).append("=").append(DEFAULT_RANGED_ENGAGE_DISTANCE).append("\n")
            .append(KEY_MELEE_ENGAGE_DISTANCE).append("=").append(DEFAULT_MELEE_ENGAGE_DISTANCE).append("\n")
            .append(KEY_TNT_COMBAT).append("=").append(DEFAULT_TNT_COMBAT).append("\n")
            .append(KEY_END_CRYSTAL_COMBAT).append("=").append(DEFAULT_END_CRYSTAL_COMBAT).append("\n")
            .append(KEY_EXTINGUISH_WITH_BUCKET).append("=").append(DEFAULT_EXTINGUISH_WITH_BUCKET).append("\n")
            .append(KEY_HUNT_FOR_FOOD).append("=").append(DEFAULT_HUNT_FOR_FOOD).append("\n")
            .append(KEY_EXACT_NAMES).append("=").append(DEFAULT_EXACT_NAMES).append("\n")
            .append(KEY_EXTRA_PICKUP_ITEMS).append("=").append(DEFAULT_EXTRA_PICKUP_ITEMS).append("\n")
            .append(KEY_MODDED_RANGED_WEAPONS).append("=").append(DEFAULT_MODDED_RANGED_WEAPONS).append("\n")
            .append(KEY_SKIN_SOURCE_BUNDLED).append("=").append(DEFAULT_SKIN_SOURCE_BUNDLED).append("\n")
            .append(KEY_SKIN_SOURCE_ONLINE).append("=").append(DEFAULT_SKIN_SOURCE_ONLINE).append("\n")
            .append(KEY_SKIN_SOURCE_LOCAL).append("=").append(DEFAULT_SKIN_SOURCE_LOCAL).append("\n")
            .append(KEY_CUSTOM_SKIN_CHANCE).append("=").append(DEFAULT_CUSTOM_SKIN_CHANCE).append("\n")
            .append("#\n")
            .append("# --- Natural spawning (opt-in) ------------------------------------------------\n")
            .append("# naturalSpawnEnabled: master switch. When false (default), PlayerMobs only appear\n")
            .append("#   via spawn egg, /summon, or Dungeon Train — never naturally. Set true to let the\n")
            .append("#   per-mob chances below take effect.\n")
            .append("#\n")
            .append("# autoNameMode: which spawns get a nameplate from their skin source — the local-folder\n")
            .append("#   filename (no extension) for a local skin, or the displayName for an online / registry\n")
            .append("#   skin (a bundled default skin has no source name and stays unnamed). One of:\n")
            .append("#     off     — never (default)\n")
            .append("#     natural — only natural / chunk-generation spawns\n")
            .append("#     egg     — only spawn eggs, mob spawners, /summon, and dispensers\n")
            .append("#     all     — every spawn except Dungeon-Train events (which keep their own naming)\n")
            .append("#   /playermob summon <name|file> labels with that name once its skin loads (unless 'named'\n")
            .append("#   was given, which always wins). Toggle live with /playermob autoname <off|natural|egg|all>\n")
            .append("#   (session override).\n")
            .append("#\n")
            .append("# naturalSpawnScale.<id>: chance (0.0-1.0) that, when that mob spawns naturally, a\n")
            .append("#   PlayerMob ALSO spawns beside it (additive — the mob is NOT replaced). 0.0 = never;\n")
            .append("#   1.0 = always. Each line below defaults to its group's chance:\n")
            .append("#     Hostile 0.0   Nether 0.05   Animals 0.15   Friendly 0.15   Water 0.0   Villager 0.25\n")
            .append("#   Edit individual lines, or set a whole group live with\n")
            .append("#   /playermob naturalspawn group <group> <chance>. Delete a line to fall back to its\n")
            .append("#   group default. Only takes effect while naturalSpawnEnabled=true.\n")
            .append(KEY_NATURAL_SPAWN_ENABLED).append("=").append(DEFAULT_NATURAL_SPAWN_ENABLED).append("\n")
            .append(KEY_AUTO_NAME_MODE).append("=").append(DEFAULT_AUTO_NAME_MODE.token()).append("\n");
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
