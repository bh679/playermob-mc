package games.brennan.playermob.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-channel reaction speeds on a mob: fallback, storage, editing. */
class DispositionTraitsChannelTest {

    @Test
    void aMobWithoutChannelValuesReadsTheOverallTraitEverywhere() {
        DispositionTraits t = new DispositionTraits();
        t.setReactionSpeed(8);
        for (ReactionChannel channel : ReactionChannel.values()) {
            assertEquals(8, t.reactionSpeed(channel), channel.name());
        }
        assertFalse(t.hasOwnReactionSpeed(ReactionChannel.MELEE));
    }

    @Test
    void aChannelValueOverridesOnlyThatChannel() {
        DispositionTraits t = new DispositionTraits();
        t.setReactionSpeed(5);
        t.setReactionSpeed(ReactionChannel.RANGED, 2);
        assertEquals(2, t.reactionSpeed(ReactionChannel.RANGED));
        assertEquals(5, t.reactionSpeed(ReactionChannel.MELEE));
        assertEquals(5, t.reactionSpeed());
        t.setReactionSpeed(ReactionChannel.MELEE, 42);
        assertEquals(10, t.reactionSpeed(ReactionChannel.MELEE), "clamped");
    }

    @Test
    void channelsRoundTripAndSurviveTheSpawnRoll() {
        DispositionTraits t = new DispositionTraits();
        t.set(6, 4, 7);
        t.setReactionSpeed(ReactionChannel.BUILDING, 9);
        t.setReactionSpeed(ReactionChannel.DEFENCE, 1);
        CompoundTag tag = new CompoundTag();
        t.save(tag);
        DispositionTraits echo = new DispositionTraits();
        echo.load(tag);
        echo.rollIfUnset(RandomSource.create(3));
        assertEquals(7, echo.reactionSpeed());
        assertEquals(9, echo.reactionSpeed(ReactionChannel.BUILDING));
        assertEquals(1, echo.reactionSpeed(ReactionChannel.DEFENCE));
        assertEquals(7, echo.reactionSpeed(ReactionChannel.ITEMS), "unset channel follows overall");
    }

    @Test
    void aSaveWithoutChannelsWritesNoChannelTag() {
        DispositionTraits t = new DispositionTraits();
        t.set(5, 5, 5);
        CompoundTag tag = new CompoundTag();
        t.save(tag);
        assertFalse(tag.contains(DispositionTraits.TAG_REACTION_SPEEDS));
    }

    @Test
    void theEditorShiftsOverallAndEveryOwnChannelTogether() {
        DispositionTraits t = new DispositionTraits();
        t.setReactionSpeed(5);
        t.setReactionSpeed(ReactionChannel.MELEE, 9);
        t.setReactionSpeed(ReactionChannel.RANGED, 2);
        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_UP, t));
        assertEquals(6, t.reactionSpeed());
        assertEquals(10, t.reactionSpeed(ReactionChannel.MELEE));
        assertEquals(3, t.reactionSpeed(ReactionChannel.RANGED));
        assertTrue(TraitEditButtons.apply(TraitEditButtons.REACTION_SPEED_UP, t));
        assertEquals(10, t.reactionSpeed(ReactionChannel.MELEE), "clamped at the top");
        assertEquals(7, t.reactionSpeed(ReactionChannel.ITEMS), "unset channel follows overall");
    }
}
