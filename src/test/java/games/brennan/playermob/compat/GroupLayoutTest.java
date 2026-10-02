package games.brennan.playermob.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link GroupLayout} — where rooms and end pads sit in a carriage group.
 * The numbers are Dungeon Train's real ones: carriages 9 long, groups of three, a 5-block
 * half-flatbed pad at each end, so a group's box is 37 long: {@code [5][9][9][9][5]}.
 */
class GroupLayoutTest {

    private static final double EPS = 1.0e-9;

    /** A three-room group whose box starts at world X 100; rooms are carriages 3, 4, 5. */
    private static final GroupLayout GROUP = GroupLayout.of(100.0, 137.0, 3, 5, 9.0);

    @Test
    void padIsWhatTheBoxHasLeftOverAfterTheRooms() {
        assertEquals(5.0, GROUP.pad(), EPS);
        assertEquals(3, GROUP.rooms());
        assertEquals(9.0, GROUP.roomLength(), EPS);
        assertEquals(105.0, GROUP.roomsMinX(), EPS);
        assertEquals(132.0, GROUP.roomsMaxX(), EPS);
    }

    @Test
    void roomsSitBetweenThePadsNotAcrossTheWholeBox() {
        assertEquals(3, GROUP.roomPidx(105.1));
        assertEquals(3, GROUP.roomPidx(113.9));   // an even three-way split of the box would call this room 4
        assertEquals(4, GROUP.roomPidx(114.1));
        assertEquals(4, GROUP.roomPidx(122.9));
        assertEquals(5, GROUP.roomPidx(123.1));   // ...and would still call this room 4
        assertEquals(5, GROUP.roomPidx(131.9));
    }

    @Test
    void roomCentresAreTheRealOnes() {
        assertEquals(109.5, GROUP.roomCentreX(3), EPS);
        assertEquals(118.5, GROUP.roomCentreX(4), EPS);
        assertEquals(127.5, GROUP.roomCentreX(5), EPS);
    }

    @Test
    void padsAreDetectedAtEachEnd() {
        assertEquals(-1, GROUP.padSide(100.5));
        assertEquals(-1, GROUP.padSide(104.9));
        assertEquals(0, GROUP.padSide(105.1));
        assertEquals(0, GROUP.padSide(131.9));
        assertEquals(1, GROUP.padSide(132.1));
        assertEquals(1, GROUP.padSide(136.5));
        // Just off the box, on the seam: still that end's pad side.
        assertEquals(-1, GROUP.padSide(99.8));
        assertEquals(1, GROUP.padSide(137.2));
    }

    @Test
    void aPadCountsAsTheRoomBesideIt() {
        assertEquals(3, GROUP.roomPidx(101.0));
        assertEquals(5, GROUP.roomPidx(136.0));
    }

    @Test
    void marchingStepsRoomToRoomThenRunsOut() {
        assertEquals(118.5, GROUP.nextRoomCentreX(109.5, +1), EPS);
        assertEquals(127.5, GROUP.nextRoomCentreX(118.5, +1), EPS);
        assertTrue(Double.isNaN(GROUP.nextRoomCentreX(127.5, +1)), "last room: the next step leaves the group");
        assertEquals(109.5, GROUP.nextRoomCentreX(118.5, -1), EPS);
        assertTrue(Double.isNaN(GROUP.nextRoomCentreX(109.5, -1)));
    }

    @Test
    void fromAPadTheNextRoomIsTheOneBesideIt() {
        // Just walked in over the low seam, marching up: first stop is room 3, not room 4.
        assertEquals(109.5, GROUP.nextRoomCentreX(102.0, +1), EPS);
        // Came in over the high seam, marching down: first stop is room 5.
        assertEquals(127.5, GROUP.nextRoomCentreX(135.0, -1), EPS);
    }

    @Test
    void onTheEndPadFacingOutThereIsNoNextRoom() {
        assertTrue(Double.isNaN(GROUP.nextRoomCentreX(135.0, +1)));
        assertTrue(Double.isNaN(GROUP.nextRoomCentreX(102.0, -1)));
    }

    @Test
    void endPadCentresAreMidPad() {
        assertEquals(102.5, GROUP.endPadCentreX(-1), EPS);
        assertEquals(134.5, GROUP.endPadCentreX(+1), EPS);
    }

    @Test
    void aLoneCarriageHasNoPads() {
        GroupLayout lone = GroupLayout.of(200.0, 209.0, 7, 7, 9.0);
        assertEquals(0.0, lone.pad(), EPS);
        assertEquals(0, lone.padSide(200.1));
        assertEquals(0, lone.padSide(208.9));
        assertEquals(7, lone.roomPidx(204.0));
        assertEquals(204.5, lone.roomCentreX(7), EPS);
        assertTrue(Double.isNaN(lone.nextRoomCentreX(204.0, +1)));
        assertTrue(Double.isNaN(lone.endPadCentreX(+1)));
    }

    @Test
    void unknownCarriageLengthFallsBackToRoomsFillingTheBox() {
        GroupLayout legacy = GroupLayout.of(0.0, 27.0, 0, 2, 0.0);
        assertEquals(0.0, legacy.pad(), EPS);
        assertEquals(9.0, legacy.roomLength(), EPS);
        assertEquals(1, legacy.roomPidx(13.0));
    }

    @Test
    void boxJitterIsNotAPad() {
        // A box a hair longer than its rooms (physics jitter) must not sprout a sliver of pad.
        GroupLayout jitter = GroupLayout.of(0.0, 27.2, 0, 2, 9.0);
        assertEquals(0.0, jitter.pad(), EPS);
    }

    @Test
    void zeroDirectionHasNoNextRoom() {
        assertTrue(Double.isNaN(GROUP.nextRoomCentreX(118.5, 0)));
    }
}
