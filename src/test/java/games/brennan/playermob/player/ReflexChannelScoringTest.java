package games.brennan.playermob.player;

import games.brennan.playermob.entity.ReactionChannel;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-channel reaction speeds: each leans on the overall score and moves only with its own evidence. */
class ReflexChannelScoringTest {

    private static final double EPS = 1e-6;

    private static ReflexTally repeat(int n, java.util.function.UnaryOperator<ReflexTally> f) {
        ReflexTally t = ReflexTally.EMPTY;
        for (int i = 0; i < n; i++) t = f.apply(t);
        return t;
    }

    @Test
    void aChannelWithNoEvidenceEqualsTheOverallScore() {
        ReflexTally fast = repeat(30, t -> t.withStrikeBack(0));
        int overall = fast.reactionSpeed();
        assertTrue(overall > 5);
        for (ReactionChannel channel : new ReactionChannel[] {
                ReactionChannel.RANGED, ReactionChannel.DEFENCE, ReactionChannel.BUILDING,
                ReactionChannel.MOVEMENT, ReactionChannel.ITEMS}) {
            assertEquals(overall, fast.reactionSpeed(channel), channel.name());
        }
        assertEquals(overall, fast.reactionSpeed(ReactionChannel.OVERALL));
    }

    @Test
    void anEmptyLifeIsFiveEverywhere() {
        for (int v : ReflexTally.EMPTY.channelSpeeds().values()) {
            assertEquals(5, v);
        }
        assertEquals(6, ReflexTally.EMPTY.channelSpeeds().size());
    }

    @Test
    void meleeMovesWithMeleeEvidenceOnly() {
        ReflexTally t = repeat(30, x -> x.withStrikeBack(0, true));
        assertTrue(t.reactionSpeed(ReactionChannel.MELEE) > t.reactionSpeed(),
            "melee leans on overall, then its own (faster) evidence pulls it higher");
        ReflexTally ranged = repeat(30, x -> x.withStrikeBack(0, false));
        assertEquals(ranged.reactionSpeed(), ranged.reactionSpeed(ReactionChannel.MELEE),
            "projectile strike backs move the overall score but not melee");
        assertEquals(0, ranged.meleeStrikeSamples());
    }

    @Test
    void fastInMeleeSlowWithABow() {
        ReflexTally t = repeat(30, x -> x.withStrikeBack(0, true));
        for (int i = 0; i < 40; i++) t = t.withArrow(false, false);
        assertTrue(t.reactionSpeed(ReactionChannel.MELEE) >= 8);
        assertTrue(t.reactionSpeed(ReactionChannel.RANGED) <= 3);
    }

    @Test
    void arrowHitRateAnchors() {
        ReflexTally t = ReflexTally.EMPTY;
        for (int i = 0; i < 3; i++) t = t.withArrow(true, null);
        for (int i = 0; i < 7; i++) t = t.withArrow(false, null);
        assertEquals(5.0, t.arrowHitScore(), EPS);
        assertEquals(0, t.bowShots(), "a shot that can't tell crit doesn't count toward crit %");
        assertEquals(10.0, ReflexTally.EMPTY.withArrow(true, null).arrowHitScore(), EPS);
    }

    @Test
    void critRateAnchors() {
        ReflexTally half = ReflexTally.EMPTY.withArrow(false, true).withArrow(false, false);
        assertEquals(5.0, half.critScore(), EPS);
        ReflexTally all = ReflexTally.EMPTY.withArrow(true, true);
        assertEquals(10.0, all.critScore(), EPS);
        assertEquals(1, all.critShots());
        assertEquals(1, all.bowShots());
    }

    @Test
    void bridgeGapAnchors() {
        assertEquals(10.0, ReflexTally.EMPTY.withBridgeBlocks(4, 3, 6).bridgeGapScore(), EPS);  // 2 ticks
        assertEquals(5.0, ReflexTally.EMPTY.withBridgeBlocks(4, 3, 15).bridgeGapScore(), EPS);  // 5 ticks
        assertEquals(0.0, ReflexTally.EMPTY.withBridgeBlocks(4, 3, 24).bridgeGapScore(), EPS);  // 8 ticks
    }

    @Test
    void jumpGapAnchors() {
        assertEquals(10.0, ReflexTally.EMPTY.withJumpBlocks(3, 2, 18).jumpGapScore(), EPS);  // 9
        assertEquals(5.0, ReflexTally.EMPTY.withJumpBlocks(3, 2, 22).jumpGapScore(), EPS);   // 11
    }

    @Test
    void quickBuildersBuildQuickly() {
        // Slow in melee (overall drags down), quick at bridging: building stands out above it.
        ReflexTally t = repeat(30, x -> x.withStrikeBack(40, true));
        for (int i = 0; i < 10; i++) t = t.withBridgeBlocks(4, 3, 6);
        assertTrue(t.reactionSpeed(ReactionChannel.BUILDING) > t.reactionSpeed(ReactionChannel.MOVEMENT));
    }

    @Test
    void dodgesDriveDefence() {
        ReflexTally t = ReflexTally.EMPTY;
        for (int i = 0; i < 20; i++) t = t.withProjectile(false);
        assertTrue(t.reactionSpeed(ReactionChannel.DEFENCE) < t.reactionSpeed(ReactionChannel.ITEMS));
    }

    @Test
    void paceDrivesMovement() {
        ReflexTally t = ReflexTally.EMPTY.withPace(60, 600);  // 6 carriages a minute
        assertTrue(t.reactionSpeed(ReactionChannel.MOVEMENT) > t.reactionSpeed(ReactionChannel.ITEMS));
    }

    @Test
    void channelSpeedsListsEverySpecificChannel() {
        Map<ReactionChannel, Integer> speeds = ReflexTally.EMPTY.withStrikeBack(0).channelSpeeds();
        for (ReactionChannel channel : ReactionChannel.SPECIFIC) {
            assertTrue(speeds.containsKey(channel), channel.name());
        }
    }

    @Test
    void aTallyWrittenByV0124LoadsItsIntCounts() {
        // v0.124.0 wrote the count stats with putInt; they must load unchanged.
        CompoundTag old = new CompoundTag();
        old.putInt("StrikeSamples", 4);
        old.putFloat("StrikeScore", 30F);
        old.putInt("CleanFirstHits", 2);
        old.putInt("Dodges", 3);
        old.putInt("ProjectileHits", 7);
        old.putInt("BridgeBlocks", 9);
        ReflexTally t = ReflexTally.load(old);
        assertEquals(4, t.strikeSamples());
        assertEquals(2, t.cleanFirstHits());
        assertEquals(3, t.dodges());
        assertEquals(9, t.bridgeBlocks());
        assertEquals(0, t.meleeStrikeSamples(), "no melee split in an old tally");
        assertEquals(t.reactionSpeed(), t.reactionSpeed(ReactionChannel.MELEE));
    }

    @Test
    void nbtRoundTripKeepsEveryNewStat() {
        ReflexTally t = ReflexTally.EMPTY.withStrikeBack(4, true).withStrikeBack(9, false)
            .withFirstHit(true, true).withFirstHit(false, false).withArrow(true, true)
            .withArrow(false, null).withBridgeBlocks(4, 3, 9).withJumpBlocks(3, 2, 20);
        ReflexTally back = ReflexTally.load(t.save());
        assertEquals(t.breakdown(), back.breakdown());
        assertEquals(t.channelSpeeds(), back.channelSpeeds());
    }

    @Test
    void arrowsAndBlockGapsDontMoveTheOverallScore() {
        ReflexTally base = repeat(10, x -> x.withStrikeBack(10, true));
        ReflexTally withArrows = base;
        for (int i = 0; i < 20; i++) withArrows = withArrows.withArrow(false, false);
        ReflexTally withGaps = base.withBridgeBlocks(0, 20, 160);   // gaps only, no bonus blocks
        assertEquals(base.rawScore(), withArrows.rawScore(), EPS);
        assertEquals(base.rawScore(), withGaps.rawScore(), EPS);
        assertTrue(withArrows.reactionSpeed(ReactionChannel.RANGED) < base.reactionSpeed());
        assertTrue(withGaps.reactionSpeed(ReactionChannel.BUILDING) < base.reactionSpeed());
    }
}
