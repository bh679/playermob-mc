package games.brennan.playermob.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Resolves a Minecraft <b>username</b> (or a UUID) to that player's current skin — a
 * {@code (textureUrl, slim)} pair — server-side, the same way vanilla bakes a skin into a
 * {@code player_head}: look the name up in the server's {@code GameProfileCache} (name → UUID),
 * then fetch the textures-bearing profile from the session service (UUID → skin URL + arm model).
 *
 * <p><b>Off-thread + cached.</b> Both steps are blocking network calls against Mojang's
 * (rate-limited) APIs, so every resolution runs on a dedicated background thread and the result is
 * memoised in-process. Concurrent requests for the same name share one fetch. The vanilla
 * {@code usercache.json} already persists the heavily-rate-limited name → UUID step across
 * restarts, so a per-session in-memory cache is enough here.</p>
 *
 * <p><b>Graceful degradation.</b> An unknown name, an offline server with no internet, or any
 * fetch failure resolves to {@link Optional#empty()} — never an exception into the caller. Callers
 * (the datapack registry, the {@code /playermob summon} command) simply fall back to a bundled skin.
 * Results are delivered on the {@linkplain MinecraftServer#execute server thread}, because callers
 * mutate entity / registry state.</p>
 *
 * <p>Version-bridged like {@link games.brennan.playermob.player.PlayerReincarnation}: the session
 * service moved to {@code services().sessionService()} on 26.x, and 1.20.1's older authlib has no
 * {@code fetchProfile} (it uses {@code fillProfileProperties} + a texture {@code Map}).</p>
 */
public final class PlayerSkinResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A resolved skin: the Mojang CDN texture URL and whether it uses the slim (3-pixel) arm model. */
    public record Resolved(String url, boolean slim) {}

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayerMob-skin-resolver");
        t.setDaemon(true);
        return t;
    });

    /** key (lower-cased name/uuid) → resolved skin. Only successful resolutions are cached. */
    private static final ConcurrentMap<String, Resolved> CACHE = new ConcurrentHashMap<>();

    /** key → callbacks awaiting an in-flight fetch, so duplicate requests share one network call. */
    private static final Map<String, List<Consumer<Optional<Resolved>>>> WAITERS = new HashMap<>();

    private PlayerSkinResolver() {}

    /** Synchronous cache peek — never touches the network. Empty until a resolution has succeeded. */
    public static Optional<Resolved> cached(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CACHE.get(key(nameOrUuid)));
    }

    /**
     * Resolve {@code nameOrUuid} and deliver the result (present or empty) to {@code callback} on the
     * server thread. Returns immediately. A cached hit calls back synchronously; otherwise the fetch
     * runs in the background and all waiters for the same key are notified when it completes.
     */
    public static void resolveAsync(MinecraftServer server, String nameOrUuid,
                                    Consumer<Optional<Resolved>> callback) {
        if (server == null || nameOrUuid == null || nameOrUuid.isBlank()) {
            callback.accept(Optional.empty());
            return;
        }
        String key = key(nameOrUuid);
        Resolved hit = CACHE.get(key);
        if (hit != null) {
            callback.accept(Optional.of(hit));
            return;
        }
        boolean startFetch;
        synchronized (WAITERS) {
            List<Consumer<Optional<Resolved>>> list = WAITERS.computeIfAbsent(key, k -> new ArrayList<>());
            startFetch = list.isEmpty();
            list.add(callback);
        }
        if (!startFetch) {
            return;     // joined an in-flight fetch — its completion notifies every waiter
        }
        EXECUTOR.submit(() -> runFetch(server, nameOrUuid, key));
    }

    private static void runFetch(MinecraftServer server, String nameOrUuid, String key) {
        Optional<Resolved> result;
        try {
            result = blockingResolve(server, nameOrUuid);
        } catch (RuntimeException e) {
            LOGGER.warn("[playermob] skin resolution for '{}' failed", nameOrUuid, e);
            result = Optional.empty();
        }
        result.ifPresent(r -> CACHE.put(key, r));
        List<Consumer<Optional<Resolved>>> waiting;
        synchronized (WAITERS) {
            waiting = WAITERS.remove(key);
        }
        Optional<Resolved> delivered = result;
        try {
            server.execute(() -> {
                if (waiting != null) {
                    for (Consumer<Optional<Resolved>> cb : waiting) {
                        cb.accept(delivered);
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // Server is stopping — nothing to notify; the entity/registry are going away anyway.
        }
    }

    /** A version-neutral name lookup result — the UUID and (best-known) name for a username. */
    private record NameId(UUID id, String name) {}

    /** The blocking core: name/uuid → resolved skin, or empty on any miss/failure. Runs off-thread. */
    private static Optional<Resolved> blockingResolve(MinecraftServer server, String nameOrUuid) {
        UUID id;
        String name;
        if (looksLikeUuid(nameOrUuid)) {
            id = UUID.fromString(nameOrUuid.trim());
            name = "PlayerMob";
        } else {
            Optional<NameId> base = lookupByName(server, nameOrUuid.trim());
            if (base.isEmpty()) {
                return Optional.empty();
            }
            id = base.get().id();
            name = base.get().name();
        }
        MinecraftProfileTexture skin = fetchSkin(server, id, name);
        if (skin == null || skin.getUrl() == null || skin.getUrl().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(skin.getUrl(), "slim".equals(skin.getMetadata("model"))));
    }

    /**
     * name → UUID via the server's name cache (queries Mojang + persists to {@code usercache.json}
     * on a miss). 26.x moved this from {@code getProfileCache()} to
     * {@code services().nameToIdCache()} (a {@code NameAndId} rather than a {@code GameProfile}).
     */
    private static Optional<NameId> lookupByName(MinecraftServer server, String name) {
        //? if >=26 {
        /*return server.services().nameToIdCache().get(name)
            .map(n -> new NameId(n.id(), n.name()));
        *///?} else {
        var cache = server.getProfileCache();
        if (cache == null) {
            return Optional.empty();
        }
        return cache.get(name).map(p -> new NameId(p.getId(), p.getName()));
        //?}
    }

    /** UUID → the player's SKIN texture (URL + {@code model} metadata), or {@code null} if unavailable. */
    private static MinecraftProfileTexture fetchSkin(MinecraftServer server, UUID id, String name) {
        MinecraftSessionService session = sessionService(server);
        //? if >=1.21.1 {
        var result = session.fetchProfile(id, false);
        if (result == null || result.profile() == null) {
            return null;
        }
        return session.getTextures(result.profile()).skin();
        //?} else {
        /*// 1.20.1 authlib (4.0.43) has no fetchProfile — fill the bare profile, then read the Map.
        GameProfile filled = session.fillProfileProperties(new GameProfile(id, name), false);
        return session.getTextures(filled, false).get(MinecraftProfileTexture.Type.SKIN);
        *///?}
    }

    private static MinecraftSessionService sessionService(MinecraftServer server) {
        //? if >=26 {
        /*return server.services().sessionService();
        *///?} else {
        return server.getSessionService();
        //?}
    }

    // ---- Pure helpers (unit-tested without a server) ----------------------

    /** True when {@code s} parses as a UUID (so we skip the name → UUID lookup). */
    static boolean looksLikeUuid(String s) {
        if (s == null) {
            return false;
        }
        try {
            UUID.fromString(s.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Cache/dedup key: trimmed + lower-cased, so {@code "Notch"} and {@code "notch"} share a fetch. */
    static String key(String nameOrUuid) {
        return nameOrUuid.trim().toLowerCase(Locale.ROOT);
    }

    /** Test seam: drop memoised results so a unit test starts clean. */
    static void clearCacheForTest() {
        CACHE.clear();
        synchronized (WAITERS) {
            WAITERS.clear();
        }
    }
}
