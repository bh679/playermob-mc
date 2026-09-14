package games.brennan.playermob.entity;

import games.brennan.playermob.entity.StuckDoorPolicy.Choice;
import games.brennan.playermob.entity.StuckDoorPolicy.DoorCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link StuckDoorPolicy} — which door a wedged mob tries next. The
 * preference order (train-axis obstruction first, then nearest), the same-door strike ladder
 * (toggle back, then move on), and the reflex pin are independent of any Minecraft world.
 */
class StuckDoorPolicyTest {

    // Facing X + closed ⇒ obstructs X; facing X + open ⇒ clears X (blocks Z).
    private static final DoorCandidate CLOSED_X_FAR = new DoorCandidate(1, true, false, 4);
    private static final DoorCandidate OPEN_X_NEAR = new DoorCandidate(2, true, true, 1);
    private static final DoorCandidate OPEN_Z_NEAR = new DoorCandidate(3, false, true, 1); // open Z blocks X

    @Test
    void prefersATrainAxisObstructionOverANearerClearDoor() {
        Choice c = StuckDoorPolicy.pick(List.of(OPEN_X_NEAR, CLOSED_X_FAR), StuckDoorPolicy.NO_DOOR, 0);
        assertEquals(CLOSED_X_FAR.key(), c.key(), "the closed X door is in the way; the open one isn't");
        assertTrue(c.desiredOpen(), "…so open it");
    }

    @Test
    void openSideDoorBlockingTheCorridorIsClosed() {
        Choice c = StuckDoorPolicy.pick(List.of(OPEN_Z_NEAR), StuckDoorPolicy.NO_DOOR, 0);
        assertEquals(OPEN_Z_NEAR.key(), c.key());
        assertFalse(c.desiredOpen(), "an open Z-facing door swings across the corridor — close it");
    }

    @Test
    void noObstructionFallsBackToNearestDoorToggled() {
        DoorCandidate farOpenX = new DoorCandidate(9, true, true, 9);
        Choice c = StuckDoorPolicy.pick(List.of(farOpenX, OPEN_X_NEAR), StuckDoorPolicy.NO_DOOR, 0);
        assertEquals(OPEN_X_NEAR.key(), c.key(), "nothing provably blocks X — just try the nearest");
        assertFalse(c.desiredOpen(), "toggle from open to closed");
    }

    @Test
    void secondStrikeTogglesTheSameDoorBack() {
        // The probe opened door 1 (now open); the mob is still stuck: try it closed.
        DoorCandidate nowOpen = new DoorCandidate(1, true, true, 4);
        Choice c = StuckDoorPolicy.pick(List.of(nowOpen, OPEN_X_NEAR), 1, 1);
        assertEquals(1, c.key(), "second strike stays on the same door");
        assertFalse(c.desiredOpen(), "…and tries its other state");
    }

    @Test
    void thirdStrikeMovesToAnotherDoorIfThereIsOne() {
        Choice c = StuckDoorPolicy.pick(List.of(CLOSED_X_FAR, OPEN_X_NEAR), 1, 2);
        assertEquals(OPEN_X_NEAR.key(), c.key(), "both states of door 1 failed — try the other door");
    }

    @Test
    void thirdStrikeRetriesTheOnlyDoor() {
        Choice c = StuckDoorPolicy.pick(List.of(CLOSED_X_FAR), 1, 2);
        assertEquals(1, c.key(), "nothing else to try — keep working the only door");
        assertTrue(c.desiredOpen());
    }

    @Test
    void aVanishedProbeDoorStartsAFreshPick() {
        Choice c = StuckDoorPolicy.pick(List.of(OPEN_Z_NEAR), 77, 1);
        assertEquals(OPEN_Z_NEAR.key(), c.key(), "last probe door is out of reach — pick afresh");
    }

    @Test
    void noDoorsMeansNoChoice() {
        assertNull(StuckDoorPolicy.pick(List.of(), StuckDoorPolicy.NO_DOOR, 0));
    }

    @Test
    void reflexMayNotTouchThePinnedDoorUntilThePinExpires() {
        assertFalse(StuckDoorPolicy.reflexMayTouch(5, 5, 10), "pinned door is off limits");
        assertTrue(StuckDoorPolicy.reflexMayTouch(6, 5, 10), "other doors are fine");
        assertTrue(StuckDoorPolicy.reflexMayTouch(5, 5, 0), "pin expired");
    }
}
