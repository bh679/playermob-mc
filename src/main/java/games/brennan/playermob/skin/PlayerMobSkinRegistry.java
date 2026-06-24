package games.brennan.playermob.skin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Server-side singleton holding the currently-usable set of {@link PlayerMobSkin} entries — the pool
 * {@code PlayerMobEntity.finalizeSpawn} draws from. The pool is the union of three sources:
 *
 * <ul>
 *   <li><b>Datapack URL entries</b> — {@code playermob_skins/*.json} entries that ship a ready
 *       {@code textureUrl}; available immediately on {@code /reload}.</li>
 *   <li><b>Resolved name entries</b> — datapack entries that named a player; folded in
 *       asynchronously as {@link PlayerSkinResolver} resolves each name to a URL (see
 *       {@link #ensureResolved}).</li>
 *   <li><b>External entries</b> — supplied programmatically by another mod through
 *       {@link games.brennan.playermob.compat.SkinSources}.</li>
 * </ul>
 *
 * <p>The three inputs are unioned (de-duplicated by texture URL) into a single {@code effective}
 * list that is swapped atomically; {@link #pickRandom} reads it lock-free. Mutations (reload,
 * resolution callbacks, external registration) are serialized on {@link #LOCK} and all run on the
 * server thread (resolution callbacks are delivered there) or at boot, so the recompute is simple.</p>
 *
 * <p>Empty pool → {@link #pickRandom} returns {@link Optional#empty()} and the caller degrades to the
 * bundled {@code SkinIndex} path — exactly the prior behaviour.</p>
 */
public final class PlayerMobSkinRegistry {

    private static final Object LOCK = new Object();

    /** Datapack entries that shipped a texture URL — usable verbatim. */
    private static List<PlayerMobSkin> datapackUrlSkins = List.of();
    /** Name entries resolved to a URL at runtime. */
    private static final List<PlayerMobSkin> nameResolved = new ArrayList<>();
    /** Skins contributed by an integrating mod via the SkinSources seam. */
    private static List<PlayerMobSkin> externalSkins = List.of();

    /** Datapack name entries still awaiting resolution. */
    private static final List<PlayerMobSkinEntry> pendingNames = new ArrayList<>();
    /** Name entries already handed to the resolver, so {@link #ensureResolved} submits each once. */
    private static final Set<PlayerMobSkinEntry> requested = new HashSet<>();

    /** The atomically-swapped, de-duplicated union the spawn path reads. Never null. */
    private static volatile List<PlayerMobSkin> effective = List.of();

    private PlayerMobSkinRegistry() {}

    /** Swap in a URL-only set with no pending name resolution — convenience for callers/tests. */
    public static void replaceAll(List<PlayerMobSkin> urlSkins) {
        replaceAll(urlSkins, List.of());
    }

    /**
     * Swap in a freshly-loaded datapack: {@code urlSkins} are usable now; {@code nameSpecs} are
     * queued for async resolution (the next {@link #ensureResolved} kicks them off). Resets all
     * prior name-resolution state so a {@code /reload} starts clean.
     */
    public static void replaceAll(List<PlayerMobSkin> urlSkins, List<PlayerMobSkinEntry> nameSpecs) {
        synchronized (LOCK) {
            datapackUrlSkins = List.copyOf(urlSkins);
            nameResolved.clear();
            pendingNames.clear();
            pendingNames.addAll(nameSpecs);
            requested.clear();
            recompute();
        }
    }

    /** Replace the external (SkinSources) contribution and recompute. Called from the seam. */
    public static void setExternalSkins(List<PlayerMobSkin> skins) {
        synchronized (LOCK) {
            externalSkins = List.copyOf(skins);
            recompute();
        }
    }

    /**
     * Kick off resolution for any datapack name entries not yet requested. Cheap no-op once every
     * name has been submitted (the common case) — safe to call on every spawn. Each resolved name is
     * folded into the pool on the server thread via {@link #onResolved}.
     */
    public static void ensureResolved(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<PlayerMobSkinEntry> toRequest = null;
        synchronized (LOCK) {
            if (pendingNames.isEmpty()) {
                return;
            }
            for (PlayerMobSkinEntry entry : pendingNames) {
                if (requested.add(entry)) {
                    if (toRequest == null) {
                        toRequest = new ArrayList<>();
                    }
                    toRequest.add(entry);
                }
            }
        }
        if (toRequest == null) {
            return;     // every pending name already in flight
        }
        for (PlayerMobSkinEntry entry : toRequest) {
            String playerName = entry.playerName().orElse(null);
            if (playerName == null) {
                continue;       // shouldn't happen — name specs always carry a playerName
            }
            PlayerSkinResolver.resolveAsync(server, playerName, opt -> onResolved(entry, opt));
        }
    }

    /** Resolution callback (server thread): drop the entry from pending; on success fold it in. */
    private static void onResolved(PlayerMobSkinEntry entry, Optional<PlayerSkinResolver.Resolved> opt) {
        synchronized (LOCK) {
            pendingNames.remove(entry);
            opt.ifPresent(r -> nameResolved.add(entry.toResolvedSkin(r.url(), r.slim())));
            recompute();
        }
    }

    /** Rebuild {@link #effective} as the de-duplicated (by URL) union of the three inputs. Holds {@link #LOCK}. */
    private static void recompute() {
        Map<String, PlayerMobSkin> byUrl = new LinkedHashMap<>();
        addAll(byUrl, datapackUrlSkins);
        addAll(byUrl, nameResolved);
        addAll(byUrl, externalSkins);
        effective = List.copyOf(byUrl.values());
    }

    private static void addAll(Map<String, PlayerMobSkin> byUrl, List<PlayerMobSkin> skins) {
        for (PlayerMobSkin skin : skins) {
            byUrl.putIfAbsent(skin.textureUrl(), skin);
        }
    }

    /** Snapshot view for tests and debugging. Never null. */
    public static List<PlayerMobSkin> all() {
        return effective;
    }

    /** Number of currently-usable skin entries (after resolution + union). */
    public static int size() {
        return effective.size();
    }

    /** Number of datapack name entries still awaiting resolution. For diagnostics / tests. */
    public static int pendingCount() {
        synchronized (LOCK) {
            return pendingNames.size();
        }
    }

    /**
     * Pick a random usable entry. Returns {@link Optional#empty()} when the pool is empty (boot
     * before reload, or a datapack that strips everything and whose names haven't resolved yet).
     */
    public static Optional<PlayerMobSkin> pickRandom(RandomSource random) {
        List<PlayerMobSkin> snapshot = effective;
        int n = snapshot.size();
        if (n == 0) {
            return Optional.empty();
        }
        return Optional.of(snapshot.get(random.nextInt(n)));
    }

    /**
     * Look up the first entry with the given texture URL. Used by the client helper to recover the
     * model variant when the synced entity only carries the URL string.
     */
    public static Optional<PlayerMobSkin> findByTextureUrl(String url) {
        if (url == null || url.isEmpty()) {
            return Optional.empty();
        }
        for (PlayerMobSkin skin : effective) {
            if (skin.textureUrl().equals(url)) {
                return Optional.of(skin);
            }
        }
        return Optional.empty();
    }
}
