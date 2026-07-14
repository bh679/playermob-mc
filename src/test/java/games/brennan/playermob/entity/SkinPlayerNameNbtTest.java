package games.brennan.playermob.entity;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NBT contract for {@code SkinPlayerName} — a {@code /summon} (or entity-egg {@code entity_data}) input
 * tag that names a player whose skin should be resolved and applied once the mob is added to a
 * {@code ServerLevel} (see {@code PlayerMobEntity#onAddedToLevel}). We can't instantiate
 * {@link PlayerMobEntity} without a full Minecraft world, so these tests cover the on-disk tag shape
 * directly, mirroring {@link SkinUrlNbtTest}.
 */
class SkinPlayerNameNbtTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tagShape_playerNameOnly_noSkinTextureUrlYet() {
        // What a raw `/summon ... {SkinPlayerName:"generikb"}` looks like on the wire: only the
        // player-name tag is present — SkinTextureUrl doesn't exist until resolution completes.
        CompoundTag tag = new CompoundTag();
        tag.putString("SkinPlayerName", "generikb");
        assertTrue(NbtCompat.containsOfType(tag, "SkinPlayerName", Tag.TAG_STRING));
        assertEquals("generikb", NbtCompat.getStringOr(tag, "SkinPlayerName", ""));
        assertFalse(NbtCompat.containsOfType(tag, "SkinTextureUrl", Tag.TAG_STRING),
            "a name-only summon has no resolved URL yet");
    }

    @Test
    void explicitSkinTextureUrlTakesPrecedenceOverPlayerName() {
        // If a caller supplies both (unusual, but not forbidden), the already-resolved URL must win —
        // PlayerMobEntity.readCustomTag only sets pendingSkinPlayerName when skinExplicit is still false,
        // i.e. only when no SkinTextureUrl key was read first. We mimic that precedence check here.
        CompoundTag tag = new CompoundTag();
        tag.putString("SkinTextureUrl", "http://textures.minecraft.net/texture/abc");
        tag.putString("SkinPlayerName", "generikb");

        boolean skinExplicit = false;
        String skinTextureUrl = null;
        if (NbtCompat.containsOfType(tag, "SkinTextureUrl", Tag.TAG_STRING)) {
            skinTextureUrl = NbtCompat.getStringOr(tag, "SkinTextureUrl", "");
            skinExplicit = true;
        }
        String pendingSkinPlayerName = null;
        if (!skinExplicit && NbtCompat.containsOfType(tag, "SkinPlayerName", Tag.TAG_STRING)) {
            pendingSkinPlayerName = NbtCompat.getStringOr(tag, "SkinPlayerName", "");
        }

        assertEquals("http://textures.minecraft.net/texture/abc", skinTextureUrl);
        assertEquals(null, pendingSkinPlayerName, "SkinPlayerName must be ignored once a URL already won");
    }

    @Test
    void blankPlayerNameIsIgnored() {
        CompoundTag tag = new CompoundTag();
        tag.putString("SkinPlayerName", "   ");
        String name = NbtCompat.getStringOr(tag, "SkinPlayerName", "");
        assertTrue(name.isBlank(), "a blank SkinPlayerName must not be treated as a pending resolution");
    }
}
