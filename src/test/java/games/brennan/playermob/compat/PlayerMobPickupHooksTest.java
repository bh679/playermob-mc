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
        PlayerMobPickupHooks.installFloorGift(null);
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

    @Test
    void floorGiftDefaultWantsNothing() {
        assertFalse(PlayerMobPickupHooks.wantsFloorGift(null, null, null));
    }

    @Test
    void floorGiftWithoutThrowerIsNeverWantedEvenWhenConsumerSaysYes() {
        // No thrower (ownerless drop, a mob's own drop) and no mob are refused before the consumer runs.
        PlayerMobPickupHooks.installFloorGift((mob, thrower, stack) -> true);
        assertFalse(PlayerMobPickupHooks.wantsFloorGift(null, null, null));
    }

    @Test
    void floorGiftInstallingNullRestoresDefault() {
        PlayerMobPickupHooks.installFloorGift((mob, thrower, stack) -> { throw new IllegalStateException("consumer fault"); });
        PlayerMobPickupHooks.installFloorGift(null);
        assertFalse(PlayerMobPickupHooks.wantsFloorGift(null, null, null));
    }
}
