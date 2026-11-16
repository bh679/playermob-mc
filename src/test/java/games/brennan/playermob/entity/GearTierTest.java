package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GearTierTest {

    @Test
    void classifiesByMaterial() {
        assertEquals(GearTier.WOOD, GearTier.classify("minecraft:wooden_pickaxe"));
        assertEquals(GearTier.WOOD, GearTier.classify("minecraft:leather_boots"));
        assertEquals(GearTier.STONE, GearTier.classify("minecraft:chainmail_chestplate"));
        assertEquals(GearTier.STONE, GearTier.classify("minecraft:golden_sword"));
        assertEquals(GearTier.IRON, GearTier.classify("minecraft:iron_ingot"));
        assertEquals(GearTier.DIAMOND, GearTier.classify("minecraft:diamond"));
        assertEquals(GearTier.NETHERITE, GearTier.classify("minecraft:netherite_scrap"));
    }

    @Test
    void unknownAndNullAreNone() {
        assertEquals(GearTier.NONE, GearTier.classify("somemod:mythril_sword"));
        assertEquals(GearTier.NONE, GearTier.classify("minecraft:grass_block"));
        assertEquals(GearTier.NONE, GearTier.classify(null));
    }

    @Test
    void bestIsTheHighestTierCarried() {
        assertEquals(GearTier.NONE, GearTier.best(List.of()));
        assertEquals(GearTier.IRON, GearTier.best(List.of(
            "minecraft:stone_axe", "minecraft:iron_ingot", "minecraft:dirt", "minecraft:leather_helmet")));
        assertEquals(GearTier.NETHERITE, GearTier.best(List.of("minecraft:netherite_boots", "minecraft:bread")));
    }

    @Test
    void everyPoolIsNonEmptyAndIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (GearTier tier : GearTier.values()) {
            assertFalse(tier.pool().isEmpty(), tier + " pool is empty");
            for (GearTier.Entry entry : tier.pool()) {
                assertTrue(seen.add(entry.itemId()), "duplicate " + entry.itemId());
                assertTrue(entry.maxCount() >= 1, entry.itemId() + " max count");
                assertTrue(entry.itemId().startsWith("minecraft:"), entry.itemId());
                assertEquals(tier, GearTier.classify(entry.itemId()));
            }
        }
    }
}
