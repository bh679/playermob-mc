package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.entity.PlayerMobEntity;
import org.slf4j.Logger;

/**
 * Optional-mod integration seam announcing that a PlayerMob has spawned as a reincarnation
 * (an "echo") of a stored past life — used by Dungeon Train's remote-echo encounter journal.
 *
 * <p>Mirrors {@link PlayerMobSocialHooks}: the observer defaults to a no-op and is replaced
 * exactly once, at boot, by a consuming mod (from inside its {@code ModList.isLoaded("playermob")}
 * guard). PlayerMob <em>owns and announces</em> the spawn; consumers subscribe here rather than
 * mixing into PlayerMob internals, so the integration survives changes to the reincarnation
 * implementation. When no consumer is installed the call is a zero-cost no-op.</p>
 *
 * <p>The {@code remote} flag tells the consumer which pool the life was drawn from:
 * {@code false} = PlayerMob's own local death log (self allowed); {@code true} = a remote pool
 * supplied by an integrating mod (cross-world; never the live player themselves). A consumer that
 * only cares about cross-world "remote echoes" filters on {@code remote == true}.</p>
 *
 * <p>Server-thread only: fired from {@code PlayerReincarnation.maybeReincarnateOnSpawn}, which runs
 * inside {@code PlayerMobEntity.finalizeSpawn}. The dispatcher swallows observer exceptions so a
 * buggy consumer can never disrupt the spawn flow (the echo is already materialised when it fires).</p>
 */
public final class PlayerMobSpawnHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A consumer of PlayerMob echo-spawn events. */
    public interface SpawnObserver {
        /**
         * A PlayerMob has spawned as an echo of {@code record}.
         *
         * @param mob    the freshly-materialised echo (named "Echo of X", skin/gear/traits applied)
         * @param record the embodied past life — {@code record.name()} / {@code record.playerId()}
         *               identify "who it was"
         * @param remote {@code true} when drawn from a remote cross-world pool (never the live
         *               player); {@code false} for a local death-log reincarnation
         */
        default void onEchoSpawned(PlayerMobEntity mob, ReincarnationRecord record, boolean remote) {}
    }

    private static volatile SpawnObserver observer = new SpawnObserver() {};

    private PlayerMobSpawnHooks() {}

    /**
     * Install the active observer. Called once during loader boot by a consuming mod; never called
     * on runs without such a mod, so the holder stays the no-op default.
     */
    public static void install(SpawnObserver newObserver) {
        observer = newObserver;
    }

    /**
     * Announce that {@code mob} spawned as an echo of {@code record}. Best-effort: any exception the
     * observer throws is logged and swallowed, so a consumer fault never breaks the spawn.
     */
    public static void onEchoSpawned(PlayerMobEntity mob, ReincarnationRecord record, boolean remote) {
        try {
            observer.onEchoSpawned(mob, record, remote);
        } catch (Throwable t) {
            LOGGER.warn("[playermob] echo-spawn observer threw; ignoring", t);
        }
    }
}
