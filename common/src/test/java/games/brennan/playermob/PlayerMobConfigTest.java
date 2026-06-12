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
}
