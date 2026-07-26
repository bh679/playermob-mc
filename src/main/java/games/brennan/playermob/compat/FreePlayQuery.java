package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Optional-mod integration seam answering "is this player in a Free Play (sandbox / cheated)
 * run, and should reincarnation echoes be provenance-matched?".
 *
 * <p>Mirrors {@link ReincarnationCaptureGate} / {@link PlayerMobSpawnHooks}: a {@code volatile}
 * holder defaulting to a no-op, replaced exactly once at boot by a consuming mod from inside its
 * {@code ModList.isLoaded("playermob")} guard. PlayerMob owns capture + spawn; it asks this seam
 * rather than referencing the consumer, so the integration survives implementation changes. With
 * no consumer installed every player reads as "legit" and matching is enforced — i.e. behaviour is
 * identical to a build with no Free Play concept at all.</p>
 *
 * <p>Used by Dungeon Train to tag each captured life with the dier's Free Play state and, at spawn,
 * to spawn a life's echo only for a player whose Free Play state matches the life's — so Free Play
 * ("creative") echoes stay quarantined to Free Play runs and never bleed into legit ones (and vice
 * versa). {@link #enforceMatch()} lets DT disable the match on dev builds so a developer sees every
 * echo while testing.</p>
 *
 * <p>Server-thread only. Fail-open: any exception from the consumer is logged and treated as
 * "legit" / "enforce", so a fault never silently hides legitimate echoes.</p>
 */
public final class FreePlayQuery {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * NBT key under which a captured life's Free Play provenance is stamped inside its snapshot.
     * Lives in the snapshot (not a separate column) so it survives the relay round-trip for remote
     * echoes verbatim. Absent → {@code false} (legit), so pre-provenance records read as legit.
     */
    public static final String SNAPSHOT_TAG = "dt_free_play";

    /** A consumer answering Free Play questions about a player + run. */
    public interface Provider {
        /**
         * @param player the player being captured (at death) or spawned near (at reincarnation)
         * @return {@code true} if this player's run is Free Play (sandbox / cheated); {@code false}
         *         for a legit run. Default {@code false} = legit.
         */
        default boolean isFreePlay(ServerPlayer player) {
            return false;
        }

        /**
         * @return {@code true} to enforce provenance matching at spawn (Free Play echoes only for
         *         Free Play players, legit only for legit); {@code false} to spawn every echo
         *         regardless of provenance (e.g. dev builds). Default {@code true}.
         */
        default boolean enforceMatch() {
            return true;
        }
    }

    private static volatile Provider provider = new Provider() {};

    private FreePlayQuery() {}

    /**
     * Install the active provider. Called once during loader boot by a consuming mod; never called
     * on runs without such a mod, so the holder stays the no-op default.
     */
    public static void install(Provider newProvider) {
        provider = newProvider;
    }

    /** Whether {@code player}'s run is Free Play. Fail-open to {@code false} (legit) on error. */
    public static boolean isFreePlay(ServerPlayer player) {
        try {
            return provider.isFreePlay(player);
        } catch (Throwable t) {
            LOGGER.warn("[playermob] Free Play provider threw; treating as legit", t);
            return false;
        }
    }

    /** Whether provenance matching is enforced at spawn. Fail-open to {@code true} on error. */
    public static boolean enforceMatch() {
        try {
            return provider.enforceMatch();
        } catch (Throwable t) {
            LOGGER.warn("[playermob] Free Play provider threw on enforceMatch; enforcing", t);
            return true;
        }
    }
}
