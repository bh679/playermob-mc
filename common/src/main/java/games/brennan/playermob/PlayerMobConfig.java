package games.brennan.playermob;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Default chance a Dungeon-Train echo with logged friends also brings back a friend-echo. */
    public static final float DEFAULT_ECHO_FRIEND_CHANCE = 0.40F;
    /** Debug spawn logging ships off — when on it broadcasts a chat line on every DT auto-spawn. */
    public static final boolean DEFAULT_DEBUG_SPAWN_LOG = false;
    /** PlayerMobs dig through fill (ice/dirt/mud/moss/logs) blocking a Dungeon-Train carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_DIG_THROUGH = true;
    /** On a Dungeon Train, a PlayerMob that loves a player aboard heads to that player's carriage; on by default. */
    public static final boolean DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER = true;

    private static volatile float echoFriendChance = DEFAULT_ECHO_FRIEND_CHANCE;
    private static volatile boolean debugSpawnLog = DEFAULT_DEBUG_SPAWN_LOG;
    private static volatile boolean trainDigThrough = DEFAULT_TRAIN_DIG_THROUGH;
    private static volatile boolean trainFollowLovedPlayer = DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER;

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
     * Toggle the DT-spawn debug log at runtime (e.g. from {@code /playermob debug spawnlog}). A session
     * override — not written back to the file, which stays the startup default.
     */
    public static void setDebugSpawnLog(boolean enabled) {
        debugSpawnLog = enabled;
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
            LOGGER.info("[{}] config: {}={}, {}={}, {}={}, {}={}", PlayerMob.MOD_ID,
                KEY_ECHO_FRIEND_CHANCE, echoFriendChance, KEY_DEBUG_SPAWN_LOG, debugSpawnLog,
                KEY_TRAIN_DIG_THROUGH, trainDigThrough,
                KEY_TRAIN_FOLLOW_LOVED_PLAYER, trainFollowLovedPlayer);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[{}] failed to load {}; using defaults", PlayerMob.MOD_ID, FILE, e);
        }
    }

    /** Parsed, validated values — split out (pure, no I/O) so the parsing rules are unit-tested. */
    record Values(float echoFriendChance, boolean debugSpawnLog, boolean trainDigThrough,
                  boolean trainFollowLovedPlayer) {}

    static Values parse(Properties props) {
        return new Values(
            clamp01(parseFloat(props.getProperty(KEY_ECHO_FRIEND_CHANCE), DEFAULT_ECHO_FRIEND_CHANCE)),
            parseBool(props.getProperty(KEY_DEBUG_SPAWN_LOG), DEFAULT_DEBUG_SPAWN_LOG),
            parseBool(props.getProperty(KEY_TRAIN_DIG_THROUGH), DEFAULT_TRAIN_DIG_THROUGH),
            parseBool(props.getProperty(KEY_TRAIN_FOLLOW_LOVED_PLAYER), DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER));
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
        String body =
            "# PlayerMob configuration.\n"
            + "#\n"
            + "# echoFriendChance: chance (0.0-1.0) that a Dungeon-Train echo which had logged\n"
            + "#   friends also brings back a friend-echo of one of them. Default 0.4.\n"
            + "# debugSpawnLog: when true, broadcasts a colour-coded chat message (and logs a line)\n"
            + "#   on every Dungeon-Train PlayerMob auto-spawn — yellow=friend, purple=echo,\n"
            + "#   green=echo+friend. Default false.\n"
            + "# trainDigThrough: when true, a PlayerMob riding a Dungeon Train mines soft fill\n"
            + "#   (ice, dirt, mud, moss, logs) blocking its march once it's stuck against it, to\n"
            + "#   pass through a packed carriage. Default true.\n"
            + "# trainFollowLovedPlayer: when true, a PlayerMob on a Dungeon Train that loves a player\n"
            + "#   aboard the same train abandons its fixed march to head to that player's carriage,\n"
            + "#   idling once it's in the same carriage. Default true.\n"
            + KEY_ECHO_FRIEND_CHANCE + "=" + DEFAULT_ECHO_FRIEND_CHANCE + "\n"
            + KEY_DEBUG_SPAWN_LOG + "=" + DEFAULT_DEBUG_SPAWN_LOG + "\n"
            + KEY_TRAIN_DIG_THROUGH + "=" + DEFAULT_TRAIN_DIG_THROUGH + "\n"
            + KEY_TRAIN_FOLLOW_LOVED_PLAYER + "=" + DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER + "\n";
        Files.writeString(file, body);
    }
}
