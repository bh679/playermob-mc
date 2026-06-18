package games.brennan.playermob;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link PlayerMobConfig#parse} — the defaults / clamp / lenient-parse rules.
 * No filesystem (the read/write-default path is exercised in-game).
 */
class PlayerMobConfigTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    void missingKeysUseDefaults() {
        var v = PlayerMobConfig.parse(new Properties());
        assertEquals(PlayerMobConfig.DEFAULT_ECHO_FRIEND_CHANCE, v.echoFriendChance(), 1e-6);
        assertEquals(PlayerMobConfig.DEFAULT_DEBUG_SPAWN_LOG, v.debugSpawnLog());
        assertEquals(PlayerMobConfig.DEFAULT_TRAIN_DIG_THROUGH, v.trainDigThrough());
        assertEquals(PlayerMobConfig.DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER, v.trainFollowLovedPlayer());
    }

    @Test
    void trainDigThroughParsedAndDefaultsOn() {
        assertTrue(PlayerMobConfig.DEFAULT_TRAIN_DIG_THROUGH, "ships on by default");
        assertFalse(PlayerMobConfig.parse(props("trainDigThrough", "false")).trainDigThrough());
        assertTrue(PlayerMobConfig.parse(props("trainDigThrough", "true")).trainDigThrough());
        // Unrecognised value → default (true), like the other boolean key.
        assertTrue(PlayerMobConfig.parse(props("trainDigThrough", "maybe")).trainDigThrough());
    }

    @Test
    void trainFollowLovedPlayerParsedAndDefaultsOn() {
        assertTrue(PlayerMobConfig.DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER, "ships on by default");
        assertFalse(PlayerMobConfig.parse(props("trainFollowLovedPlayer", "false")).trainFollowLovedPlayer());
        assertTrue(PlayerMobConfig.parse(props("trainFollowLovedPlayer", "true")).trainFollowLovedPlayer());
        // Unrecognised value → default (true), like the other boolean key.
        assertTrue(PlayerMobConfig.parse(props("trainFollowLovedPlayer", "maybe")).trainFollowLovedPlayer());
    }

    @Test
    void validValuesParsed() {
        var v = PlayerMobConfig.parse(props("echoFriendChance", "0.4", "debugSpawnLog", "true"));
        assertEquals(0.4F, v.echoFriendChance(), 1e-6);
        assertTrue(v.debugSpawnLog());
    }

    @Test
    void chanceClampedToUnitRange() {
        assertEquals(1.0F, PlayerMobConfig.parse(props("echoFriendChance", "5")).echoFriendChance(), 1e-6);
        assertEquals(0.0F, PlayerMobConfig.parse(props("echoFriendChance", "-2")).echoFriendChance(), 1e-6);
    }

    @Test
    void invalidValuesFallBackToDefaults() {
        var v = PlayerMobConfig.parse(props("echoFriendChance", "abc", "debugSpawnLog", "yes"));
        assertEquals(PlayerMobConfig.DEFAULT_ECHO_FRIEND_CHANCE, v.echoFriendChance(), 1e-6);
        assertFalse(v.debugSpawnLog()); // "yes" is neither true nor false -> default (false)
    }

    @Test
    void setDebugSpawnLogTogglesAtRuntime() {
        try {
            PlayerMobConfig.setDebugSpawnLog(true);
            assertTrue(PlayerMobConfig.debugSpawnLog());
            PlayerMobConfig.setDebugSpawnLog(false);
            assertFalse(PlayerMobConfig.debugSpawnLog());
        } finally {
            PlayerMobConfig.setDebugSpawnLog(PlayerMobConfig.DEFAULT_DEBUG_SPAWN_LOG); // don't leak into other tests
        }
    }
}
