package games.brennan.playermob.skin;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PlayerMobSkinRegistry}. Pure data, no Minecraft bootstrap
 * required — {@link RandomSource} is a small utility.
 */
class PlayerMobSkinRegistryTest {

    private static final PlayerMobSkin A =
        new PlayerMobSkin("A", "http://x/a", SkinModel.WIDE);
    private static final PlayerMobSkin B =
        new PlayerMobSkin("B", "http://x/b", SkinModel.SLIM);
    private static final PlayerMobSkin C =
        new PlayerMobSkin("C", "http://x/c", SkinModel.WIDE);

    @BeforeEach
    void clearRegistry() {
        PlayerMobSkinRegistry.replaceAll(List.of());
    }

    @AfterEach
    void clearRegistryAgain() {
        // Leave the static state empty so test order doesn't matter.
        PlayerMobSkinRegistry.replaceAll(List.of());
    }

    @Test
    void emptyRegistryReturnsEmptyOptional() {
        assertEquals(0, PlayerMobSkinRegistry.size());
        Optional<PlayerMobSkin> picked =
            PlayerMobSkinRegistry.pickRandom(RandomSource.create(1L));
        assertTrue(picked.isEmpty(),
            "Empty registry must return Optional.empty() so finalizeSpawn can degrade cleanly");
    }

    @Test
    void pickRandomReturnsOnlyRegisteredSkins() {
        PlayerMobSkinRegistry.replaceAll(List.of(A, B, C));
        Set<PlayerMobSkin> available = Set.of(A, B, C);
        RandomSource random = RandomSource.create(42L);
        for (int i = 0; i < 200; i++) {
            Optional<PlayerMobSkin> picked = PlayerMobSkinRegistry.pickRandom(random);
            assertTrue(picked.isPresent());
            assertTrue(available.contains(picked.get()),
                "pickRandom must only return registered skins");
        }
    }

    @Test
    void pickRandomCoversAllRegisteredSkinsOverManyRolls() {
        // With 3 entries and 200 rolls, each entry should turn up at least
        // once. If pickRandom is biased to a single index, this fails.
        PlayerMobSkinRegistry.replaceAll(List.of(A, B, C));
        RandomSource random = RandomSource.create(7L);
        Set<PlayerMobSkin> seen = new HashSet<>();
        for (int i = 0; i < 200 && seen.size() < 3; i++) {
            PlayerMobSkinRegistry.pickRandom(random).ifPresent(seen::add);
        }
        assertEquals(3, seen.size(),
            "All three entries should turn up over 200 rolls");
    }

    @Test
    void replaceAllSwapsTheBackingList() {
        PlayerMobSkinRegistry.replaceAll(List.of(A));
        assertEquals(1, PlayerMobSkinRegistry.size());
        PlayerMobSkinRegistry.replaceAll(List.of(B, C));
        assertEquals(2, PlayerMobSkinRegistry.size());
        // No leftover from the first replaceAll
        assertFalse(PlayerMobSkinRegistry.findByTextureUrl("http://x/a").isPresent());
        assertTrue(PlayerMobSkinRegistry.findByTextureUrl("http://x/b").isPresent());
    }

    @Test
    void findByTextureUrlHandlesNullAndEmpty() {
        PlayerMobSkinRegistry.replaceAll(List.of(A));
        assertTrue(PlayerMobSkinRegistry.findByTextureUrl(null).isEmpty());
        assertTrue(PlayerMobSkinRegistry.findByTextureUrl("").isEmpty());
        assertTrue(PlayerMobSkinRegistry.findByTextureUrl("nope").isEmpty());
        assertTrue(PlayerMobSkinRegistry.findByTextureUrl("http://x/a").isPresent());
    }

    @Test
    void allReturnsCurrentSnapshot() {
        PlayerMobSkinRegistry.replaceAll(List.of(A, B));
        List<PlayerMobSkin> snapshot = PlayerMobSkinRegistry.all();
        assertEquals(2, snapshot.size());
        // Mutating the snapshot must not affect the registry.
        assertEquals(List.of(A, B), snapshot);
    }
}
