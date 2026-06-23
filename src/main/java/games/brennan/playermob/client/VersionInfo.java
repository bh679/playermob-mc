package games.brennan.playermob.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.Properties;

/**
 * Shared holder for the mod version + git branch baked into the jar at build
 * time by the {@code generateVersionProperties} task (see {@code common/build.gradle}).
 * Loaded once on class init and reused by {@link VersionHudRenderer}.
 *
 * <p>The properties file lives in {@code common}'s generated resources, so the
 * single baked copy ships in all three loader jars. The {@code branch} value is
 * the dev-vs-release signal — see {@link VersionHudRenderer#shouldDisplay(String)}.</p>
 *
 * <p>Mirrors {@code DungeonTrainCraft}'s {@code VersionInfo}.</p>
 */
@Environment(EnvType.CLIENT)
public final class VersionInfo {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROPERTIES_PATH = "/playermob_version.properties";
    private static final String UNKNOWN = "?";

    public static final String VERSION;
    public static final String BRANCH;
    public static final String DISPLAY;

    static {
        String version = UNKNOWN;
        String branch = UNKNOWN;
        try (InputStream in = VersionInfo.class.getResourceAsStream(PROPERTIES_PATH)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version", UNKNOWN);
                branch = props.getProperty("branch", UNKNOWN);
            } else {
                LOGGER.warn("VersionInfo: resource {} not found — using fallback", PROPERTIES_PATH);
            }
        } catch (Exception e) {
            LOGGER.warn("VersionInfo: failed to load {} — using fallback", PROPERTIES_PATH, e);
        }
        VERSION = version;
        BRANCH = branch;
        DISPLAY = "PlayerMob v" + VERSION + " (" + BRANCH + ")";
    }

    private VersionInfo() {}
}
