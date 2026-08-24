package games.brennan.playermob.player;

import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.FeelingRecord;
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
            .credit(Signal.GIFT, 2.5F)  // +2.5 (uses magnitude)
            .credit(Signal.TAME, 0);    // +2.0
        assertEquals(0.5F + 0.2F + 1.0F + 2.5F + 2.0F, r.kindness(), EPS);
    }

    @Test
    void tamingRaisesFriendlinessTwoPointsEach() {
        DispositionTraits one = PlayerLifeRecord.EMPTY
            .credit(Signal.TAME, 0)
            .toTraits();
        assertEquals(DispositionTraits.DEFAULT + 2, one.friendliness());
        assertEquals(DispositionTraits.DEFAULT, one.fightFlight()); // taming is not combat

        // Two tames reach the friendliness a defend-heavy life needs four gestures for.
        DispositionTraits two = PlayerLifeRecord.EMPTY
            .credit(Signal.TAME, 0)
            .credit(Signal.TAME, 0)
            .toTraits();
        assertEquals(DispositionTraits.DEFAULT + 4, two.friendliness());
    }

    @Test
    void tamingOffsetsTheCrueltyOfAKill() {
        // One kill costs 2.0 cruelty; one tame gives 2.0 kindness — they cancel exactly.
        DispositionTraits t = PlayerLifeRecord.EMPTY
            .credit(Signal.KILL, 0)
            .credit(Signal.TAME, 0)
            .toTraits();
        assertEquals(DispositionTraits.DEFAULT, t.friendliness());
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

    // ---- self-defence discount ----

    @Test
    void defensiveKillScoresATenthOfTheSameUnprovokedKill() {
        // Same 20-damage kill, but the mob picked the fight: aggression 25 → 2.5, cruelty 4 → 0.4.
        DispositionTraits t = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 20.0F, true)
            .credit(Signal.KILL, 0, true)
            .toTraits();
        // ff = round(5 + 2.5*0.1) = round(5.25) = 5 ; fr = round(5 - 0.4) = round(4.6) = 5
        assertEquals(DispositionTraits.DEFAULT, t.fightFlight());
        assertEquals(DispositionTraits.DEFAULT, t.friendliness());
    }

    @Test
    void defensiveTalliesAreSubsetsOfTheTotals() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 6.0F, true)
            .credit(Signal.ATTACK, 4.0F, false)
            .credit(Signal.KILL, 0, true)
            .credit(Signal.KILL, 0, false);
        assertEquals(10.0F, r.damageDealt(), EPS);   // both blows counted in the total
        assertEquals(6.0F, r.defensiveDamage(), EPS);
        assertEquals(2, r.kills());
        assertEquals(1, r.defensiveKills());
        assertEquals(2, r.attacks());
    }

    @Test
    void aLifeOfPureSelfDefenceStaysNearNeutral() {
        // Five mobs hunted this player down; they killed all five. weighted damage 10,
        // weighted kills 0.5 → aggression 12.5, cruelty 2.0.
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY;
        for (int i = 0; i < 5; i++) {
            r = r.credit(Signal.ATTACK, 20.0F, true).credit(Signal.KILL, 0, true);
        }
        DispositionTraits t = r.toTraits();
        assertEquals(6, t.fightFlight());   // round(5 + 1.25) — vs MAX for the same unprovoked spree
        assertEquals(3, t.friendliness());  // round(5 - 2.0) — vs MIN unprovoked
    }

    @Test
    void mixedLifeWeighsEachBloodOnItsOwnTerms() {
        // One kill started, one survived. Offensive 20 damage + 1 kill = 25 aggression;
        // defensive adds 20*0.1 + 5*0.1 = 2.5 → 27.5. Cruelty 4 + 0.4 = 4.4.
        DispositionTraits t = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 20.0F, false).credit(Signal.KILL, 0, false)
            .credit(Signal.ATTACK, 20.0F, true).credit(Signal.KILL, 0, true)
            .toTraits();
        assertEquals(8, t.fightFlight());   // round(5 + 2.75)
        assertEquals(1, t.friendliness());  // round(5 - 4.4)
    }

    @Test
    void defensiveFlagIsIgnoredForNonCombatSignals() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY
            .credit(Signal.GIFT, 2.0F, true)
            .credit(Signal.HARM, 0, true);
        assertEquals(2.0F, r.kindness(), EPS);
        assertEquals(1, r.harms());
        assertEquals(0.0F, r.defensiveDamage(), EPS);
        assertEquals(0, r.defensiveKills());
    }

    @Test
    void unprovokedScoringIsUnchangedByTheSplit() {
        // Regression guard: with nothing marked defensive, traits match the pre-split formula.
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 13.0F)
            .credit(Signal.KILL, 0)
            .credit(Signal.HARM, 0);
        double aggression = 13.0 + 1 * 5.0;
        double cruelty = 13.0 * 0.1 + 1 * 2.0 + 1 * 1.0;
        assertEquals((int) Math.round(5 + aggression * 0.1), r.toTraits().fightFlight());
        assertEquals((int) Math.round(5 + 0 - cruelty), r.toTraits().friendliness());
    }

    // ---- persistence ----

    // ---- timidity: restraint pushes toward Flight ----

    @Test
    void enduredDamageLowersFightFlightAndLeavesFriendlinessAlone() {
        DispositionTraits traits = PlayerLifeRecord.EMPTY.credit(Signal.FLEE, 10.0F).toTraits();
        assertEquals(DispositionTraits.DEFAULT - 1, traits.fightFlight());
        assertEquals(DispositionTraits.DEFAULT, traits.friendliness());
    }

    @Test
    void oneCleanEscapeIsWorthOneSolidBeatingEndured() {
        DispositionTraits escaped =
            PlayerLifeRecord.EMPTY.credit(Signal.FLEE, FeelingRecord.ESCAPE_TIMIDITY).toTraits();
        DispositionTraits endured = PlayerLifeRecord.EMPTY.credit(Signal.FLEE, 10.0F).toTraits();
        assertEquals(endured.fightFlight(), escaped.fightFlight());
    }

    @Test
    void aLifeOfPureFleeingClampsToFullFlight() {
        PlayerLifeRecord timid = PlayerLifeRecord.EMPTY;
        for (int i = 0; i < 10; i++) {
            timid = timid.credit(Signal.FLEE, FeelingRecord.ESCAPE_TIMIDITY);
        }
        DispositionTraits traits = timid.toTraits();
        assertEquals(DispositionTraits.MIN, traits.fightFlight());
        // Fleeing says nothing about warmth — only Fight/Flight moves.
        assertEquals(DispositionTraits.DEFAULT, traits.friendliness());
    }

    @Test
    void damageDealtAndDamageEnduredCancelPointForPoint() {
        DispositionTraits traits = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 10.0F)
            .credit(Signal.FLEE, 10.0F)
            .toTraits();
        assertEquals(DispositionTraits.DEFAULT, traits.fightFlight());
    }

    @Test
    void aNegativeFleeHandsBackWhatWasBanked() {
        PlayerLifeRecord banked = PlayerLifeRecord.EMPTY.credit(Signal.FLEE, 12.0F);
        assertEquals(12.0F, banked.timidity(), EPS);
        PlayerLifeRecord forfeited = banked.credit(Signal.FLEE, -12.0F);
        assertEquals(0.0F, forfeited.timidity(), EPS);
        assertEquals(DispositionTraits.DEFAULT, forfeited.toTraits().fightFlight());
    }

    @Test
    void anOverLargeForfeitFloorsAtZeroRatherThanTurningAggressive() {
        PlayerLifeRecord forfeited = PlayerLifeRecord.EMPTY
            .credit(Signal.FLEE, 5.0F)
            .credit(Signal.FLEE, -50.0F);
        assertEquals(0.0F, forfeited.timidity(), EPS);
        assertEquals(DispositionTraits.DEFAULT, forfeited.toTraits().fightFlight());
    }

    @Test
    void timidityCountsTowardNonEmptiness() {
        assertFalse(PlayerLifeRecord.EMPTY.credit(Signal.FLEE, 3.0F).isEmpty());
    }

    @Test
    void fleeLeavesEveryOtherTallyUntouched() {
        PlayerLifeRecord fled = PlayerLifeRecord.EMPTY.credit(Signal.FLEE, 7.0F);
        assertEquals(0.0F, fled.damageDealt(), EPS);
        assertEquals(0, fled.kills());
        assertEquals(0, fled.attacks());
        assertEquals(0.0F, fled.kindness(), EPS);
        assertEquals(0, fled.harms());
    }

    @Test
    void nbtRoundTripCarriesTimidity() {
        CompoundTag tag = new CompoundTag();
        PlayerLifeRecord.EMPTY.credit(Signal.FLEE, 14.0F).save(tag);
        assertEquals(14.0F, PlayerLifeRecord.load(tag).timidity(), EPS);
    }

    @Test
    void legacyTagWithoutTheTimidityKeyLoadsAsUnflinching() {
        CompoundTag tag = new CompoundTag();
        PlayerLifeRecord.EMPTY.credit(Signal.ATTACK, 20.0F).save(tag);
        tag.remove(PlayerLifeRecord.TAG_TIMIDITY);
        PlayerLifeRecord loaded = PlayerLifeRecord.load(tag);
        assertEquals(0.0F, loaded.timidity(), EPS);
        assertEquals(DispositionTraits.DEFAULT + 2, loaded.toTraits().fightFlight());
    }

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
        assertEquals(r.defensiveDamage(), back.defensiveDamage(), EPS);
        assertEquals(r.defensiveKills(), back.defensiveKills());
    }

    @Test
    void nbtRoundTripCarriesTheDefensiveSplit() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY
            .credit(Signal.ATTACK, 8.0F, true)
            .credit(Signal.KILL, 0, true)
            .credit(Signal.ATTACK, 2.0F, false);
        CompoundTag tag = new CompoundTag();
        r.save(tag);
        PlayerLifeRecord back = PlayerLifeRecord.load(tag);
        assertEquals(8.0F, back.defensiveDamage(), EPS);
        assertEquals(1, back.defensiveKills());
        assertEquals(10.0F, back.damageDealt(), EPS);
        assertEquals(r.toTraits().fightFlight(), back.toTraits().fightFlight());
        assertEquals(r.toTraits().friendliness(), back.toTraits().friendliness());
    }

    @Test
    void legacyTagWithoutTheDefensiveKeysLoadsAsFullyOffensive() {
        // A save written before the split: only the old keys are present.
        CompoundTag tag = new CompoundTag();
        tag.putFloat("DamageDealt", 20.0F);
        tag.putInt("Kills", 1);
        tag.putInt("Attacks", 3);
        PlayerLifeRecord back = PlayerLifeRecord.load(tag);
        assertEquals(0.0F, back.defensiveDamage(), EPS);
        assertEquals(0, back.defensiveKills());
        assertEquals(8, back.toTraits().fightFlight());   // scores exactly as that build scored it
        assertEquals(1, back.toTraits().friendliness());
    }

    @Test
    void loadFromEmptyTagIsEmptyRecord() {
        assertTrue(PlayerLifeRecord.load(new CompoundTag()).isEmpty());
    }
}
