package games.brennan.playermob.skin;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-helper tests for {@link PlayerSkinResolver} — the name-vs-UUID detection and the cache key
 * normalisation. The network path is not exercised here (no live server).
 */
class PlayerSkinResolverTest {

    @Test
    void detectsUuids() {
        assertTrue(PlayerSkinResolver.looksLikeUuid(UUID.randomUUID().toString()));
        assertTrue(PlayerSkinResolver.looksLikeUuid("  069a79f4-44e9-4726-a5be-fca90e38aaf5  "));
    }

    @Test
    void treatsUsernamesAsNames() {
        assertFalse(PlayerSkinResolver.looksLikeUuid("Notch"));
        assertFalse(PlayerSkinResolver.looksLikeUuid("jeb_"));
        assertFalse(PlayerSkinResolver.looksLikeUuid(null));
        assertFalse(PlayerSkinResolver.looksLikeUuid(""));
    }

    @Test
    void keyIsTrimmedAndLowerCased() {
        assertEquals("notch", PlayerSkinResolver.key("  Notch "));
        assertEquals(PlayerSkinResolver.key("NOTCH"), PlayerSkinResolver.key("notch"));
    }
}
