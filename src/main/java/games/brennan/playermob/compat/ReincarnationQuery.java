package games.brennan.playermob.compat;

import java.util.UUID;

/**
 * A request for a past life to embody, handed to every {@link ReincarnationSource}. Three modes
 * describe <em>which</em> lives qualify; {@link #owner()} (the nearby live player, if any) names
 * whose "already met this life" set gates reuse so the same player isn't shown one life twice in
 * a session.
 *
 * <ul>
 *   <li>{@link Mode#CARRIAGE} — lives that ended within {@code ±}depth-band of {@link #carriage()}
 *       (Dungeon-Train echoes; nearer carriages are mildly preferred — see
 *       {@link ReincarnationWeighting}).</li>
 *   <li>{@link Mode#PLAYER} — lives belonging to {@link #player()} (a specific person's past life).</li>
 *   <li>{@link Mode#ANY} — any stored life.</li>
 * </ul>
 *
 * <p>{@link #matchFreePlay()} carries the requested provenance: {@code null} = don't filter (no
 * enforcement / dev bypass), {@code TRUE}/{@code FALSE} = keep only lives whose stored Free Play
 * flag equals it. Selection reads it in {@code ReincarnationSources.liveCandidates}.</p>
 *
 * <p>A pure value object (uuids only) so selection is unit-testable without a world.</p>
 */
public record ReincarnationQuery(Mode mode, int carriage, UUID player, UUID owner, Boolean matchFreePlay) {

    /** Which lives qualify for the request. */
    public enum Mode { CARRIAGE, PLAYER, ANY }

    /** Lives that ended within the depth band of {@code carriage}; {@code owner} may be {@code null}. */
    public static ReincarnationQuery byCarriage(int carriage, UUID owner) {
        return new ReincarnationQuery(Mode.CARRIAGE, carriage, null, owner, null);
    }

    /** Lives belonging to {@code player}; {@code owner} may be {@code null}. */
    public static ReincarnationQuery byPlayer(UUID player, UUID owner) {
        return new ReincarnationQuery(Mode.PLAYER, TrainConfinement.NO_CARRIAGE, player, owner, null);
    }

    /** Any stored life; {@code owner} may be {@code null}. */
    public static ReincarnationQuery any(UUID owner) {
        return new ReincarnationQuery(Mode.ANY, TrainConfinement.NO_CARRIAGE, null, owner, null);
    }

    /**
     * A copy of this query requiring candidates' Free Play provenance to equal {@code matchFreePlay}
     * ({@code null} = no provenance filter). Used at spawn to keep Free Play echoes with Free Play
     * players and legit with legit.
     */
    public ReincarnationQuery withMatchFreePlay(Boolean matchFreePlay) {
        return new ReincarnationQuery(mode, carriage, player, owner, matchFreePlay);
    }
}
