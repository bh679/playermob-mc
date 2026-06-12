package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static games.brennan.playermob.entity.FollowLovedOnePolicy.followByBlocks;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.followByCarriages;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.keepByBlocks;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.keepByCarriages;
import static games.brennan.playermob.entity.FollowLovedOnePolicy.leads;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link FollowLovedOnePolicy} — the "catch up only when far" carriage
 * and block thresholds, plus the mutual-love leadership tiebreak. Primitives + {@link UUID}
 * only, so no Minecraft bootstrap is needed.
 */
class FollowLovedOnePolicyTest {

    @Test
    void chasesOnlyBeyondTwoCarriages() {
        assertFalse(followByCarriages(0));
        assertFalse(followByCarriages(1));
        assertFalse(followByCarriages(2)); // exactly 2 — "within", stay normal
        assertTrue(followByCarriages(3));  // more than 2 → catch up
        assertTrue(followByCarriages(6));
    }

    @Test
    void keepsCatchingUpUntilWithinOneCarriage() {
        assertTrue(keepByCarriages(3));
        assertTrue(keepByCarriages(2));  // still closing the gap
        assertFalse(keepByCarriages(1)); // caught up → release to normal
        assertFalse(keepByCarriages(0));
    }

    @Test
    void blockFallbackOffTrain() {
        assertFalse(followByBlocks(24.0)); // exactly the gate — not yet
        assertTrue(followByBlocks(24.1));  // past it → chase
        assertTrue(keepByBlocks(11.0));    // still closing
        assertFalse(keepByBlocks(10.0));   // caught up → normal
        assertFalse(keepByBlocks(64.1));   // beyond scan → lost
    }

    @Test
    void leadershipIsTotalAndStable() {
        UUID a = new UUID(0L, 1L);
        UUID b = new UUID(0L, 2L);
        // Exactly one of a mutual pair leads — the other chases.
        assertTrue(leads(a, b));
        assertFalse(leads(b, a));
        // Deterministic, and a mob never leads over itself.
        assertTrue(leads(a, b));
        assertFalse(leads(a, a));
    }
}
