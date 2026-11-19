package games.brennan.playermob.entity;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for {@link TamingPolicy} and the {@code Taming} trait key. */
class TamingPolicyTest {

    @Test
    void chanceScalesWithScore() {
        assertEquals(0.0, TamingPolicy.scanChance(0), 1e-9);
        assertEquals(0.3, TamingPolicy.scanChance(3), 1e-9);
        assertEquals(1.0, TamingPolicy.scanChance(10), 1e-9);
        assertFalse(TamingPolicy.rollsToAct(0, 0.0));
        assertTrue(TamingPolicy.rollsToAct(10, 0.999));
        assertTrue(TamingPolicy.rollsToAct(5, 0.49));
        assertFalse(TamingPolicy.rollsToAct(5, 0.5));
    }

    @Test
    void feedsAReadyAdultWithAPartner() {
        assertTrue(TamingPolicy.mayFeed(false, 0, false, true, false, 2, true));
    }

    @Test
    void wontBreedWithoutAPartnerOrAtTheHerdCap() {
        assertFalse(TamingPolicy.mayFeed(false, 0, false, true, false, 1, false));
        assertFalse(TamingPolicy.mayFeed(false, 0, false, true, false, TamingPolicy.HERD_CAP, true));
    }

    @Test
    void wontFeedAnAnimalInLoveOrOnCooldown() {
        assertFalse(TamingPolicy.mayFeed(false, 0, true, false, false, 2, true));
        assertFalse(TamingPolicy.mayFeed(false, 6000, false, true, false, 2, true));
    }

    @Test
    void feedsBabiesEvenAtTheHerdCap() {
        assertTrue(TamingPolicy.mayFeed(true, -24000, false, true, false, 20, false));
    }

    @Test
    void neverFeedsAWildTamable() {
        assertFalse(TamingPolicy.mayFeed(false, 0, false, true, true, 2, true));
        assertFalse(TamingPolicy.mayFeed(true, -24000, false, true, true, 2, true));
        assertFalse(TamingPolicy.isPartner(false, 0, false, true, true));
    }

    @Test
    void partnerIsAnAdultInLoveOrReady() {
        assertTrue(TamingPolicy.isPartner(false, 0, true, false, false));
        assertTrue(TamingPolicy.isPartner(false, 0, false, true, false));
        assertFalse(TamingPolicy.isPartner(true, -100, false, true, false));
        assertFalse(TamingPolicy.isPartner(false, 6000, false, true, false));
    }

    @Test
    void tamingTraitDefaultsToZeroAndRoundTrips() {
        DispositionTraits wild = new DispositionTraits();
        assertEquals(0, wild.taming());
        wild.rollIfUnset(net.minecraft.util.RandomSource.create(1));
        assertEquals(0, wild.taming(), "never rolled");

        DispositionTraits echo = new DispositionTraits();
        echo.setTaming(7);
        CompoundTag tag = new CompoundTag();
        echo.save(tag);
        DispositionTraits back = new DispositionTraits();
        back.load(tag);
        assertEquals(7, back.taming());

        DispositionTraits old = new DispositionTraits();
        old.load(new CompoundTag());
        assertEquals(0, old.taming(), "pre-feature save");
        DispositionTraits clamped = new DispositionTraits();
        clamped.setTaming(42);
        assertEquals(10, clamped.taming());
    }
}
