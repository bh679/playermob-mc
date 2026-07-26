package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The registry that pools every {@link ReincarnationSource} — PlayerMob's own death log plus any
 * registered by an integrating mod — and answers reincarnation requests across all of them. The
 * public entry point for both directions of the reincarnation integration:
 *
 * <ul>
 *   <li><b>Supply</b>: {@link #register} (a mod adds its source), {@link #pick} (the spawn path
 *       draws a past life from the combined pool).</li>
 *   <li><b>Read</b>: {@link #recentDeaths} (a mod reads recent lives across all sources).</li>
 * </ul>
 *
 * <p>Mirrors {@link PlayerMobSocialHooks}: the built-in PlayerMob death-log source is registered
 * once at boot from {@code PlayerMob.init}; an integrating mod registers its own from its loader
 * entry point. With no source registered, {@link #pick} simply yields nothing.</p>
 *
 * <p><b>Met-this-life de-dup</b> lives here, not in the sources, because a live player meets lives
 * from <em>any</em> source: each owner has a transient set of the {@link ReincarnationRecord#id()}s
 * they've already been shown this session. The set is cleared when that player dies
 * ({@link #resetSession}) and when the running server changes (a new world starts everyone fresh).
 * Server-thread only.</p>
 */
public final class ReincarnationSources {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Most candidates a single source contributes per query (the newest are kept). A ±band rarely
     * sees more than a handful of echoes before the player moves on, and the recency weighting makes
     * anything past the newest few negligible anyway — so this bounds per-spawn work, and lets a
     * network-backed source cache/fetch only this many lives per band rather than the whole log.
     */
    static final int MAX_CANDIDATES_PER_BAND = 5;

    private static volatile List<ReincarnationSource> sources = List.of();

    /** Transient per-owner "already met this life" sets (record ids); cleared on death / world change. */
    private static final Map<UUID, Set<String>> met = new HashMap<>();
    private static MinecraftServer boundServer;

    private ReincarnationSources() {}

    /**
     * Register a source of past lives. Called once at boot — by {@code PlayerMob.init} for the
     * built-in death log, and by an integrating mod from its loader entry point. Idempotent only
     * by caller discipline (each source registers itself once).
     */
    public static synchronized void register(ReincarnationSource source) {
        List<ReincarnationSource> next = new ArrayList<>(sources);
        next.add(source);
        sources = List.copyOf(next);
    }

    /**
     * Draw one past life for {@code query} from the sources of the requested kind, or empty to fall
     * back to a fresh mob. {@code remote} selects the pool: {@code false} = PlayerMob's own local
     * death log (self allowed); {@code true} = the remote pools registered by integrating mods, with
     * the live player ({@code query.owner()}) excluded so a remote echo is never the player
     * themselves. A spawning mob rolls the two kinds on separate chances, so a remote pool never
     * dilutes the local one. Pools every matching source's eligible {@linkplain
     * ReincarnationSource#candidates candidates}, drops the ones {@code query.owner()} has already met
     * this session, then makes the recency/proximity-weighted pick ({@link ReincarnationWeighting})
     * and marks it met. A source that throws is logged and skipped — selection never throws into the
     * spawn flow.
     */
    public static Optional<ReincarnationRecord> pick(MinecraftServer server, ReincarnationQuery query,
                                                     RandomSource rng, boolean remote) {
        onServer(server);
        Set<String> seen = query.owner() == null ? Set.of() : metFor(query.owner());
        // Remote echoes never embody the live player themselves — only their own world's local pool can.
        UUID excludePlayer = remote ? query.owner() : null;
        ReincarnationRecord chosen =
            pickFrom(filterByKind(sources, remote), server, query, seen, excludePlayer, rng);
        if (chosen == null) {
            return Optional.empty();
        }
        if (query.owner() != null) {
            metFor(query.owner()).add(chosen.id());
        }
        return Optional.of(chosen);
    }

    /** The registered sources of the requested kind ({@code remote} true ⇒ external pools; false ⇒ the local log). */
    static List<ReincarnationSource> filterByKind(List<ReincarnationSource> sources, boolean remote) {
        List<ReincarnationSource> out = new ArrayList<>();
        for (ReincarnationSource source : sources) {
            if (source.remote() == remote) {
                out.add(source);
            }
        }
        return out;
    }

    /**
     * The pure aggregation core (no global state): pool each source's candidates for {@code query},
     * drop the ids in {@code seen} (and any life belonging to {@code excludePlayer}, used to keep
     * remote echoes off the live player themselves), and make the weighted pick — or {@code null} if
     * nothing is left. Package-visible so the cross-source pooling is unit-tested directly.
     */
    static ReincarnationRecord pickFrom(List<ReincarnationSource> sources, MinecraftServer server,
                                        ReincarnationQuery query, Set<String> seen,
                                        UUID excludePlayer, RandomSource rng) {
        List<List<ReincarnationRecord>> groups = new ArrayList<>();
        for (ReincarnationSource source : sources) {
            List<ReincarnationRecord> live = liveCandidates(source, server, query, seen, excludePlayer);
            if (!live.isEmpty()) {
                groups.add(live);
            }
        }
        return ReincarnationWeighting.choose(groups, query, rng);
    }

    /**
     * One source's candidates for {@code query} with the owner's met lives removed — and, when
     * {@code excludePlayer} is non-null, any life belonging to that player (self-exclusion for remote
     * echoes). Empty (logged) on failure.
     */
    private static List<ReincarnationRecord> liveCandidates(ReincarnationSource source, MinecraftServer server,
                                                            ReincarnationQuery query, Set<String> seen,
                                                            UUID excludePlayer) {
        try {
            List<ReincarnationRecord> candidates = source.candidates(server, query);
            Boolean wantFreePlay = query.matchFreePlay();
            List<ReincarnationRecord> live = new ArrayList<>(candidates.size());
            for (ReincarnationRecord r : candidates) {
                if (seen.contains(r.id())) {
                    continue;
                }
                if (excludePlayer != null && excludePlayer.equals(r.playerId())) {
                    continue;
                }
                // Provenance match: keep a life only if its Free Play flag equals the requested one.
                // Filtered here (before the weighted pick + mark-met) so a mismatched life is never
                // consumed. wantFreePlay == null means no filter (dev bypass / no consumer).
                if (wantFreePlay != null
                        && NbtCompat.getBooleanOr(r.snapshot(), FreePlayQuery.SNAPSHOT_TAG, false) != wantFreePlay) {
                    continue;
                }
                live.add(r);
            }
            // Keep only the newest few — candidates arrive oldest→newest, so trim from the front.
            if (live.size() > MAX_CANDIDATES_PER_BAND) {
                return new ArrayList<>(live.subList(live.size() - MAX_CANDIDATES_PER_BAND, live.size()));
            }
            return live;
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reincarnation source {} failed to supply candidates",
                source.getClass().getName(), e);
            return List.of();
        }
    }

    /**
     * The newest stored lives across all sources, up to {@code limit} — the read direction for a mod
     * displaying/relaying PlayerMob's death log. Grouped by source (each newest-first), then capped.
     * A source that throws is logged and skipped.
     */
    public static List<ReincarnationRecord> recentDeaths(MinecraftServer server, int limit) {
        onServer(server);
        return recentFrom(sources, server, limit);
    }

    /** Pure read aggregation (no global state): each source's recent lives, capped to {@code limit}. */
    static List<ReincarnationRecord> recentFrom(List<ReincarnationSource> sources, MinecraftServer server, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ReincarnationRecord> out = new ArrayList<>();
        for (ReincarnationSource source : sources) {
            try {
                out.addAll(source.recent(server, limit));
            } catch (RuntimeException e) {
                LOGGER.error("[playermob] reincarnation source {} failed to supply recent lives",
                    source.getClass().getName(), e);
            }
        }
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /** Forget {@code owner}'s "met this life" set — call on their death so a new life meets everyone afresh. */
    public static void resetSession(UUID owner) {
        met.remove(owner);
    }

    /** Reset all per-owner sessions when the running server changes (a new world starts everyone fresh). */
    private static void onServer(MinecraftServer server) {
        if (boundServer != server) {
            boundServer = server;
            met.clear();
        }
    }

    private static Set<String> metFor(UUID owner) {
        return met.computeIfAbsent(owner, k -> new HashSet<>());
    }
}
