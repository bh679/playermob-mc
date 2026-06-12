package games.brennan.playermob.compat;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Optional-mod integration seam for social interactions a PlayerMob initiates toward a
 * player. Today it carries one signal — a PlayerMob gave a player a gift — used by
 * Dungeon Train's "befriended" advancement.
 *
 * <p>Mirrors the {@link TrainConfinement#install} backfill pattern: the observer defaults
 * to a no-op and is replaced exactly once, at boot, by a consuming mod. PlayerMob
 * <em>owns and announces</em> the event (it fires {@link #onMobGift} from its own gift
 * action); consumers subscribe here rather than mixing into PlayerMob internals — so the
 * integration doesn't break when PlayerMob's gift implementation changes. When no consumer
 * is present (every loader without such a mod) the call is a zero-cost no-op.</p>
 *
 * <p>Server-thread only: fired from the gift action, which runs server-side.</p>
 */
public final class PlayerMobSocialHooks {

    /** Notified when a PlayerMob successfully tosses a gift to a player. */
    @FunctionalInterface
    public interface GiftObserver {
        /**
         * @param recipient the player the gift was thrown to
         * @param mobId     the UUID of the PlayerMob that gave it
         */
        void onMobGift(ServerPlayer recipient, UUID mobId);
    }

    private static volatile GiftObserver observer = (recipient, mobId) -> {};

    private PlayerMobSocialHooks() {}

    /**
     * Install the active gift observer. Called once during loader boot by a consuming mod
     * (e.g. from inside its {@code ModList.isLoaded("playermob")} guard); never called on
     * runs without such a mod, so the holder stays the no-op default.
     */
    public static void install(GiftObserver newObserver) {
        observer = newObserver;
    }

    /**
     * Announce that {@code mobId} gave {@code recipient} a gift. A no-op without an
     * installed observer. Called from {@code PlayerMobEntity.tossGift} when the target is a
     * player.
     */
    public static void onMobGift(ServerPlayer recipient, UUID mobId) {
        observer.onMobGift(recipient, mobId);
    }
}
