package games.brennan.playermob.compat;

import games.brennan.playermob.skin.PlayerMobSkin;
import games.brennan.playermob.skin.PlayerMobSkinRegistry;
import games.brennan.playermob.skin.SkinModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link SkinSources} seam: registered sources feed {@link PlayerMobSkinRegistry}, the
 * union de-duplicates by texture URL, and a throwing source never breaks collection.
 */
class SkinSourcesTest {

    private static final PlayerMobSkin A = new PlayerMobSkin("A", "http://x/a", SkinModel.WIDE);
    private static final PlayerMobSkin B = new PlayerMobSkin("B", "http://x/b", SkinModel.SLIM);

    @BeforeEach
    void reset() {
        SkinSources.resetForTest();
        PlayerMobSkinRegistry.replaceAll(List.of());     // clear the datapack pool too
    }

    @AfterEach
    void cleanup() {
        // Leave the global registry pristine so other test classes see an empty pool regardless
        // of run order (the external pool persists across reloads by design).
        SkinSources.resetForTest();
        PlayerMobSkinRegistry.replaceAll(List.of());
    }

    @Test
    void registeredSourceFeedsTheRegistry() {
        SkinSources.register(() -> List.of(A, B));
        List<PlayerMobSkin> pool = PlayerMobSkinRegistry.all();
        assertTrue(pool.contains(A));
        assertTrue(pool.contains(B));
    }

    @Test
    void unionDeDupesByTextureUrl() {
        // Datapack ships A; an external source also supplies A's URL plus B → A appears once.
        PlayerMobSkinRegistry.replaceAll(List.of(A));
        SkinSources.register(() -> List.of(
            new PlayerMobSkin("A-dup", "http://x/a", SkinModel.SLIM), B));
        List<PlayerMobSkin> pool = PlayerMobSkinRegistry.all();
        assertEquals(2, pool.size(), "Same URL from datapack + source should collapse to one entry");
        assertTrue(pool.stream().anyMatch(s -> s.textureUrl().equals("http://x/b")));
    }

    @Test
    void throwingSourceIsSkipped() {
        SkinSources.register(() -> { throw new IllegalStateException("boom"); });
        SkinSources.register(() -> List.of(A));
        // collect() swallows the throwing source and still returns the good one.
        assertTrue(SkinSources.collect().contains(A));
        assertTrue(PlayerMobSkinRegistry.all().contains(A));
    }
}
