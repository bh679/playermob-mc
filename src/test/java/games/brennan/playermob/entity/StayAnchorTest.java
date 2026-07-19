package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On-disk NBT contract for {@link StayAnchor} — the {@code StayNear} sub-compound a tethered
 * PlayerMob round-trips through save/load and that can be authored in spawn NBT. Covers the
 * position and entity forms, radius clamping on load, and the "no anchor" cases (an empty or
 * pre-feature compound). Live resolution ({@code resolvePos} / {@code label}) needs a world, so
 * it's covered by the in-game Gate 2 smoke test, not here.
 */
class StayAnchorTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void positionAnchorRoundTrips() {
        BlockPos pos = new BlockPos(120, 64, -300);
        StayAnchor saved = StayAnchor.ofPosition(pos, 24);

        CompoundTag tag = new CompoundTag();
        saved.save(tag);

        StayAnchor loaded = StayAnchor.load(tag);
        assertTrue(loaded != null);
        assertFalse(loaded.isEntity());
        assertEquals(24, loaded.radius());
        // A position anchor writes AnchorPos and no AnchorEntity.
        assertTrue(tag.contains("AnchorPos"));
        assertFalse(tag.contains("AnchorEntity"));
    }

    @Test
    void entityAnchorRoundTrips() {
        UUID id = new UUID(0x1234L, 0x5678L);
        StayAnchor saved = StayAnchor.ofEntity(id, 48);

        CompoundTag tag = new CompoundTag();
        saved.save(tag);

        StayAnchor loaded = StayAnchor.load(tag);
        assertTrue(loaded != null);
        assertTrue(loaded.isEntity());
        assertEquals(48, loaded.radius());
        assertTrue(tag.contains("AnchorEntity"));
        assertFalse(tag.contains("AnchorPos"));
    }

    @Test
    void playerNameAnchorRoundTrips() {
        StayAnchor saved = StayAnchor.ofPlayerName("Steve", 16);

        CompoundTag tag = new CompoundTag();
        saved.save(tag);

        StayAnchor loaded = StayAnchor.load(tag);
        assertTrue(loaded != null);
        assertTrue(loaded.isEntity()); // a named player is a live-entity anchor
        assertEquals(16, loaded.radius());
        // A player-name anchor writes AnchorPlayer only.
        assertTrue(tag.contains("AnchorPlayer"));
        assertFalse(tag.contains("AnchorEntity"));
        assertFalse(tag.contains("AnchorPos"));
    }

    @Test
    void blankPlayerNameIsNoAnchor() {
        // A hand-authored AnchorPlayer:"" carries no target — treat as no anchor, not an empty tether.
        CompoundTag tag = new CompoundTag();
        tag.putString("AnchorPlayer", "");
        tag.putInt("Radius", 16);
        assertNull(StayAnchor.load(tag));
    }

    @Test
    void loadPrecedenceEntityOverPlayerOverPos() {
        // AnchorEntity wins over both others.
        UUID id = new UUID(0xAAAAL, 0xBBBBL);
        CompoundTag all = new CompoundTag();
        StayAnchor.ofEntity(id, 20).save(all); // writes AnchorEntity + Radius
        all.putString("AnchorPlayer", "Steve");
        all.putLong("AnchorPos", BlockPos.ZERO.asLong());
        assertTrue(StayAnchor.load(all).isEntity());
        assertEquals(20, StayAnchor.load(all).radius()); // the UUID form's radius, not a default

        // AnchorPlayer wins over AnchorPos when no UUID is present.
        CompoundTag playerAndPos = new CompoundTag();
        playerAndPos.putString("AnchorPlayer", "Alex");
        playerAndPos.putLong("AnchorPos", BlockPos.ZERO.asLong());
        playerAndPos.putInt("Radius", 12);
        StayAnchor loaded = StayAnchor.load(playerAndPos);
        assertTrue(loaded.isEntity()); // resolved as the player-name (live-entity) form, not the position
        assertEquals(12, loaded.radius());
    }

    @Test
    void radiusIsClampedOnLoad() {
        // A hand-authored / tampered NBT with an out-of-range radius is clamped, not trusted.
        CompoundTag tooBig = new CompoundTag();
        tooBig.putLong("AnchorPos", BlockPos.ZERO.asLong());
        tooBig.putInt("Radius", 10_000);
        assertEquals(StayNearPolicy.MAX_RADIUS, StayAnchor.load(tooBig).radius());

        CompoundTag tooSmall = new CompoundTag();
        tooSmall.putLong("AnchorPos", BlockPos.ZERO.asLong());
        tooSmall.putInt("Radius", 0);
        assertEquals(StayNearPolicy.MIN_RADIUS, StayAnchor.load(tooSmall).radius());
    }

    @Test
    void missingRadiusDefaults() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("AnchorPos", BlockPos.ZERO.asLong());
        assertEquals(StayNearPolicy.DEFAULT_RADIUS, StayAnchor.load(tag).radius());
    }

    @Test
    void emptyOrAnchorlessCompoundIsNoAnchor() {
        assertNull(StayAnchor.load(new CompoundTag()));

        // A radius with neither anchor key is still "no anchor" — nothing to tether to.
        CompoundTag radiusOnly = new CompoundTag();
        radiusOnly.putInt("Radius", 16);
        assertNull(StayAnchor.load(radiusOnly));
    }
}
