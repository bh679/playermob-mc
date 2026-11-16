package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalSpawnGearTest {

    @Test
    void rollNeverExceedsThePlayersTier() {
        for (GearTier playerTier : GearTier.values()) {
            Random rng = new Random(playerTier.ordinal());
            boolean sawTopTier = false;
            for (int i = 0; i < 2000; i++) {
                for (NaturalSpawnGear.Pick pick : NaturalSpawnGear.roll(playerTier, rng::nextInt)) {
                    GearTier tier = GearTier.classify(pick.itemId());
                    assertTrue(tier.ordinal() <= playerTier.ordinal(),
                        pick.itemId() + " above " + playerTier);
                    sawTopTier |= tier == playerTier;
                }
            }
            assertTrue(sawTopTier, "never rolled the player's own tier " + playerTier);
        }
    }

    @Test
    void itemCountAndStackSizesStayInBounds() {
        Random rng = new Random(42);
        for (int i = 0; i < 2000; i++) {
            List<NaturalSpawnGear.Pick> picks = NaturalSpawnGear.roll(GearTier.NETHERITE, rng::nextInt);
            assertTrue(picks.size() >= NaturalSpawnGear.MIN_ITEMS && picks.size() <= NaturalSpawnGear.MAX_ITEMS);
            for (NaturalSpawnGear.Pick pick : picks) {
                int max = GearTier.classify(pick.itemId()).pool().stream()
                    .filter(e -> e.itemId().equals(pick.itemId())).findFirst().orElseThrow().maxCount();
                assertTrue(pick.count() >= 1 && pick.count() <= max, pick.toString());
            }
        }
    }

    @Test
    void emptyHandedPlayerOnlyYieldsNoneTier() {
        Random rng = new Random(7);
        for (int i = 0; i < 500; i++) {
            for (NaturalSpawnGear.Pick pick : NaturalSpawnGear.roll(GearTier.NONE, rng::nextInt)) {
                assertEquals(GearTier.NONE, GearTier.classify(pick.itemId()));
            }
        }
    }

    @Test
    void onlyWildSpawnReasonsGetItems() {
        assertTrue(NaturalSpawnGear.isWildSpawn("NATURAL"));
        assertTrue(NaturalSpawnGear.isWildSpawn("CHUNK_GENERATION"));
        assertTrue(NaturalSpawnGear.isWildSpawn("STRUCTURE"));
        assertFalse(NaturalSpawnGear.isWildSpawn("EVENT"));
        assertFalse(NaturalSpawnGear.isWildSpawn("SPAWN_EGG"));
        assertFalse(NaturalSpawnGear.isWildSpawn("COMMAND"));
        assertFalse(NaturalSpawnGear.isWildSpawn("SPAWNER"));
        assertFalse(NaturalSpawnGear.isWildSpawn(null));
    }

    @Test
    void handheldIsWeaponsAndToolsOnly() {
        assertTrue(NaturalSpawnGear.isHandheld("minecraft:iron_sword"));
        assertTrue(NaturalSpawnGear.isHandheld("minecraft:stone_pickaxe"));
        assertTrue(NaturalSpawnGear.isHandheld("minecraft:bow"));
        assertFalse(NaturalSpawnGear.isHandheld("minecraft:bucket"));
        assertFalse(NaturalSpawnGear.isHandheld("minecraft:crafting_table"));
        assertFalse(NaturalSpawnGear.isHandheld("minecraft:iron_ingot"));
    }
}
