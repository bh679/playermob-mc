package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DoorObstruction#obstructs(boolean, boolean, boolean)} — the
 * load-bearing geometry of path-aware door handling. A closed door blocks travel along its
 * facing axis; an open door blocks the perpendicular axis; toggling swaps which axis is
 * blocked (so toggling an obstructing door always clears it). Independent of any Minecraft
 * world, so it's driven directly with the facing/open/travel booleans; the live scan and
 * toggle are covered by the in-game Gate 2 test instead. Axes are encoded as "is it X?":
 * {@code true} = X axis, {@code false} = Z axis.
 */
class DoorObstructionTest {

    @Test
    void closedDoorBlocksItsFacingAxisOnly() {
        // Facing X, closed: blocks X travel (walk into the panel), clears Z.
        assertTrue(DoorObstruction.obstructs(true, false, true), "closed door blocks its facing axis");
        assertFalse(DoorObstruction.obstructs(true, false, false), "closed door clears the perpendicular axis");
    }

    @Test
    void openDoorBlocksThePerpendicularAxisOnly() {
        // Facing X, open: panel swung 90° onto the Z edge — blocks Z travel, clears X.
        assertFalse(DoorObstruction.obstructs(true, true, true), "open door clears its facing axis");
        assertTrue(DoorObstruction.obstructs(true, true, false), "open door blocks the perpendicular axis");
    }

    @Test
    void facingZIsTheMirrorImage() {
        // Closed, facing Z: blocks Z, clears X.
        assertTrue(DoorObstruction.obstructs(false, false, false), "closed facing-Z blocks Z");
        assertFalse(DoorObstruction.obstructs(false, false, true), "closed facing-Z clears X");
        // Open, facing Z: blocks X, clears Z.
        assertTrue(DoorObstruction.obstructs(false, true, true), "open facing-Z blocks X");
        assertFalse(DoorObstruction.obstructs(false, true, false), "open facing-Z clears Z");
    }

    @Test
    void togglingAlwaysFlipsWhichAxisIsBlocked() {
        for (boolean facingIsX : new boolean[]{true, false}) {
            for (boolean travelIsX : new boolean[]{true, false}) {
                assertNotEquals(
                    DoorObstruction.obstructs(facingIsX, false, travelIsX),
                    DoorObstruction.obstructs(facingIsX, true, travelIsX),
                    "toggling open↔closed must swap the blocked axis, so an obstructing door clears");
            }
        }
    }
}
