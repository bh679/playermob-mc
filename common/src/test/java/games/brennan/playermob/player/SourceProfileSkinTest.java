package games.brennan.playermob.player;

import games.brennan.playermob.player.SourceProfileSkin.Ref;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure oracle for {@link SourceProfileSkin} — the source-player marker carried in the
 * mob's SkinTextureUrl field. No Minecraft, like the other entity/player oracles.
 */
class SourceProfileSkinTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void roundTrip() {
        String encoded = SourceProfileSkin.encode(ID, "Brennan");
        assertTrue(encoded.startsWith(SourceProfileSkin.PREFIX));
        Optional<Ref> ref = SourceProfileSkin.decode(encoded);
        assertTrue(ref.isPresent());
        assertEquals(ID, ref.get().uuid());
        assertEquals("Brennan", ref.get().name());
    }

    @Test
    void emptyNameRoundTrips() {
        Optional<Ref> ref = SourceProfileSkin.decode(SourceProfileSkin.encode(ID, ""));
        assertTrue(ref.isPresent());
        assertEquals("", ref.get().name());
    }

    @Test
    void realUrlIsNotAProfileRef() {
        assertTrue(SourceProfileSkin.decode("http://textures.minecraft.net/texture/abc").isEmpty());
    }

    @Test
    void emptyAndNullAreEmpty() {
        assertTrue(SourceProfileSkin.decode("").isEmpty());
        assertTrue(SourceProfileSkin.decode(null).isEmpty());
    }

    @Test
    void malformedAreEmpty() {
        assertTrue(SourceProfileSkin.decode(SourceProfileSkin.PREFIX + "not-a-uuid;Name").isEmpty());
        assertTrue(SourceProfileSkin.decode(SourceProfileSkin.PREFIX + "missing-separator").isEmpty());
    }
}
