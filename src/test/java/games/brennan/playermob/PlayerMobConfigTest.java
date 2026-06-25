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
        assertEquals(PlayerMobConfig.DEFAULT_ATTACK_RANGE_MULTIPLIER, v.attackRangeMultiplier(), 1e-6);
        assertEquals(PlayerMobConfig.DEFAULT_RANGED_ATTACK_RANGE_MULTIPLIER, v.rangedAttackRangeMultiplier(), 1e-6);
    }

    @Test
    void attackRangeMultipliersParsedClampedAndDefaulted() {
        assertEquals(1.0F, PlayerMobConfig.DEFAULT_ATTACK_RANGE_MULTIPLIER, 1e-6, "global ships at 1.0 (unchanged)");
        assertEquals(2.0F, PlayerMobConfig.DEFAULT_RANGED_ATTACK_RANGE_MULTIPLIER, 1e-6, "ranged ships at 2.0 (x2)");

        var v = PlayerMobConfig.parse(props("attackRangeMultiplier", "1.5", "rangedAttackRangeMultiplier", "3"));
        assertEquals(1.5F, v.attackRangeMultiplier(), 1e-6);
        assertEquals(3.0F, v.rangedAttackRangeMultiplier(), 1e-6);

        // Clamp: negative -> 0 (disables proactive distance-attack); absurd -> MAX_RANGE_MULTIPLIER.
        assertEquals(0.0F, PlayerMobConfig.parse(props("attackRangeMultiplier", "-2")).attackRangeMultiplier(), 1e-6);
        assertEquals(PlayerMobConfig.MAX_RANGE_MULTIPLIER,
            PlayerMobConfig.parse(props("rangedAttackRangeMultiplier", "999")).rangedAttackRangeMultiplier(), 1e-6);

        // Unparseable -> default (like the other lenient-parsed keys).
        var bad = PlayerMobConfig.parse(props("attackRangeMultiplier", "abc", "rangedAttackRangeMultiplier", "x"));
        assertEquals(PlayerMobConfig.DEFAULT_ATTACK_RANGE_MULTIPLIER, bad.attackRangeMultiplier(), 1e-6);
        assertEquals(PlayerMobConfig.DEFAULT_RANGED_ATTACK_RANGE_MULTIPLIER, bad.rangedAttackRangeMultiplier(), 1e-6);
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
    void naturalSpawnDefaultsOff() {
        var v = PlayerMobConfig.parse(new Properties());
        assertFalse(v.naturalSpawnEnabled(), "natural spawning ships off");
        assertFalse(PlayerMobConfig.DEFAULT_NATURAL_SPAWN_ENABLED);
        assertTrue(v.naturalSpawnScales().isEmpty(), "no per-mob overrides parsed from an empty file");
    }

    @Test
    void groupDefaultsAppliedByResolveScale() {
        Map<String, Float> none = Map.of();
        assertEquals(0.0F, PlayerMobConfig.resolveScale(true, none, "minecraft:zombie"), 1e-6);   // hostile
        assertEquals(0.05F, PlayerMobConfig.resolveScale(true, none, "minecraft:blaze"), 1e-6);   // nether
        assertEquals(0.15F, PlayerMobConfig.resolveScale(true, none, "minecraft:cow"), 1e-6);     // animals
        assertEquals(0.15F, PlayerMobConfig.resolveScale(true, none, "minecraft:wolf"), 1e-6);    // friendly
        assertEquals(0.0F, PlayerMobConfig.resolveScale(true, none, "minecraft:cod"), 1e-6);      // water
        assertEquals(0.25F, PlayerMobConfig.resolveScale(true, none, "minecraft:villager"), 1e-6); // villager
        assertEquals(0.25F, PlayerMobConfig.resolveScale(true, none, "minecraft:iron_golem"), 1e-6);
        assertEquals(0.0F, PlayerMobConfig.resolveScale(true, none, "minecraft:warden"), 1e-6);   // ungrouped
    }

    @Test
    void resolveScaleHonoursMasterAndOverride() {
        Map<String, Float> overrides = Map.of("minecraft:zombie", 0.5F);
        // master off -> always 0, even for an explicit override
        assertEquals(0.0F, PlayerMobConfig.resolveScale(false, overrides, "minecraft:zombie"), 1e-6);
        // explicit override wins over the group default
        assertEquals(0.5F, PlayerMobConfig.resolveScale(true, overrides, "minecraft:zombie"), 1e-6);
        // grouped mob without override falls back to its group default (animals 0.15)
        assertEquals(0.15F, PlayerMobConfig.resolveScale(true, overrides, "minecraft:cow"), 1e-6);
    }

    @Test
    void naturalSpawnScaleOverridesParsedAndClamped() {
        var v = PlayerMobConfig.parse(props(
            "naturalSpawnEnabled", "true",
            "naturalSpawnScale.minecraft:zombie", "0.5",
            "naturalSpawnScale.minecraft:creeper", "5",      // clamps to 1.0
            "naturalSpawnScale.minecraft:cow", "-1",         // clamps to 0.0
            "naturalSpawnScale.minecraft:pig", "abc",        // unparseable -> dropped
            "naturalSpawnScale.", "0.9"));                   // blank id -> dropped
        assertTrue(v.naturalSpawnEnabled());
        Map<String, Float> scales = v.naturalSpawnScales();
        assertEquals(0.5F, scales.get("minecraft:zombie"), 1e-6);
        assertEquals(1.0F, scales.get("minecraft:creeper"), 1e-6);
        assertEquals(0.0F, scales.get("minecraft:cow"), 1e-6);
        assertFalse(scales.containsKey("minecraft:pig"), "unparseable value is dropped");
        assertEquals(3, scales.size(), "only zombie, creeper, cow survive (pig + blank-id dropped)");
    }

    @Test
    void spawnGroupByNameIsCaseInsensitive() {
        assertEquals(PlayerMobConfig.SpawnGroup.HOSTILE, PlayerMobConfig.SpawnGroup.byName("hostile"));
        assertEquals(PlayerMobConfig.SpawnGroup.VILLAGER, PlayerMobConfig.SpawnGroup.byName("Villager"));
        assertEquals(PlayerMobConfig.SpawnGroup.WATER, PlayerMobConfig.SpawnGroup.byName("WATER"));
        assertEquals(null, PlayerMobConfig.SpawnGroup.byName("nope"));
    }

    @Test
    void everyMobMapsToExactlyOneGroupAndIsNamespaced() {
        assertFalse(PlayerMobConfig.NATURAL_SPAWN_MOBS.isEmpty());
        for (String id : PlayerMobConfig.NATURAL_SPAWN_MOBS) {
            assertTrue(id.contains(":"), id + " is fully namespaced");
            assertTrue(PlayerMobConfig.groupOf(id) != null, id + " belongs to a group");
        }
        // villager group carries villager + iron golem
        assertEquals(PlayerMobConfig.SpawnGroup.VILLAGER, PlayerMobConfig.groupOf("minecraft:villager"));
        assertEquals(PlayerMobConfig.SpawnGroup.VILLAGER, PlayerMobConfig.groupOf("minecraft:iron_golem"));
    }

    @Test
    void setGroupScaleSetsEveryMember() {
        try {
            PlayerMobConfig.setNaturalSpawnEnabled(true);
            PlayerMobConfig.setGroupScale(PlayerMobConfig.SpawnGroup.HOSTILE, 1.0F);
            assertEquals(1.0F, PlayerMobConfig.naturalSpawnScale("minecraft:zombie"), 1e-6);
            assertEquals(1.0F, PlayerMobConfig.naturalSpawnScale("minecraft:creeper"), 1e-6);
            // a different group is untouched (still its default)
            assertEquals(0.15F, PlayerMobConfig.naturalSpawnScale("minecraft:cow"), 1e-6);
        } finally {
            PlayerMobConfig.setGroupScale(PlayerMobConfig.SpawnGroup.HOSTILE,
                PlayerMobConfig.SpawnGroup.HOSTILE.defaultScale);
            PlayerMobConfig.setNaturalSpawnEnabled(PlayerMobConfig.DEFAULT_NATURAL_SPAWN_ENABLED);
        }
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
            PlayerMobConfig.setNaturalSpawnScale("minecraft:zombie",
                PlayerMobConfig.SpawnGroup.HOSTILE.defaultScale);
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
