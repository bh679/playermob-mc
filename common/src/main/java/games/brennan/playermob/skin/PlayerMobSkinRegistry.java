package games.brennan.playermob.skin;

import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side singleton holding the currently-loaded set of
 * {@link PlayerMobSkin} entries. {@link PlayerMobSkinReloadListener} swaps the
 * list atomically on datapack reload; {@link #pickRandom} is what
 * {@code PlayerMobEntity.finalizeSpawn} calls to assign a skin.
 *
 * <p>Read-mostly: written rarely (boot, {@code /reload}), read on every spawn.
 * Uses {@link CopyOnWriteArrayList} so reads are lock-free and writes are
 * atomic (one assignment of the whole list).</p>
 *
 * <p>Empty registry → {@link #pickRandom} returns {@link Optional#empty()}.
 * Callers (i.e. {@code finalizeSpawn}) degrade to the legacy bundled
 * {@code SkinIndex} rendering path when this happens.</p>
 */
public final class PlayerMobSkinRegistry {

    private static volatile List<PlayerMobSkin> skins = new CopyOnWriteArrayList<>();

    private PlayerMobSkinRegistry() {}

    /**
     * Swap the registry to {@code next} atomically. Called from the reload
     * listener at the tail of its {@code apply(...)}. Defensive copy so the
     * caller can't mutate the registry afterward.
     */
    public static void replaceAll(List<PlayerMobSkin> next) {
        skins = new CopyOnWriteArrayList<>(next);
    }

    /** Snapshot view for tests and debugging. Never null. */
    public static List<PlayerMobSkin> all() {
        return List.copyOf(skins);
    }

    /** Number of currently-loaded skin entries. */
    public static int size() {
        return skins.size();
    }

    /**
     * Pick a random entry using the supplied {@link RandomSource}. Returns
     * {@link Optional#empty()} when the registry is empty (boot before reload,
     * or a datapack that strips everything).
     */
    public static Optional<PlayerMobSkin> pickRandom(RandomSource random) {
        List<PlayerMobSkin> snapshot = skins;
        int n = snapshot.size();
        if (n == 0) {
            return Optional.empty();
        }
        return Optional.of(snapshot.get(random.nextInt(n)));
    }

    /**
     * Look up the first entry with the given texture URL. Used by the client
     * helper to recover the model variant when the synced entity only carries
     * the URL string.
     */
    public static Optional<PlayerMobSkin> findByTextureUrl(String url) {
        if (url == null || url.isEmpty()) {
            return Optional.empty();
        }
        for (PlayerMobSkin skin : skins) {
            if (skin.textureUrl().equals(url)) {
                return Optional.of(skin);
            }
        }
        return Optional.empty();
    }
}
