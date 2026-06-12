package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static games.brennan.playermob.entity.FollowLovedOnePolicy.SPRINT_SPEED;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.WALK_SPEED;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.keepFollowing;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.leads;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.speedFor;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.wantsToFollow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link FollowLovedOnePolicy} — the follow / park / sprint distance
 * bands and the mutual-love leadership tiebreak. Primitives + {@link UUID} only, so no
 * Minecraft bootstrap is needed.
 */
class FollowLovedOnePolicyTest {

    @Test
    void parksWhenClose_followsWhenFar() {
        assertFalse(wantsToFollow(2.0));
        assertFalse(wantsToFollow(6.0)); // exactly START — not yet past it
        assertTrue(wantsToFollow(6.1));  // drifted past the start band
        assertTrue(wantsToFollow(12.0));
    }

    @Test
    void hysteresisKeepsFollowingUntilParked() {
        assertTrue(keepFollowing(5.0));  // in the dead-band, already following → keep going
        assertTrue(keepFollowing(3.1));
        assertFalse(keepFollowing(3.0)); // parked (<= STOP) → stop so own goals run
        assertFalse(keepFollowing(1.0));
    }

    @Test
    void dropsLovedOneBeyondScanRange() {
        assertTrue(keepFollowing(24.0));  // at the edge — still in range
        assertFalse(keepFollowing(24.1)); // beyond scan → lost
    }

    @Test
    void sprintsOnlyWhenFar() {
        assertEquals(WALK_SPEED, speedFor(4.0));
        assertEquals(WALK_SPEED, speedFor(10.0));   // exactly SPRINT — still a walk
        assertEquals(SPRINT_SPEED, speedFor(10.1)); // past it → sprint to catch up
        assertEquals(SPRINT_SPEED, speedFor(20.0));
    }

    @Test
    void leadershipIsTotalAndStable() {
        UUID a = new UUID(0L, 1L);
        UUID b = new UUID(0L, 2L);
        // Exactly one of a mutual pair leads — the other follows.
        assertTrue(leads(a, b));
        assertFalse(leads(b, a));
        // Deterministic / idempotent, and a mob never leads over itself.
        assertTrue(leads(a, b));
        assertFalse(leads(a, a));
    }
}
