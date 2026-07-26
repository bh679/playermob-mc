package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Optional-mod integration seam deciding whether a player's death should be <em>captured</em>
 * into the reincarnation pool (the local death log an "echo" can later be reincarnated from).
 *
 * <p>Mirrors {@link PlayerMobSpawnHooks}: the predicate defaults to allow-all and is replaced
 * exactly once, at boot, by a consuming mod (from inside its {@code ModList.isLoaded("playermob")}
 * guard). PlayerMob <em>owns</em> the capture; consumers veto it here rather than mixing into
 * PlayerMob internals, so the integration survives changes to the reincarnation implementation.
 * When no consumer is installed every death is captured, exactly as before this seam existed.</p>
 *
 * <p>Used by Dungeon Train to exclude "Free Play" (cheated / sandbox) deaths from the echo pool,
 * so a consequence-free run never spawns reincarnated echoes of itself — while still capturing on
 * dev builds. The predicate only gates whether the death is <em>recorded</em>; PlayerMob always
 * runs its own per-life reset either way (see {@code PlayerReincarnation.onDeath}).</p>
 *
 * <p>Server-thread only: queried from {@code PlayerReincarnation.onDeath} at the moment of death.
 * Fail-open — any exception the predicate throws is logged and treated as "capture" so a buggy
 * consumer can never silently drop legitimate echoes.</p>
 */
public final class ReincarnationCaptureGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A consumer's veto over reincarnation capture. */
    public interface CapturePredicate {
        /**
         * @param player the player who just died (inventory/gear still intact)
         * @return {@code true} to capture this death into the reincarnation pool; {@code false}
         *         to skip capture (no echo will ever be reincarnated from this death)
         */
        default boolean shouldCapture(ServerPlayer player) {
            return true;
        }
    }

    private static volatile CapturePredicate gate = new CapturePredicate() {};

    private ReincarnationCaptureGate() {}

    /**
     * Install the active predicate. Called once during loader boot by a consuming mod; never called
     * on runs without such a mod, so the holder stays the allow-all default.
     */
    public static void install(CapturePredicate newGate) {
        gate = newGate;
    }

    /**
     * Whether {@code player}'s death should be captured. Best-effort: any exception the predicate
     * throws is logged and treated as {@code true}, so a consumer fault never drops a legit echo.
     */
    public static boolean shouldCapture(ServerPlayer player) {
        try {
            return gate.shouldCapture(player);
        } catch (Throwable t) {
            LOGGER.warn("[playermob] reincarnation-capture predicate threw; capturing anyway", t);
            return true;
        }
    }
}
