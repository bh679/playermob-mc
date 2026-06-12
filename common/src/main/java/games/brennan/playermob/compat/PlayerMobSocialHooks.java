package games.brennan.playermob.compat;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Optional-mod integration seam for gift exchanges between a PlayerMob and a player —
 * both directions, used by Dungeon Train's "befriended" advancements.
 *
 * <p>Mirrors the {@link TrainConfinement#install} backfill pattern: the observer defaults
 * to a no-op and is replaced exactly once, at boot, by a consuming mod. PlayerMob
 * <em>owns and announces</em> these events (it fires them from its own gift / pickup
 * actions); consumers subscribe here rather than mixing into PlayerMob internals — so the
 * integration doesn't break when PlayerMob's gift implementation changes. When no consumer
 * is installed (every loader without such a mod) the calls are zero-cost no-ops.</p>
 *
 * <p>Server-thread only: fired from the gift/pickup actions, which run server-side.</p>
 */
public final class PlayerMobSocialHooks {

    /**
     * A consumer of PlayerMob gift events. Both methods default to no-ops, so an observer
     * can implement only the direction it cares about.
     */
    public interface GiftObserver {
        /** A PlayerMob ({@code mobId}) gave a gift to {@code recipient}. */
        default void onMobGift(ServerPlayer recipient, UUID mobId) {}

        /** {@code giver} gave a gift to a PlayerMob ({@code mobId}). */
        default void onPlayerGift(ServerPlayer giver, UUID mobId) {}
    }

    private static volatile GiftObserver observer = new GiftObserver() {};

    private PlayerMobSocialHooks() {}

    /**
     * Install the active observer. Called once during loader boot by a consuming mod
     * (e.g. from inside its {@code ModList.isLoaded("playermob")} guard); never called on
     * runs without such a mod, so the holder stays the no-op default.
     */
    public static void install(GiftObserver newObserver) {
        observer = newObserver;
    }

    /** Announce that {@code mobId} gave {@code recipient} a gift. Fired from {@code PlayerMobEntity.tossGift}. */
    public static void onMobGift(ServerPlayer recipient, UUID mobId) {
        observer.onMobGift(recipient, mobId);
    }

    /** Announce that {@code giver} gave a gift to {@code mobId}. Fired when a PlayerMob accepts a player's tossed item. */
    public static void onPlayerGift(ServerPlayer giver, UUID mobId) {
        observer.onPlayerGift(giver, mobId);
    }
}
