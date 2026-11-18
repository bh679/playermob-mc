package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.ReactionChannel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outdated echo snapshots roll random reaction speeds; current ones pass through untouched. */
class EchoReactionUpgradeTest {

    private static CompoundTag oldSnapshot() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(DispositionTraits.TAG_FIGHT_FLIGHT, 7);
        tag.putInt(DispositionTraits.TAG_FRIENDLINESS, 3);
        tag.putInt(DispositionTraits.TAG_REACTION_SPEED, 5);   // the old pinned value
        return tag;
    }

    @Test
    void anOldSnapshotRollsEveryReactionSpeedInRange() {
        Set<Integer> overalls = new HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            CompoundTag up = EchoReactionUpgrade.upgrade(oldSnapshot(), RandomSource.create(seed));
            int overall = NbtCompat.getIntOr(up, DispositionTraits.TAG_REACTION_SPEED, -1);
            assertTrue(overall >= 0 && overall <= 10);
            overalls.add(overall);
            CompoundTag channels = NbtCompat.getCompoundOrEmpty(up, DispositionTraits.TAG_REACTION_SPEEDS);
            for (ReactionChannel channel : ReactionChannel.SPECIFIC) {
                int v = NbtCompat.getIntOr(channels, channel.key(), -1);
                assertTrue(v >= 0 && v <= 10, channel.name());
            }
            assertEquals(7, NbtCompat.getIntOr(up, DispositionTraits.TAG_FIGHT_FLIGHT, -1), "other traits kept");
        }
        assertTrue(overalls.size() > 5, "values vary across echoes, not a fixed 5");
    }

    @Test
    void theInputSnapshotIsNeverModified() {
        CompoundTag old = oldSnapshot();
        EchoReactionUpgrade.upgrade(old, RandomSource.create(1));
        assertFalse(old.contains(DispositionTraits.TAG_REACTION_SPEEDS));
        assertEquals(5, NbtCompat.getIntOr(old, DispositionTraits.TAG_REACTION_SPEED, -1));
    }

    @Test
    void aCurrentSnapshotPassesThroughUntouched() {
        DispositionTraits traits = new DispositionTraits();
        traits.set(5, 5, 4);
        traits.setReactionSpeed(ReactionChannel.MELEE, 9);
        CompoundTag current = new CompoundTag();
        traits.save(current);
        // A snapshot written now carries every channel, so it is never re-rolled.
        PlayerLifeRecord.EMPTY.toTraits().save(current);
        assertFalse(EchoReactionUpgrade.isOutdated(current));
        assertSame(current, EchoReactionUpgrade.upgrade(current, RandomSource.create(1)));
    }

    @Test
    void anUpgradedEchoKeepsItsRolledValuesThroughTheSpawnRoll() {
        CompoundTag up = EchoReactionUpgrade.upgrade(oldSnapshot(), RandomSource.create(42));
        DispositionTraits echo = new DispositionTraits();
        echo.load(up);
        echo.rollIfUnset(RandomSource.create(7));
        assertEquals(NbtCompat.getIntOr(up, DispositionTraits.TAG_REACTION_SPEED, -1), echo.reactionSpeed());
        CompoundTag channels = NbtCompat.getCompoundOrEmpty(up, DispositionTraits.TAG_REACTION_SPEEDS);
        for (ReactionChannel channel : ReactionChannel.SPECIFIC) {
            assertEquals(NbtCompat.getIntOr(channels, channel.key(), -1), echo.reactionSpeed(channel));
        }
    }
}
