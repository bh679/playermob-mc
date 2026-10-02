package games.brennan.playermob.entity;

/**
 * Pure decision core for sprint-jumping: whether a sprinting {@link PlayerMobEntity} should jump
 * along the stretch of path ahead of it, and in which style. Primitives only (no Minecraft types),
 * so it unit-tests without a game bootstrap; {@link SprintJumpDriver} gathers the block facts and
 * acts on the verdict.
 *
 * <p>Who sprint-jumps at all, and who may head-bump, is decided by the mob's
 * {@link PlayerSpeeds.Style}; this class only answers whether the ground ahead allows it.</p>
 *
 * <p><b>Head-bump only in a 2-block-tall gap.</b> Jumping under a ceiling that sits directly on the
 * mob's head cuts each hop short, so the 0.2 sprint-jump boost lands several times a second. It is
 * the fastest way a player moves on foot and it only exists where there are exactly two blocks of
 * air; a ceiling three or more blocks up is an ordinary {@link Mode#OPEN} jump. A stretch that
 * mixes the two is neither — the mob just sprints until the ceiling is uniform.</p>
 */
public final class SprintJumpPolicy {

    private SprintJumpPolicy() {}

    /** How a run should be jumped. */
    public enum Mode {
        /** Don't jump — plain sprint. */
        NONE,
        /** Ordinary sprint-jump: hold jump, one leap every time the mob lands. */
        OPEN,
        /** 2-block-tall gap: tap jump on each landing so the ceiling cuts every hop short. */
        HEAD_BUMP
    }

    /**
     * Shortest straight an open sprint-jump may start on: one leap carries about 4.3 blocks, so the
     * landing has to be inside the stretch that was checked.
     */
    static final int MIN_OPEN_RUN = 5;
    /** Straight needed to head-bump: each clipped hop only carries about a block and a half. */
    static final int HEAD_BUMP_RUN = 3;
    /** How far ahead the driver needs to look to answer every case above. */
    public static final int LOOKAHEAD = MIN_OPEN_RUN;

    /** Yaw error (degrees) from the run direction within which a takeoff's boost is still on line. */
    static final float YAW_TOLERANCE = 15.0F;

    /**
     * Number of leading path cells that continue in one straight, level line: all at {@code feetY},
     * each one step on from the last by the same {@code (dx, dz)}, with each component in
     * {@code {-1, 0, 1}}. The step from the mob's own cell {@code (fromX, fromZ)} to the first cell
     * sets the direction, so a path that starts by doubling back counts as zero.
     */
    public static int straightRunLength(int fromX, int fromZ, int feetY, int[] xs, int[] ys, int[] zs) {
        int count = Math.min(xs.length, Math.min(ys.length, zs.length));
        if (count == 0) {
            return 0;
        }
        int dx = xs[0] - fromX;
        int dz = zs[0] - fromZ;
        if (!isUnitStep(dx, dz)) {
            return 0;
        }
        int length = 0;
        int prevX = fromX;
        int prevZ = fromZ;
        for (int i = 0; i < count; i++) {
            if (ys[i] != feetY || xs[i] - prevX != dx || zs[i] - prevZ != dz) {
                break;
            }
            prevX = xs[i];
            prevZ = zs[i];
            length++;
        }
        return length;
    }

    private static boolean isUnitStep(int dx, int dz) {
        return Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && (dx != 0 || dz != 0);
    }

    /**
     * How to jump the run ahead, or {@link Mode#NONE}.
     *
     * @param runLength  cells of straight, level path ahead (see {@link #straightRunLength})
     * @param safe       per cell: clear to stand in, ground underfoot, and a wall or ground on both
     *                   sides (nothing to fall off)
     * @param lowCeiling per cell: a solid block directly above the mob's head, i.e. the cell is
     *                   exactly two blocks tall
     * @param mayBoost   whether this mob head-bumps ({@link PlayerSpeeds.Style#boosts})
     */
    public static Mode classify(int runLength, boolean[] safe, boolean[] lowCeiling, boolean mayBoost) {
        int usable = Math.min(runLength, Math.min(safe.length, lowCeiling.length));
        int safeCells = 0;
        while (safeCells < usable && safe[safeCells]) {
            safeCells++;
        }
        if (safeCells >= HEAD_BUMP_RUN && allEqual(lowCeiling, HEAD_BUMP_RUN, true)) {
            return mayBoost ? Mode.HEAD_BUMP : Mode.NONE;
        }
        if (safeCells >= MIN_OPEN_RUN && allEqual(lowCeiling, MIN_OPEN_RUN, false)) {
            return Mode.OPEN;
        }
        return Mode.NONE;
    }

    private static boolean allEqual(boolean[] flags, int count, boolean value) {
        for (int i = 0; i < count; i++) {
            if (flags[i] != value) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Minecraft yaw (degrees) that faces along a run step: 0 is +Z (south), 90 is -X (west) —
     * the same convention as {@code Entity.getYRot}.
     */
    public static float yawOf(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** True when {@code yaw} is within {@link #YAW_TOLERANCE} of the run direction. */
    public static boolean yawAligned(float yaw, int dx, int dz) {
        float error = (yaw - yawOf(dx, dz)) % 360.0F;
        if (error > 180.0F) {
            error -= 360.0F;
        } else if (error < -180.0F) {
            error += 360.0F;
        }
        return Math.abs(error) <= YAW_TOLERANCE;
    }
}
