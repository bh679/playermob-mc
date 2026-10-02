package games.brennan.playermob.entity;

import games.brennan.playermob.entity.SprintJumpPolicy.Mode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link SprintJumpPolicy} — what counts as a straight run, and when a run
 * is an open jump, a head-bump, or neither (who may do either is {@link PlayerSpeedsTest}'s table). Primitives
 * only, so no Minecraft bootstrap is needed (mirrors {@link StayNearPolicyTest}).
 */
class SprintJumpPolicyTest {

    private static final int Y = 64;

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
    void openJumpNeedsAStraightLongEnoughToLandOn() {
        assertEquals(Mode.OPEN, SprintJumpPolicy.classify(5, flags(5, true), flags(5, false), false));
        assertEquals(Mode.OPEN, SprintJumpPolicy.classify(5, flags(5, true), flags(5, false), true));
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(4, flags(4, true), flags(4, false), true));
    }

    @Test
    void anUnsafeCellEndsTheUsableRun() {
        // A drop, a wall or water at the fourth cell: only three usable cells — too short to leap.
        boolean[] safe = flags(5, true);
        safe[3] = false;
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(5, safe, flags(5, false), true));
        // At the fifth cell: four usable, still one short of the landing stretch.
        boolean[] later = flags(5, true);
        later[4] = false;
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(5, later, flags(5, false), true));
        assertEquals(Mode.OPEN, SprintJumpPolicy.classify(5, flags(5, true), flags(5, false), true));
    }

    // ---- Head-bump: only in a 2-block-tall gap ----

    @Test
    void boostersHeadBumpThroughATwoTallGap() {
        assertEquals(Mode.HEAD_BUMP, SprintJumpPolicy.classify(3, flags(3, true), flags(3, true), true));
        assertEquals(Mode.HEAD_BUMP, SprintJumpPolicy.classify(5, flags(5, true), flags(5, true), true));
    }

    @Test
    void othersJustSprintThroughATwoTallGap() {
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(5, flags(5, true), flags(5, true), false));
    }

    @Test
    void aTallerCeilingIsNeverAHeadBump() {
        // lowCeiling is false for a ceiling three or more blocks up — that's an ordinary open jump.
        assertEquals(Mode.OPEN, SprintJumpPolicy.classify(5, flags(5, true), flags(5, false), true));
    }

    @Test
    void headBumpNeedsAShortRunOfItsOwn() {
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(2, flags(2, true), flags(2, true), true));
    }

    @Test
    void mixedCeilingIsNeitherStyle() {
        // Open for two cells, then the ceiling drops: can't leap (would hit it), can't head-bump yet.
        boolean[] dropping = {false, false, true, true, true};
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(5, flags(5, true), dropping, true));
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(5, flags(5, true), dropping, false));
        // In a 2-tall gap that opens out after the hop distance, a booster still head-bumps...
        boolean[] opening = {true, true, true, false, false};
        assertEquals(Mode.HEAD_BUMP, SprintJumpPolicy.classify(5, flags(5, true), opening, true));
        // ...but one that opens out sooner is neither.
        boolean[] openingSoon = {true, true, false, false, false};
        assertEquals(Mode.NONE, SprintJumpPolicy.classify(5, flags(5, true), openingSoon, true));
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
