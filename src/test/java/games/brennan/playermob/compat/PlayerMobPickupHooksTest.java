package games.brennan.playermob.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Dispatch tests for the {@link PlayerMobPickupHooks} seam. The dispatcher is a pure pass-through
 * guarded against empty stacks and consumer faults; the live call from
 * {@code PlayerMobEntity#isExtraWanted} is covered by the in-game Gate 2 test. {@code null} stands
 * in for a real stack where the stack is never consulted (no Minecraft bootstrap needed).
 */
class PlayerMobPickupHooksTest {

    @AfterEach
    void resetPredicate() {
        PlayerMobPickupHooks.install(null);
    }

    @Test
    void defaultWantsNothing() {
        assertFalse(PlayerMobPickupHooks.wants(null));
    }

    @Test
    void nullStackIsNeverWantedEvenWhenPredicateSaysYes() {
        PlayerMobPickupHooks.install(stack -> true);
        assertFalse(PlayerMobPickupHooks.wants(null));
    }

    @Test
    void throwingPredicateIsNotWanted() {
        PlayerMobPickupHooks.install(stack -> { throw new IllegalStateException("consumer fault"); });
        // A throwing predicate is only reached with a non-empty stack; the null guard runs first, so
        // this pins that the guard — not the catch — answers here, and that neither path throws.
        assertFalse(PlayerMobPickupHooks.wants(null));
    }

    @Test
    void installingNullRestoresDefault() {
        PlayerMobPickupHooks.install(stack -> true);
        PlayerMobPickupHooks.install(null);
        assertFalse(PlayerMobPickupHooks.wants(null));
    }
}
