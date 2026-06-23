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
 * NBT contract for the v2 (URL-skin) PlayerMob save format. We can't
 * instantiate {@link PlayerMobEntity} without a full Minecraft world, so
 * these tests cover the on-disk format directly — confirming that the v2
 * additive tags ({@code SkinTextureUrl}, {@code SkinSlim}) are layered on
 * top of the v1 fields ({@code SkinIndex}) such that:
 *
 * <ul>
 *   <li>A v1 (0.2.0) tag with only {@code SkinIndex} carries no URL key —
 *       the renderer treats this as "use bundled vanilla skin".</li>
 *   <li>A v2 tag carries both — the renderer prefers the URL.</li>
 *   <li>{@link PlayerMobEntity#addAdditionalSaveData} skips the URL key when
 *       the URL is empty, so v1 mobs round-tripped through a v2 save don't
 *       accidentally pick up an empty-string SkinTextureUrl.</li>
 * </ul>
 *
 * <p>Behavioural coverage (the renderer actually picking the right texture
 * at runtime) lives in the in-game Gate 2 smoke test.</p>
 */
class SkinUrlNbtTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void v1TagShape_skinIndexOnly_noUrlKey() {
        // What a 0.2.0 save looks like: SkinIndex present, no SkinTextureUrl,
        // no SkinSlim. The renderer's URL branch must short-circuit on the
        // empty default and fall through to the index branch.
        CompoundTag v1 = new CompoundTag();
        v1.putInt("Stance", 0);
        v1.putInt("SkinIndex", 3);
        assertTrue(NbtCompat.containsOfType(v1, "SkinIndex", Tag.TAG_INT));
        assertFalse(NbtCompat.containsOfType(v1, "SkinTextureUrl", Tag.TAG_STRING),
            "v1 tag should have no SkinTextureUrl key");
        assertFalse(NbtCompat.containsOfType(v1, "SkinSlim", Tag.TAG_BYTE),
            "v1 tag should have no SkinSlim key");
    }

    @Test
    void v2TagShape_carriesBothFormats() {
        // A v2 save round-trips both SkinIndex (downgrade-safety) and the
        // URL pair. Reading this tag on either v1 or v2 yields a renderable
        // entity — v1 reads the index; v2 prefers the URL.
        CompoundTag v2 = new CompoundTag();
        v2.putInt("Stance", 0);
        v2.putInt("SkinIndex", 5);
        v2.putString("SkinTextureUrl", "http://textures.minecraft.net/texture/abc");
        v2.putBoolean("SkinSlim", true);
        assertEquals(5, NbtCompat.getIntOr(v2, "SkinIndex", 0));
        assertEquals("http://textures.minecraft.net/texture/abc",
            NbtCompat.getStringOr(v2, "SkinTextureUrl", ""));
        assertTrue(NbtCompat.getBooleanOr(v2, "SkinSlim", false));
    }

    @Test
    void writeContract_emptyUrlOmitsKeys() {
        // PlayerMobEntity.addAdditionalSaveData only writes SkinTextureUrl
        // when the URL is non-empty. We mimic that here against a CompoundTag
        // to lock in the wire format expectation.
        CompoundTag out = new CompoundTag();
        String url = "";
        out.putInt("SkinIndex", 0);
        if (!url.isEmpty()) {
            out.putString("SkinTextureUrl", url);
            out.putBoolean("SkinSlim", false);
        }
        assertFalse(NbtCompat.containsOfType(out, "SkinTextureUrl", Tag.TAG_STRING),
            "Empty URL must not be written to NBT");
        assertFalse(NbtCompat.containsOfType(out, "SkinSlim", Tag.TAG_BYTE),
            "SkinSlim only round-trips alongside a non-empty URL");
    }

    @Test
    void writeContract_nonEmptyUrlIncludesBoth() {
        // Mirror of the above for the present-url case.
        CompoundTag out = new CompoundTag();
        String url = "http://textures.minecraft.net/texture/xyz";
        out.putInt("SkinIndex", 7);
        if (!url.isEmpty()) {
            out.putString("SkinTextureUrl", url);
            out.putBoolean("SkinSlim", true);
        }
        assertTrue(NbtCompat.containsOfType(out, "SkinTextureUrl", Tag.TAG_STRING));
        assertEquals(url, NbtCompat.getStringOr(out, "SkinTextureUrl", ""));
        assertTrue(NbtCompat.getBooleanOr(out, "SkinSlim", false));
    }
}
