package games.brennan.playermob.entity;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link PathFirePolicy} — the notice roll, the delayed reaction, and the
 * "is the mob actually walking" threshold. The live scan (path nodes, block lookups) needs a real
 * level and is covered by the in-game Gate 2 test, the same split {@link DoorObstruction} uses.
 *
 * <p>No {@code Bootstrap} needed: nothing here touches a registry.</p>
 */
class PathFirePolicyTest {

    @Test
    void noticesMostFiresButNotAllOfThem() {
        RandomSource random = RandomSource.create(20260826L);
        int noticed = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            if (PathFirePolicy.notices(random)) {
                noticed++;
            }
        }
        double rate = noticed / (double) trials;
        // Within 2 percentage points of the declared chance — enough to catch an inverted or
        // always-true roll without being flaky.
        assertTrue(Math.abs(rate - PathFirePolicy.NOTICE_CHANCE) < 0.02,
            "notice rate ≈ " + PathFirePolicy.NOTICE_CHANCE + ", got " + rate);
        // And it must genuinely fail sometimes — that fallibility is the point of the behaviour.
        assertTrue(noticed < trials, "never misses a fire");
        assertTrue(noticed > 0, "never spots a fire");
    }

    @Test
    void windUpRangesMatchTheSpecifiedBounds() {
        // The windows themselves are the neutral (reaction 5) baseline; DouseFireInPathGoal rolls them
        // through PlayerMobEntity.reactRoll, whose bounds and skew are ReactionSpeedTest's business.
        assertEquals(5, PathFirePolicy.REACTION_MIN_TICKS);
        assertEquals(40, PathFirePolicy.REACTION_MAX_TICKS);
        assertEquals(4, PathFirePolicy.BUCKET_SWAP_MIN_TICKS);
        assertEquals(20, PathFirePolicy.BUCKET_SWAP_MAX_TICKS);
    }

    @Test
    void aStandingStillMobIsNotHeadingAnywhere() {
        assertEquals(0, PathFirePolicy.stepsAhead(0.0, 0.0), "dead still");
        assertEquals(0, PathFirePolicy.stepsAhead(0.001, -0.001), "idle physics jitter");
        assertEquals(0, PathFirePolicy.stepsAhead(0.02, 0.0), "just under the walking threshold");
    }

    @Test
    void aWalkingMobProbesAheadOnEveryHeading() {
        // A walking mob moves ~0.1-0.2 blocks/tick.
        assertTrue(PathFirePolicy.stepsAhead(0.15, 0.0) > 0, "east");
        assertTrue(PathFirePolicy.stepsAhead(-0.15, 0.0) > 0, "west");
        assertTrue(PathFirePolicy.stepsAhead(0.0, 0.15) > 0, "south");
        assertTrue(PathFirePolicy.stepsAhead(0.0, -0.15) > 0, "north");
        assertTrue(PathFirePolicy.stepsAhead(0.1, 0.1) > 0, "diagonal");
    }

    @Test
    void lookaheadStaysWithinAPlayersReach() {
        // Past ~4.5 blocks the mob could not put the fire out from where it stops.
        assertTrue(PathFirePolicy.LOOKAHEAD_BLOCKS <= 4.5, "lookahead within reach");
        assertEquals(PathFirePolicy.LOOKAHEAD_BLOCKS * PathFirePolicy.LOOKAHEAD_BLOCKS,
            PathFirePolicy.LOOKAHEAD_SQR, 1e-9, "squared form matches");
    }

    @Test
    void reactionRangeMatchesTheSpecifiedBounds() {
        assertEquals(5, PathFirePolicy.REACTION_MIN_TICKS);
        assertEquals(40, PathFirePolicy.REACTION_MAX_TICKS);
    }
}
