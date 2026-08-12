package games.brennan.playermob.compat;

import games.brennan.playermob.PlayerMobConfig;
import net.minecraft.world.level.Level;

/**
 * The reincarnation pool's <em>partition</em> key: the vanilla difficulty a life was lived on.
 *
 * <p>A life recorded on one difficulty is only ever offered back on that same difficulty. Dungeon
 * Train makes each vanilla difficulty a self-contained profile — its own ender chest, its own carried
 * loadout, its own echoes — and this is the echo half of that: gear you never earned on Hard should not
 * walk up to you wearing your Peaceful run's face.</p>
 *
 * <p>Unlike {@link FreePlayQuery} this needs no provider seam. The difficulty is a vanilla,
 * server-authoritative value readable straight off the level, so every repo in the chain (PlayerMob,
 * Discord Presence, the relay, Dungeon Train) derives the same key independently from
 * {@code Difficulty.getSerializedName()} — nothing has to be threaded through an integration.</p>
 *
 * <p><b>Legacy records.</b> Every life logged before the partition existed carries no difficulty, and
 * reads as {@link #LEGACY} — the difficulty essentially all of it was actually played on. Attributing
 * the history is deliberate; the alternative (a partition of its own) would quietly strand years of
 * past lives where no player could ever meet them.</p>
 */
public final class ReincarnationDifficulty {

    /** The partition an untagged (pre-partition) life belongs to. */
    public static final String LEGACY = "normal";

    private ReincarnationDifficulty() {}

    /** The partition key for {@code level}, or {@code ""} when there is no level to read. */
    public static String keyFor(Level level) {
        return level == null ? "" : level.getDifficulty().getSerializedName();
    }

    /** A stored difficulty's effective partition — null/blank is {@link #LEGACY}. */
    public static String partitionOf(String difficulty) {
        return difficulty == null || difficulty.isEmpty() ? LEGACY : difficulty;
    }

    /**
     * Whether echo selection should be partitioned by difficulty at all (config, default on). When
     * off, every partition is drawn from as one pool — the behaviour before this existed.
     */
    public static boolean isolationEnabled() {
        return PlayerMobConfig.reincarnationDifficultyIsolation();
    }

    /**
     * The partition to filter a spawn query by: {@code null} (no filter) when isolation is off or the
     * level is unreadable, else {@link #keyFor}.
     */
    public static String filterFor(Level level) {
        if (!isolationEnabled()) {
            return null;
        }
        String key = keyFor(level);
        return key.isEmpty() ? null : key;
    }
}
