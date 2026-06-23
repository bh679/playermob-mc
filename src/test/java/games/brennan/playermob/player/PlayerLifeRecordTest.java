package games.brennan.playermob.player;

import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.player.PlayerLifeRecord.Signal;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link PlayerLifeRecord} — accumulation of player→mob
 * conduct and its distillation into reincarnation traits. No Minecraft world
 * (primitives + a {@link CompoundTag}), like {@code FeelingRecordTest}.
 */
class PlayerLifeRecordTest {

    private static final float EPS = 1e-4f;

    @Test
    void emptyIsNeutral() {
        assertTrue(PlayerLifeRecord.EMPTY.isEmpty());
        DispositionTraits traits = PlayerLifeRecord.EMPTY.toTraits();
        assertEquals(DispositionTraits.DEFAULT, traits.fightFlight());
        assertEquals(DispositionTraits.DEFAULT, traits.friendliness());
    }

    // ---- accumulation + immutability ----

    @Test
    void creditReturnsFreshRecordAndLeavesOriginalUntouched() {
        PlayerLifeRecord hit = PlayerLifeRecord.EMPTY.credit(Signal.ATTACK, 4.0F);
        assertNotSame(PlayerLifeRecord.EMPTY, hit);
        assertEquals(0.0F, PlayerLifeRecord.EMPTY.damageDealt(), EPS);
        assertEquals(0, PlayerLifeRecord.EMPTY.attacks());
        assertEquals(4.0F, hit.damageDealt(), EPS);
        assertEquals(1, hit.attacks());
        assertFalse(hit.isEmpty());
    }

    @Test
    void attackAccumulatesDamageAndCount() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 3.0F)
            .credit(Signal.ATTACK, 5.0F);
        assertEquals(8.0F, r.damageDealt(), EPS);
        assertEquals(2, r.attacks());
    }

    @Test
    void kindSignalsRouteToKindness() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY
            .credit(Signal.CROUCH, 0)   // +0.5
            .credit(Signal.TRAVEL, 0)   // +0.2
            .credit(Signal.DEFEND, 0)   // +1.0
            .credit(Signal.GIFT, 2.5F); // +2.5 (uses magnitude)
        assertEquals(0.5F + 0.2F + 1.0F + 2.5F, r.kindness(), EPS);
    }

    // ---- trait derivation ----

    @Test
    void aggressionRaisesFightFlightOnly() {
        // 20-damage killing blow + the kill itself: aggression 20 + 5 = 25.
        DispositionTraits t = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 20.0F)
            .credit(Signal.KILL, 0)
            .toTraits();
        // ff = round(5 + 25*0.1) = round(7.5) = 8 ; fr = clamp(5 - (2 + 2)) = 1
        assertEquals(8, t.fightFlight());
        assertEquals(1, t.friendliness());
    }

    @Test
    void aSlaughterClampsToMaxFightMinFriendly() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY;
        for (int i = 0; i < 5; i++) {
            r = r.credit(Signal.ATTACK, 20.0F).credit(Signal.KILL, 0);
        }
        DispositionTraits t = r.toTraits(); // aggression 125, cruelty 20
        assertEquals(DispositionTraits.MAX, t.fightFlight());
        assertEquals(DispositionTraits.MIN, t.friendliness());
    }

    @Test
    void kindnessRaisesFriendlinessLeavesFightNeutral() {
        DispositionTraits t = PlayerLifeRecord.EMPTY
            .credit(Signal.GIFT, 3.0F)
            .credit(Signal.CROUCH, 0)
            .credit(Signal.CROUCH, 0)
            .credit(Signal.DEFEND, 0)
            .toTraits(); // kindness 3 + 0.5 + 0.5 + 1.0 = 5
        assertEquals(DispositionTraits.DEFAULT, t.fightFlight()); // no combat → neutral
        assertEquals(DispositionTraits.MAX, t.friendliness());    // clamp(5 + 5) = 10
    }

    @Test
    void modestKindnessReachesLove() {
        DispositionTraits t = PlayerLifeRecord.EMPTY
            .credit(Signal.GIFT, 1.5F)
            .credit(Signal.CROUCH, 0)
            .toTraits(); // kindness 2.0 → friendliness 7
        assertEquals(7, t.friendliness());
    }

    @Test
    void harmingLovedOnesErodesFriendliness() {
        DispositionTraits t = PlayerLifeRecord.EMPTY
            .credit(Signal.HARM, 0)
            .credit(Signal.HARM, 0)
            .toTraits(); // cruelty 2 → friendliness 3
        assertEquals(DispositionTraits.DEFAULT, t.fightFlight()); // harm isn't aggression
        assertEquals(3, t.friendliness());
    }

    // ---- persistence ----

    @Test
    void nbtRoundTrip() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 12.5F)
            .credit(Signal.KILL, 0)
            .credit(Signal.GIFT, 2.0F)
            .credit(Signal.HARM, 0);
        CompoundTag tag = new CompoundTag();
        r.save(tag);
        PlayerLifeRecord back = PlayerLifeRecord.load(tag);
        assertEquals(r.damageDealt(), back.damageDealt(), EPS);
        assertEquals(r.kills(), back.kills());
        assertEquals(r.kindness(), back.kindness(), EPS);
        assertEquals(r.harms(), back.harms());
        assertEquals(r.attacks(), back.attacks());
    }

    @Test
    void loadFromEmptyTagIsEmptyRecord() {
        assertTrue(PlayerLifeRecord.load(new CompoundTag()).isEmpty());
    }
}
