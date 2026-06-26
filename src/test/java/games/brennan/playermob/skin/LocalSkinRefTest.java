package games.brennan.playermob.skin;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure oracle for {@link LocalSkinRef} — the local-skin scheme encode/decode and the safety guard. */
class LocalSkinRefTest {

    @Test
    void encodeDecodeRoundTrips() {
        String ref = LocalSkinRef.encode("cat");
        assertEquals("playermob-local:cat", ref);
        assertEquals(Optional.of("cat"), LocalSkinRef.decode(ref));
    }

    @Test
    void namesWithSpacesAndDotsSurvive() {
        assertEquals("My Skin.thing", LocalSkinRef.sanitize("My Skin.thing"));
        String ref = LocalSkinRef.encode("My Skin.thing");
        assertEquals(Optional.of("My Skin.thing"), LocalSkinRef.decode(ref));
    }

    @Test
    void decodeRejectsNonLocalValues() {
        assertFalse(LocalSkinRef.decode("http://textures.minecraft.net/texture/abc").isPresent());
        assertFalse(LocalSkinRef.decode("playermob-profile:uuid;name;url").isPresent());
        assertFalse(LocalSkinRef.decode("").isPresent());
        assertFalse(LocalSkinRef.decode(null).isPresent());
    }

    @Test
    void sanitizeRejectsTraversalAndSchemeChars() {
        assertNull(LocalSkinRef.sanitize(null));
        assertNull(LocalSkinRef.sanitize(""));
        assertNull(LocalSkinRef.sanitize("  "));
        assertNull(LocalSkinRef.sanitize("."));
        assertNull(LocalSkinRef.sanitize(".."));
        assertNull(LocalSkinRef.sanitize("../secret"));
        assertNull(LocalSkinRef.sanitize("a/b"));
        assertNull(LocalSkinRef.sanitize("a\\b"));
        assertNull(LocalSkinRef.sanitize("a:b"));
        assertNull(LocalSkinRef.sanitize("a;b"));
        assertNull(LocalSkinRef.sanitize("bad\nname"));
    }

    @Test
    void encodeOfUnsafeNameYieldsEmptyRefThatDecodesToNothing() {
        assertEquals("", LocalSkinRef.encode("../escape"));
        assertTrue(LocalSkinRef.decode(LocalSkinRef.encode("../escape")).isEmpty());
    }
}
