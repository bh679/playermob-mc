package games.brennan.playermob;

import org.junit.jupiter.api.Test;

import java.util.Map;
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
    void naturalSpawnDefaultsOffWithDefaultScale() {
        var v = PlayerMobConfig.parse(new Properties());
        assertFalse(v.naturalSpawnEnabled(), "natural spawning ships off");
        assertFalse(PlayerMobConfig.DEFAULT_NATURAL_SPAWN_ENABLED);
        assertEquals(0.8F, v.naturalSpawnDefaultScale(), 1e-6);
        assertTrue(v.naturalSpawnScales().isEmpty(), "no per-mob overrides parsed from an empty file");
    }

    @Test
    void villageCompanionChanceDefaultsAndClamps() {
        assertEquals(0.25F, PlayerMobConfig.parse(new Properties()).villageCompanionChance(), 1e-6);
        assertEquals(0.25F, PlayerMobConfig.DEFAULT_VILLAGE_COMPANION_CHANCE, 1e-6);
        assertEquals(0.5F, PlayerMobConfig.parse(props("villageCompanionChance", "0.5")).villageCompanionChance(), 1e-6);
        assertEquals(1.0F, PlayerMobConfig.parse(props("villageCompanionChance", "3")).villageCompanionChance(), 1e-6);
        assertEquals(0.0F, PlayerMobConfig.parse(props("villageCompanionChance", "-1")).villageCompanionChance(), 1e-6);
        // unparseable -> default
        assertEquals(0.25F, PlayerMobConfig.parse(props("villageCompanionChance", "x")).villageCompanionChance(), 1e-6);
    }

    @Test
    void waterMobsDefaultToZeroLandMobsToDefaultScale() {
        // listed land mob -> default scale; listed water mob -> 0; unlisted -> 0
        assertEquals(0.8F, PlayerMobConfig.resolveScale(true, 0.8F, Map.of(), "minecraft:zombie"), 1e-6);
        assertEquals(0.0F, PlayerMobConfig.resolveScale(true, 0.8F, Map.of(), "minecraft:cod"), 1e-6);
        assertEquals(0.0F, PlayerMobConfig.resolveScale(true, 0.8F, Map.of(), "minecraft:guardian"), 1e-6);
        // an explicit override still wins for a water mob
        assertEquals(0.3F, PlayerMobConfig.resolveScale(true, 0.8F,
            Map.of("minecraft:cod", 0.3F), "minecraft:cod"), 1e-6);
        assertTrue(PlayerMobConfig.WATER_SPAWN_MOBS.stream().allMatch(PlayerMobConfig.NATURAL_SPAWN_MOBS::contains),
            "every water mob is also in the natural-spawn list");
    }

    @Test
    void naturalSpawnScaleOverridesParsedAndClamped() {
        var v = PlayerMobConfig.parse(props(
            "naturalSpawnEnabled", "true",
            "naturalSpawnDefaultScale", "0.2",
            "naturalSpawnScale.minecraft:zombie", "0.5",
            "naturalSpawnScale.minecraft:creeper", "5",      // clamps to 1.0
            "naturalSpawnScale.minecraft:cow", "-1",         // clamps to 0.0
            "naturalSpawnScale.minecraft:pig", "abc",        // unparseable -> dropped
            "naturalSpawnScale.", "0.9"));                   // blank id -> dropped
        assertTrue(v.naturalSpawnEnabled());
        assertEquals(0.2F, v.naturalSpawnDefaultScale(), 1e-6);
        Map<String, Float> scales = v.naturalSpawnScales();
        assertEquals(0.5F, scales.get("minecraft:zombie"), 1e-6);
        assertEquals(1.0F, scales.get("minecraft:creeper"), 1e-6);
        assertEquals(0.0F, scales.get("minecraft:cow"), 1e-6);
        assertFalse(scales.containsKey("minecraft:pig"), "unparseable value is dropped");
        assertEquals(3, scales.size(), "only zombie, creeper, cow survive (pig + blank-id dropped)");
    }

    @Test
    void resolveScaleHonoursMasterOverrideAndDefault() {
        Map<String, Float> overrides = Map.of("minecraft:zombie", 0.5F);
        // master off -> always 0, even for an explicit override
        assertEquals(0.0F, PlayerMobConfig.resolveScale(false, 0.05F, overrides, "minecraft:zombie"), 1e-6);
        // explicit override wins
        assertEquals(0.5F, PlayerMobConfig.resolveScale(true, 0.05F, overrides, "minecraft:zombie"), 1e-6);
        // listed mob without override falls back to the default scale
        assertEquals(0.05F, PlayerMobConfig.resolveScale(true, 0.05F, overrides, "minecraft:creeper"), 1e-6);
        // unlisted mob -> 0 (never replaced)
        assertEquals(0.0F, PlayerMobConfig.resolveScale(true, 0.05F, overrides, "minecraft:warden"), 1e-6);
    }

    @Test
    void naturalSpawnMobListIsNonEmptyAndNamespaced() {
        assertFalse(PlayerMobConfig.NATURAL_SPAWN_MOBS.isEmpty());
        assertTrue(PlayerMobConfig.NATURAL_SPAWN_MOBS.contains("minecraft:zombie"));
        assertTrue(PlayerMobConfig.NATURAL_SPAWN_MOBS.stream().allMatch(id -> id.contains(":")),
            "every listed mob id is fully namespaced");
    }

    @Test
    void setNaturalSpawnEnabledTogglesAtRuntime() {
        try {
            PlayerMobConfig.setNaturalSpawnEnabled(true);
            assertTrue(PlayerMobConfig.naturalSpawnEnabled());
            PlayerMobConfig.setNaturalSpawnEnabled(false);
            assertFalse(PlayerMobConfig.naturalSpawnEnabled());
        } finally {
            PlayerMobConfig.setNaturalSpawnEnabled(PlayerMobConfig.DEFAULT_NATURAL_SPAWN_ENABLED);
        }
    }

    @Test
    void setNaturalSpawnScaleOverridesLookupAndClamps() {
        try {
            PlayerMobConfig.setNaturalSpawnEnabled(true);
            PlayerMobConfig.setNaturalSpawnScale("minecraft:zombie", 0.5F);
            assertEquals(0.5F, PlayerMobConfig.naturalSpawnScale("minecraft:zombie"), 1e-6);
            // a command can enable a mob not in the default list — the explicit override wins
            PlayerMobConfig.setNaturalSpawnScale("minecraft:warden", 1.0F);
            assertEquals(1.0F, PlayerMobConfig.naturalSpawnScale("minecraft:warden"), 1e-6);
            // off -> 0, and out-of-range clamps
            PlayerMobConfig.setNaturalSpawnScale("minecraft:zombie", 0.0F);
            assertEquals(0.0F, PlayerMobConfig.naturalSpawnScale("minecraft:zombie"), 1e-6);
            PlayerMobConfig.setNaturalSpawnScale("minecraft:zombie", 9.0F);
            assertEquals(1.0F, PlayerMobConfig.naturalSpawnScale("minecraft:zombie"), 1e-6);
        } finally {
            // reset session state so it can't leak into other tests
            PlayerMobConfig.setNaturalSpawnScale("minecraft:zombie", PlayerMobConfig.DEFAULT_NATURAL_SPAWN_SCALE);
            PlayerMobConfig.setNaturalSpawnScale("minecraft:warden", 0.0F);
            PlayerMobConfig.setNaturalSpawnEnabled(PlayerMobConfig.DEFAULT_NATURAL_SPAWN_ENABLED);
        }
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
