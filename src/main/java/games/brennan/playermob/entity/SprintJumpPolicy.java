package games.brennan.playermob.entity;

/**
 * Pure decision core for sprint-jumping: whether a sprinting {@link PlayerMobEntity} should jump
 * along the stretch of path ahead of it, and in which style. Primitives only (no Minecraft types),
 * so it unit-tests without a game bootstrap; {@link SprintJumpDriver} gathers the block facts and
 * acts on the verdict.
 *
 * <p><b>Reaction speed decides who does it.</b> Sprint-jumping is a skill, so it follows the
 * {@code reactionSpeed} trait:</p>
 * <ul>
 *   <li>0–4 — never.</li>
 *   <li>5 — sometimes: a coin flip per sprint ({@link #rollsRun}).</li>
 *   <li>6–8 — on a long, clear straight ({@link #CAUTIOUS_RUN} cells).</li>
 *   <li>9–10 — on any straight long enough to land on ({@link #MIN_OPEN_RUN} cells), and
 *       <em>head-bumping</em> through a 2-block-tall gap.</li>
 * </ul>
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

    /** Below this reaction speed a mob never sprint-jumps. */
    static final int MIN_REACTION = DispositionTraits.DEFAULT;
    /** From this reaction speed up a mob jumps shorter runs and head-bumps. */
    static final int EXPERT_REACTION = 9;
    /** Chance a reaction-{@value #MIN_REACTION} mob sprint-jumps on any given sprint. */
    static final double SOMETIMES_CHANCE = 0.5;

    /**
     * Shortest straight an open sprint-jump may start on: one leap carries about 4.3 blocks, so the
     * landing has to be inside the stretch that was checked.
     */
    static final int MIN_OPEN_RUN = 5;
    /** Straight a reaction 5–8 mob wants before it commits — it only jumps where it is obviously clear. */
    static final int CAUTIOUS_RUN = 7;
    /** Straight needed to head-bump: each clipped hop only carries about a block and a half. */
    static final int HEAD_BUMP_RUN = 3;
    /** How far ahead the driver needs to look to answer every case above. */
    public static final int LOOKAHEAD = CAUTIOUS_RUN;

    /** Yaw error (degrees) from the run direction within which a takeoff's boost is still on line. */
    static final float YAW_TOLERANCE = 15.0F;

    /**
     * Whether this mob sprint-jumps at all on the sprint it is starting. Rolled once per sprint, so
     * a "sometimes" mob either jumps the whole run or none of it rather than stuttering.
     *
     * @param roll a uniform random number in {@code [0, 1)}
     */
    public static boolean rollsRun(int reactionSpeed, double roll) {
        if (reactionSpeed < MIN_REACTION) {
            return false;
        }
        return reactionSpeed > MIN_REACTION || roll < SOMETIMES_CHANCE;
    }

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
     * @param reactionSpeed the mob's reaction speed, {@code [0, 10]}
     * @param runLength     cells of straight, level path ahead (see {@link #straightRunLength})
     * @param safe          per cell: clear to stand in, solid underfoot, and solid under both side
     *                      neighbours (nothing to fall off)
     * @param lowCeiling    per cell: a solid block directly above the mob's head, i.e. the cell is
     *                      exactly two blocks tall
     */
    public static Mode classify(int reactionSpeed, int runLength, boolean[] safe, boolean[] lowCeiling) {
        if (reactionSpeed < MIN_REACTION) {
            return Mode.NONE;
        }
        int usable = Math.min(runLength, Math.min(safe.length, lowCeiling.length));
        int safeCells = 0;
        while (safeCells < usable && safe[safeCells]) {
            safeCells++;
        }
        boolean expert = reactionSpeed >= EXPERT_REACTION;
        if (safeCells >= HEAD_BUMP_RUN && allEqual(lowCeiling, HEAD_BUMP_RUN, true)) {
            return expert ? Mode.HEAD_BUMP : Mode.NONE;
        }
        int needed = expert ? MIN_OPEN_RUN : CAUTIOUS_RUN;
        if (safeCells >= needed && allEqual(lowCeiling, needed, false)) {
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
