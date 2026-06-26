package games.brennan.playermob.skin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression oracle for {@link LocalSkinFolder#dueForRescan} — the listing-cache staleness check.
 * An earlier version used {@code now - sentinel < ttl} with a {@code Long.MIN_VALUE} sentinel, whose
 * subtraction overflowed to a negative "fresh" reading and wedged the cache at an empty list forever
 * (the folder showed 0 skins in-game no matter what was dropped in). These pin the corrected logic.
 */
class LocalSkinFolderTest {

    private static final long NOW = 1_790_000_000_000L;   // a realistic epoch-ms "now"
    private static final long TTL = 3_000L;

    @Test
    void zeroSentinelAlwaysRescans() {
        assertTrue(LocalSkinFolder.dueForRescan(NOW, 0L), "the 0 'stale' sentinel must force a rescan");
    }

    @Test
    void freshWithinTtlDoesNotRescan() {
        assertFalse(LocalSkinFolder.dueForRescan(NOW, NOW));
        assertFalse(LocalSkinFolder.dueForRescan(NOW, NOW - (TTL - 1)));
    }

    @Test
    void rescansOnceTtlElapsed() {
        assertTrue(LocalSkinFolder.dueForRescan(NOW, NOW - TTL));
        assertTrue(LocalSkinFolder.dueForRescan(NOW, NOW - (TTL + 5_000L)));
    }

    @Test
    void extremeNegativeSentinelDoesNotOverflowToFresh() {
        // The original bug: Long.MIN_VALUE as cachedAt. now - cachedAt overflowed negative (< ttl) and
        // read as "fresh". The add-based comparison must instead read it as stale (rescan).
        assertTrue(LocalSkinFolder.dueForRescan(NOW, Long.MIN_VALUE),
            "a far-past timestamp must read stale, never overflow into 'fresh'");
    }
}
