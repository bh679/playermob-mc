package games.brennan.playermob.entity;

import games.brennan.playermob.entity.SprintJumpPolicy.Mode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link SprintJumpPolicy} — who sprint-jumps (reaction-speed tiers), what
 * counts as a straight run, and when a run is an open jump, a head-bump, or neither. Primitives
 * only, so no Minecraft bootstrap is needed (mirrors {@link StayNearPolicyTest}).
 */
class SprintJumpPolicyTest {

    private static final int Y = 64;

    // ---- Who does it ----

    @Test
    void slowReactorsNeverSprintJump() {
        for (int reaction = 0; reaction <= 4; reaction++) {
            assertFalse(SprintJumpPolicy.rollsRun(reaction, 0.0), "reaction " + reaction);
            assertEquals(Mode.NONE, SprintJumpPolicy.classify(reaction, 7, flags(7, true), flags(7, false)));
        }
    }

    @Test
    void neutralReactorSprintJumpsSometimes() {
        assertTrue(SprintJumpPolicy.rollsRun(5, 0.0));
        assertTrue(SprintJumpPolicy.rollsRun(5, 0.49));
        assertFalse(SprintJumpPolicy.rollsRun(5, 0.5));
        assertFalse(SprintJumpPolicy.rollsRun(5, 0.99));
    }

    @Test
    void quickReactorsAlwaysRollIn() {
        for (int reaction = 6; reaction <= 10; reaction++) {
            assertTrue(SprintJumpPolicy.rollsRun(reaction, 0.99), "reaction " + reaction);
        }
    }

    // ---- Straight runs ----

    @Test
    void countsAStraightAxisRun() {
        // Mob at (0, 0) heading +X.
        assertEquals(5, run(0, 0, new int[] {1, 2, 3, 4, 5}, level(5), new int[] {0, 0, 0, 0, 0}));
    }

    @Test
    void countsAStraightDiagonalRun() {
        assertEquals(4, run(0, 0, new int[] {1, 2, 3, 4}, level(4), new int[] {-1, -2, -3, -4}));
    }

    @Test
    void runEndsAtATurn() {
        // Three cells along +X, then the path turns to +Z.
        assertEquals(3, run(0, 0, new int[] {1, 2, 3, 3, 3}, level(5), new int[] {0, 0, 0, 1, 2}));
    }

    @Test
    void runEndsAtAStepUpOrDown() {
        assertEquals(2, run(0, 0, new int[] {1, 2, 3, 4}, new int[] {Y, Y, Y + 1, Y + 1}, new int[] {0, 0, 0, 0}));
        assertEquals(2, run(0, 0, new int[] {1, 2, 3, 4}, new int[] {Y, Y, Y - 1, Y - 1}, new int[] {0, 0, 0, 0}));
    }

    @Test
    void pathNotStartingNextToTheMobIsNoRun() {
        // First node two cells away — the mob isn't on this line yet.
        assertEquals(0, run(0, 0, new int[] {2, 3, 4}, level(3), new int[] {0, 0, 0}));
        // First node at a different height.
        assertEquals(0, run(0, 0, new int[] {1, 2, 3}, new int[] {Y + 1, Y + 1, Y + 1}, new int[] {0, 0, 0}));
        assertEquals(0, run(0, 0, new int[0], new int[0], new int[0]));
    }

    // ---- Open jumps ----

    @Test
    void cautiousReactorsNeedALongClearStraight() {
        for (int reaction = 5; reaction <= 8; reaction++) {
            assertEquals(Mode.OPEN, SprintJumpPolicy.classify(reaction, 7, flags(7, true), flags(7, false)));
            assertEquals(Mode.NONE, SprintJumpPolicy.classify(reaction, 6, flags(6, true), flags(6, false)));
        }
    }

    @Test
    void expertReactorsJumpAnyStraightLongEnoughToLandOn() {
        for (int reaction = 9; reaction <= 10; reaction++) {
            assertEquals(Mode.OPEN, SprintJumpPolicy.classify(reaction, 5, flags(5, true), flags(5, false)));
            assertEquals(Mode.NONE, SprintJumpPolicy.classify(reaction, 4, flags(4, true), flags(4, false)));
        }
    }

    @Test
    void anUnsafeCellEndsTheUsableRun() {
        // A drop, a wall or water at the fourth cell: only three usable cells — too short for anyone.
        boolean[] safe = flags(7, true);
        safe[3] = false;
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(7, 7, safe, flags(7, false)));
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(10, 7, safe, flags(7, false)));
        // At the sixth cell an expert still has its five; a cautious mob does not have its seven.
        boolean[] later = flags(7, true);
        later[5] = false;
        assertEquals(Mode.OPEN, SprintJumpPolicy.classify(10, 7, later, flags(7, false)));
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(7, 7, later, flags(7, false)));
    }

    // ---- Head-bump: only in a 2-block-tall gap ----

    @Test
    void expertsHeadBumpThroughATwoTallGap() {
        assertEquals(Mode.HEAD_BUMP, SprintJumpPolicy.classify(9, 3, flags(3, true), flags(3, true)));
        assertEquals(Mode.HEAD_BUMP, SprintJumpPolicy.classify(10, 7, flags(7, true), flags(7, true)));
    }

    @Test
    void nonExpertsJustSprintThroughATwoTallGap() {
        for (int reaction = 5; reaction <= 8; reaction++) {
            assertEquals(Mode.NONE, SprintJumpPolicy.classify(reaction, 7, flags(7, true), flags(7, true)));
        }
    }

    @Test
    void aTallerCeilingIsNeverAHeadBump() {
        // lowCeiling is false for a ceiling three or more blocks up — that's an ordinary open jump.
        assertEquals(Mode.OPEN, SprintJumpPolicy.classify(10, 7, flags(7, true), flags(7, false)));
    }

    @Test
    void headBumpNeedsAShortRunOfItsOwn() {
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(10, 2, flags(2, true), flags(2, true)));
    }

    @Test
    void mixedCeilingIsNeitherStyle() {
        // Open for two cells, then the ceiling drops: can't leap (would hit it), can't head-bump yet.
        boolean[] dropping = {false, false, true, true, true, true, true};
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(10, 7, flags(7, true), dropping));
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(7, 7, flags(7, true), dropping));
        // In a 2-tall gap that opens out after the hop distance, an expert still head-bumps...
        boolean[] opening = {true, true, true, false, false, false, false};
        assertEquals(Mode.HEAD_BUMP, SprintJumpPolicy.classify(10, 7, flags(7, true), opening));
        // ...but one that opens out sooner is neither.
        boolean[] openingSoon = {true, true, false, false, false, false, false};
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(10, 7, flags(7, true), openingSoon));
    }

    // ---- Heading ----

    @Test
    void yawFollowsMinecraftConvention() {
        assertEquals(0.0F, SprintJumpPolicy.yawOf(0, 1), 1.0e-4F);      // south, +Z
        assertEquals(-90.0F, SprintJumpPolicy.yawOf(1, 0), 1.0e-4F);    // east, +X
        assertEquals(90.0F, SprintJumpPolicy.yawOf(-1, 0), 1.0e-4F);    // west, -X
        assertEquals(180.0F, Math.abs(SprintJumpPolicy.yawOf(0, -1)), 1.0e-4F);  // north, -Z
        assertEquals(-45.0F, SprintJumpPolicy.yawOf(1, 1), 1.0e-4F);    // south-east
    }

    @Test
    void takeoffNeedsTheMobFacingAlongTheRun() {
        assertTrue(SprintJumpPolicy.yawAligned(-90.0F, 1, 0));
        assertTrue(SprintJumpPolicy.yawAligned(-80.0F, 1, 0));
        assertFalse(SprintJumpPolicy.yawAligned(-60.0F, 1, 0));
        assertFalse(SprintJumpPolicy.yawAligned(90.0F, 1, 0));
    }

    @Test
    void yawComparisonWrapsAround() {
        // Vanilla keeps a mob's yaw in [0, 360): 270 is the same heading as -90.
        assertTrue(SprintJumpPolicy.yawAligned(270.0F, 1, 0));
        assertTrue(SprintJumpPolicy.yawAligned(630.0F, 1, 0));
        // North is ±180 — either side of the wrap is aligned.
        assertTrue(SprintJumpPolicy.yawAligned(175.0F, 0, -1));
        assertTrue(SprintJumpPolicy.yawAligned(-175.0F, 0, -1));
        assertTrue(SprintJumpPolicy.yawAligned(185.0F, 0, -1));
    }

    private static int run(int fromX, int fromZ, int[] xs, int[] ys, int[] zs) {
        return SprintJumpPolicy.straightRunLength(fromX, fromZ, Y, xs, ys, zs);
    }

    private static int[] level(int count) {
        int[] ys = new int[count];
        Arrays.fill(ys, Y);
        return ys;
    }

    private static boolean[] flags(int count, boolean value) {
        boolean[] flags = new boolean[count];
        Arrays.fill(flags, value);
        return flags;
    }
}
