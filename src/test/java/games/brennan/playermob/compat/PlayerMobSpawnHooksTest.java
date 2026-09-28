package games.brennan.playermob.compat;

import games.brennan.playermob.entity.PlayerMobEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dispatch tests for the {@link PlayerMobSpawnHooks} companion-spawn seam. The dispatcher is a pure
 * pass-through, so {@code null} mobs stand in for real entities (no Minecraft bootstrap needed). The
 * live call from {@code PlayerMobEntity#spawnRandomFriend} is covered by the in-game Gate 2 test.
 */
class PlayerMobSpawnHooksTest {

    /** Reset the global holder so one test's install never leaks into the next. */
    @AfterEach
    void resetObserver() {
        PlayerMobSpawnHooks.install(new PlayerMobSpawnHooks.SpawnObserver() {});
    }

    @Test
    void defaultObserverIgnoresCompanionSpawn() {
        assertDoesNotThrow(() -> PlayerMobSpawnHooks.onCompanionSpawned(null, null));
    }

    @Test
    void installedObserverReceivesCompanionSpawn() {
        AtomicInteger calls = new AtomicInteger();
        PlayerMobSpawnHooks.install(new PlayerMobSpawnHooks.SpawnObserver() {
            @Override
            public void onCompanionSpawned(PlayerMobEntity companion, PlayerMobEntity leader) {
                calls.incrementAndGet();
            }
        });
        PlayerMobSpawnHooks.onCompanionSpawned(null, null);
        assertEquals(1, calls.get(), "observer should be called exactly once per companion");
    }

    @Test
    void echoOnlyObserverIgnoresCompanionSpawn() {
        AtomicInteger echoCalls = new AtomicInteger();
        PlayerMobSpawnHooks.install(new PlayerMobSpawnHooks.SpawnObserver() {
            @Override
            public void onEchoSpawned(PlayerMobEntity mob, ReincarnationRecord record, boolean remote) {
                echoCalls.incrementAndGet();
            }
        });
        assertDoesNotThrow(() -> PlayerMobSpawnHooks.onCompanionSpawned(null, null));
        assertEquals(0, echoCalls.get(), "a companion spawn must not be reported as an echo");
    }

    @Test
    void throwingObserverIsSwallowed() {
        PlayerMobSpawnHooks.install(new PlayerMobSpawnHooks.SpawnObserver() {
            @Override
            public void onCompanionSpawned(PlayerMobEntity companion, PlayerMobEntity leader) {
                throw new IllegalStateException("consumer fault");
            }
        });
        assertDoesNotThrow(() -> PlayerMobSpawnHooks.onCompanionSpawned(null, null));
    }
}
