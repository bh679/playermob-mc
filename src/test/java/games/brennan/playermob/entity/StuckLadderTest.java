package games.brennan.playermob.entity;

import games.brennan.playermob.entity.StuckLadder.Rung;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The door-first, wall-last ladder and the carriage wall-column maths. */
class StuckLadderTest {

    @Test
    void atAnUntriedDoorTheMobUsesIt() {
        assertEquals(Rung.USE_DOOR, StuckLadder.rung(true, true, 0, false, false, 1));
        assertEquals(Rung.USE_DOOR, StuckLadder.rung(true, true, 2, false, false, 5),
            "both states not yet tried");
    }

    @Test
    void aDoorTriedBothWaysIsBrokenOnlyAfterRepeatedStucks() {
        assertEquals(Rung.USE_DOOR, StuckLadder.rung(true, true, 3, false, false, 1), "never on the first pause");
        assertEquals(Rung.BREAK_DOOR, StuckLadder.rung(true, true, 3, false, false, 2));
    }

    @Test
    void fillOnTheWayToTheDoorIsDugBeforeAnythingIsBroken() {
        assertEquals(Rung.UNBLOCK_DOOR, StuckLadder.rung(true, false, 0, true, false, 1));
        assertEquals(Rung.UNBLOCK_DOOR, StuckLadder.rung(true, false, 5, true, true, 9),
            "diggable fill beats breaking the wall");
    }

    @Test
    void aWallIsBrokenOnlyWhenTheDoorCannotBeReached() {
        assertEquals(Rung.USE_DOOR, StuckLadder.rung(true, false, 0, false, false, 9),
            "open way to the door: keep using it");
        assertEquals(Rung.USE_DOOR, StuckLadder.rung(true, false, 0, false, true, 2));
        assertEquals(Rung.BREAK_WALL, StuckLadder.rung(true, false, 0, false, true, 3));
    }

    @Test
    void aWallWithNoDoorIsBrokenAfterASecondStuck() {
        assertEquals(Rung.USE_DOOR, StuckLadder.rung(false, false, 0, false, false, 1));
        assertEquals(Rung.BREAK_WALL, StuckLadder.rung(false, false, 0, false, false, 2));
    }

    @Test
    void wallColumnsAreTheTwoEndsOfEveryCarriage() {
        // Seen on the train: a group from sub x 20481019, carriages 9 long. Walls at 019 and 027
        // (first carriage), 028 and 036, 037 and 045; the doors sit in those columns.
        int origin = 20481019;
        for (int x : new int[] {20481019, 20481027, 20481028, 20481036, 20481037, 20481045}) {
            assertTrue(StuckLadder.isCarriageEndWall(x, origin, 9), "wall column " + x);
        }
        for (int x : new int[] {20481020, 20481026, 20481029, 20481032, 20481044}) {
            assertFalse(StuckLadder.isCarriageEndWall(x, origin, 9), "interior column " + x);
        }
    }

    @Test
    void anUnknownLengthIsNeverAWall() {
        assertFalse(StuckLadder.isCarriageEndWall(0, 0, 0));
        assertFalse(StuckLadder.isCarriageEndWall(5, 0, 1));
    }
}
