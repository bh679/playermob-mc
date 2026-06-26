package games.brennan.playermob;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.compat.ReincarnationSources;
import games.brennan.playermob.player.GlobalLifeReincarnationSource;
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
     * <p>Loads {@link PlayerMobConfig} from {@code configDir} and registers PlayerMob's own death log
     * as the built-in {@link ReincarnationSources reincarnation source}, then logs — registration of
     * the entity types lives per-loader (see {@link PlayerMobRegistry} for the rationale).</p>
     *
     * @param configDir the loader's config directory (Fabric {@code getConfigDir()},
     *     Forge/NeoForge {@code FMLPaths.CONFIGDIR}); {@code playermob.properties} is read from it.
     */
    public static void init(Path configDir) {
        PlayerMobConfig.load(configDir);
        // The local-skin drop folder (config/playermob/skins) — created if absent. Resolved on each
        // physical side: the server lists names to pick from, the client loads the PNGs.
        games.brennan.playermob.skin.LocalSkinFolder.init(configDir);
        // The local death log is always a source; integrating mods register their own on top.
        ReincarnationSources.register(new GlobalLifeReincarnationSource());
        LOGGER.info("[{}] common init — entity={}, spawn egg={}",
                    MOD_ID,
                    PlayerMobRegistry.PLAYER_MOB,
                    PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG);
    }
}
