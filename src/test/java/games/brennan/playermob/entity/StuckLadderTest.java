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
        // A 3-carriage group from world X 100 to 127: carriages 100..109, 109..118, 118..127.
        double min = 100.0, max = 127.0;
        assertTrue(StuckLadder.isCarriageEndWall(100.5, min, max, 3), "first carriage, west wall");
        assertTrue(StuckLadder.isCarriageEndWall(108.5, min, max, 3), "first carriage, east wall");
        assertTrue(StuckLadder.isCarriageEndWall(109.5, min, max, 3), "second carriage, west wall");
        assertTrue(StuckLadder.isCarriageEndWall(126.5, min, max, 3), "last carriage, east wall");
        assertFalse(StuckLadder.isCarriageEndWall(101.5, min, max, 3), "first interior cell");
        assertFalse(StuckLadder.isCarriageEndWall(107.5, min, max, 3), "last interior cell");
        assertFalse(StuckLadder.isCarriageEndWall(113.5, min, max, 3), "mid carriage");
    }

    @Test
    void wallColumnsInASingleCarriageGroup() {
        assertTrue(StuckLadder.isCarriageEndWall(0.5, 0.0, 9.0, 1));
        assertTrue(StuckLadder.isCarriageEndWall(8.5, 0.0, 9.0, 1));
        assertFalse(StuckLadder.isCarriageEndWall(4.5, 0.0, 9.0, 1));
        assertFalse(StuckLadder.isCarriageEndWall(4.5, 0.0, 0.0, 1), "no bounds: never a wall");
    }
}
