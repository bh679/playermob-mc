package games.brennan.playermob.entity;

/**
 * What a PlayerMob stuck against a carriage wall on a Dungeon Train does next: the door first, the
 * wall last. Each time the stuck monitor fires at the same spot the mob may climb one rung:
 *
 * <ol>
 *   <li>{@link Rung#USE_DOOR} — leave it to the door reflex and its stuck probe, which open and close
 *       the door. Nothing is dug.</li>
 *   <li>{@link Rung#UNBLOCK_DOOR} — soft fill stands between the mob and the door: dig that.</li>
 *   <li>{@link Rung#BREAK_DOOR} — the mob is at the door and the probe has tried it both ways without
 *       getting through: break the door.</li>
 *   <li>{@link Rung#BREAK_WALL} — there is no door in the wall ahead, or the way to it is blocked by
 *       something the mob may not dig: break through the wall itself.</li>
 * </ol>
 *
 * <p>Pure logic, no Minecraft types, so the ordering is unit-tested directly; the caller in the
 * Dungeon Train seam measures the wall, the door and the way to it in the carriage's own frame.</p>
 */
public final class StuckLadder {

    private StuckLadder() {}

    /** The rungs, lowest first. */
    public enum Rung { USE_DOOR, UNBLOCK_DOOR, BREAK_DOOR, BREAK_WALL }

    /** Probe strikes on the door after which it has been tried both ways (strike 2 is the other state). */
    static final int DOOR_TRIED_STRIKES = 3;

    /** Stuck firings at one spot before the door may be broken: never on the first pause. */
    static final int BREAK_DOOR_FIRINGS = 2;

    /** Stuck firings at one spot before a wall with no door may be broken. */
    static final int BREAK_WALL_NO_DOOR_FIRINGS = 2;

    /** Stuck firings at one spot before a wall may be broken when the way to its door is blocked. */
    static final int BREAK_WALL_BLOCKED_FIRINGS = 3;

    /**
     * Choose the rung.
     *
     * @param doorPresent    a door stands in the wall ahead
     * @param atDoor         the mob is in the door's row, right in front of it
     * @param probeStrikes   strikes the door probe has spent without the mob getting through
     * @param fillOnWay      soft fill the mob may dig stands between it and the door
     * @param wayBlocked     something the mob may not dig stands between it and the door
     * @param firings        consecutive stuck firings at this spot (1 on the first)
     */
    public static Rung rung(boolean doorPresent, boolean atDoor, int probeStrikes,
                            boolean fillOnWay, boolean wayBlocked, int firings) {
        if (!doorPresent) {
            return firings >= BREAK_WALL_NO_DOOR_FIRINGS ? Rung.BREAK_WALL : Rung.USE_DOOR;
        }
        if (atDoor) {
            return probeStrikes >= DOOR_TRIED_STRIKES && firings >= BREAK_DOOR_FIRINGS
                ? Rung.BREAK_DOOR : Rung.USE_DOOR;
        }
        if (fillOnWay) {
            return Rung.UNBLOCK_DOOR;
        }
        if (wayBlocked && firings >= BREAK_WALL_BLOCKED_FIRINGS) {
            return Rung.BREAK_WALL;
        }
        return Rung.USE_DOOR;
    }

    /**
     * Whether sub-level column {@code cellX} is one of its carriage's two end walls. A group's
     * carriages sit end to end from {@code originX} (the west wall of its first carriage), each
     * {@code length} blocks long, so the walls are the first and last column of
     * every {@code length}-block run. Never a wall when the length is unknown.
     */
    public static boolean isCarriageEndWall(int cellX, int originX, int length) {
        if (length < 3) {
            return false;
        }
        int offset = Math.floorMod(cellX - originX, length);
        return offset == 0 || offset == length - 1;
    }
}
