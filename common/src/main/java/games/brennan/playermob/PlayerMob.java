package games.brennan.playermob;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Common-side post-registration hook. Loader-specific entries
 * ({@code PlayerMobFabric}, {@code PlayerMobForge}, {@code PlayerMobNeoForge})
 * each call {@link #init()} after they've registered their entity type +
 * spawn egg + attributes and back-filled the static references on
 * {@link PlayerMobRegistry}.
 *
 * <p>Registration itself does NOT live here — Architectury's API runtime
 * dropped Forge in the 1.21 line, so registration is performed per-loader
 * using each loader's native API surface (see {@link PlayerMobRegistry} for
 * the rationale). This class is just the shared post-init landing point.</p>
 */
public final class PlayerMob {

    public static final String MOD_ID = "playermob";

    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerMob() {}

    /**
     * Called once per JVM from each loader entry point AFTER that loader has
     * populated {@link PlayerMobRegistry#PLAYER_MOB} and
     * {@link PlayerMobRegistry#PLAYER_MOB_SPAWN_EGG}. Any common-side wiring
     * that depends on the registered types belongs here.
     *
     * <p>Loads {@link PlayerMobConfig} from {@code configDir} (the only common wiring so far),
     * then logs — registration lives per-loader (see {@link PlayerMobRegistry} for the rationale).</p>
     *
     * @param configDir the loader's config directory (Fabric {@code getConfigDir()},
     *     Forge/NeoForge {@code FMLPaths.CONFIGDIR}); {@code playermob.properties} is read from it.
     */
    public static void init(Path configDir) {
        PlayerMobConfig.load(configDir);
        LOGGER.info("[{}] common init — entity={}, spawn egg={}",
                    MOD_ID,
                    PlayerMobRegistry.PLAYER_MOB,
                    PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG);
    }
}
