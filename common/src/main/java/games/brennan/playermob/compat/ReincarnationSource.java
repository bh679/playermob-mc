package games.brennan.playermob.compat;

import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * A supplier of past lives to PlayerMob's reincarnation pool — the optional-mod integration seam
 * for reincarnation. Mirrors the {@link PlayerMobSocialHooks} pattern: PlayerMob owns the seam,
 * and an integrating mod (e.g. Discord Presence) registers a source via
 * {@link ReincarnationSources#register}. PlayerMob's own death log is itself a built-in source.
 *
 * <p>When a PlayerMob spawns and rolls a reincarnation, PlayerMob asks every registered source
 * for its eligible {@linkplain #candidates candidates} and pools them; a source that loved this
 * life's depth or person simply offers more. The source returns whatever it has — the registry
 * handles "already met this life" de-dup and the recency/proximity-weighted final pick, so a
 * source never has to track sessions or weighting itself.</p>
 *
 * <p>Implementations are queried on the <b>server thread</b> during entity spawn, so
 * {@link #candidates} must answer synchronously from local state (no blocking I/O / network). A
 * mod syncing lives from elsewhere should serve them from a pre-populated in-memory cache.</p>
 */
public interface ReincarnationSource {

    /**
     * Every past life this source offers for {@code query}, oldest first (so the last element is
     * the most recent — the registry weights recency by position). Already filtered to the query's
     * mode (carriage band / player / any); <em>not</em> de-duped against the owner's met-set — the
     * registry does that. Return an empty list (never {@code null}) when this source has nothing.
     */
    List<ReincarnationRecord> candidates(MinecraftServer server, ReincarnationQuery query);

    /**
     * The newest lives this source knows, up to {@code limit} (newest first) — the read direction,
     * for a mod that wants to display/relay PlayerMob's death log. Defaults to none, so a
     * supply-only source need not implement it.
     */
    default List<ReincarnationRecord> recent(MinecraftServer server, int limit) {
        return List.of();
    }
}
