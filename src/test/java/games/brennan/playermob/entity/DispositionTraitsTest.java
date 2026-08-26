package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DispositionTraits} — clamping, the explicit-set /
 * roll contract that protects spawn-egg presets from the spawn roll, and NBT
 * round-tripping. {@link CompoundTag} needs a registry bootstrap (same as the
 * other NBT tests in this module).
 */
class DispositionTraitsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void defaultsAreNeutral() {
        DispositionTraits t = new DispositionTraits();
        assertEquals(5, t.fightFlight());
        assertEquals(5, t.friendliness());
        assertEquals(5, t.reactionSpeed());
        assertFalse(t.isExplicit());
    }

    @Test
    void settersClampAndMarkExplicit() {
        DispositionTraits t = new DispositionTraits();
        t.setFightFlight(-3);
        t.setFriendliness(14);
        t.setReactionSpeed(11);
        assertEquals(0, t.fightFlight());
        assertEquals(10, t.friendliness());
        assertEquals(10, t.reactionSpeed());
        assertTrue(t.isExplicit());
    }

    @Test
    void isExplicitNeedsEveryTrait() {
        DispositionTraits t = new DispositionTraits();
        t.setFightFlight(7);
        t.setFriendliness(2);
        assertFalse(t.isExplicit(), "reaction speed is still unset");
        t.setReactionSpeed(9);
        assertTrue(t.isExplicit());
    }

    @Test
    void rollIfUnsetIsNoOpAfterExplicitSet() {
        DispositionTraits t = new DispositionTraits();
        t.set(7, 2, 9);
        t.rollIfUnset(RandomSource.create(42L));
        assertEquals(7, t.fightFlight());
        assertEquals(2, t.friendliness());
        assertEquals(9, t.reactionSpeed());
    }

    @Test
    void rollIfUnsetProducesInRangeValues() {
        for (long seed = 0; seed < 500; seed++) {
            DispositionTraits t = new DispositionTraits();
            t.rollIfUnset(RandomSource.create(seed));
            assertTrue(t.fightFlight() >= 0 && t.fightFlight() <= 10, "ff in range");
            assertTrue(t.friendliness() >= 0 && t.friendliness() <= 10, "fr in range");
            assertTrue(t.reactionSpeed() >= 0 && t.reactionSpeed() <= 10, "rs in range");
        }
    }

    @Test
    void saveLoadRoundTripsAndStaysExplicit() {
        DispositionTraits src = new DispositionTraits();
        src.set(8, 1, 4);
        CompoundTag tag = new CompoundTag();
        src.save(tag);

        DispositionTraits loaded = new DispositionTraits();
        loaded.load(tag);
        assertEquals(8, loaded.fightFlight());
        assertEquals(1, loaded.friendliness());
        assertEquals(4, loaded.reactionSpeed());
        assertTrue(loaded.isExplicit());
        // Explicit ⇒ a later spawn roll leaves it untouched.
        loaded.rollIfUnset(RandomSource.create(99L));
        assertEquals(8, loaded.fightFlight());
        assertEquals(1, loaded.friendliness());
        assertEquals(4, loaded.reactionSpeed());
    }

    @Test
    void partialLoadRollsOnlyTheUnsetTrait() {
        // /summon {FightFlight:7} pins one trait; the other must still roll.
        CompoundTag tag = new CompoundTag();
        tag.putInt(DispositionTraits.TAG_FIGHT_FLIGHT, 7);

        DispositionTraits t = new DispositionTraits();
        t.load(tag);
        assertEquals(7, t.fightFlight());
        assertFalse(t.isExplicit(), "only one trait was set");
        t.rollIfUnset(RandomSource.create(1234L));
        assertEquals(7, t.fightFlight(), "pinned trait stays put");
        assertTrue(t.friendliness() >= 0 && t.friendliness() <= 10, "other trait rolled into range");
        assertTrue(t.reactionSpeed() >= 0 && t.reactionSpeed() <= 10, "reaction speed rolled into range");
    }

    @Test
    void saveMadeBeforeReactionSpeedExistedLoadsNeutral() {
        // A world saved by an earlier build has the two original keys and no ReactionSpeed.
        // finalizeSpawn never re-runs on load, so the missing trait must default to neutral
        // rather than a degenerate 0.
        CompoundTag preReaction = new CompoundTag();
        preReaction.putInt(DispositionTraits.TAG_FIGHT_FLIGHT, 9);
        preReaction.putInt(DispositionTraits.TAG_FRIENDLINESS, 1);

        DispositionTraits t = new DispositionTraits();
        t.load(preReaction);
        assertEquals(9, t.fightFlight());
        assertEquals(1, t.friendliness());
        assertEquals(5, t.reactionSpeed(), "missing key degrades to neutral, not 0");
    }

    @Test
    void legacyTagWithNoTraitKeysKeepsDefaults() {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("PlayerPersonality", 1); // legacy key — must be ignored
        DispositionTraits t = new DispositionTraits();
        t.load(legacy);
        assertEquals(5, t.fightFlight());
        assertEquals(5, t.friendliness());
        assertEquals(5, t.reactionSpeed());
        assertFalse(t.isExplicit());
    }
}
