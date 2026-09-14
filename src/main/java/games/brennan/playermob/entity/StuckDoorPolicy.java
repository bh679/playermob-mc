package games.brennan.playermob.entity;

import java.util.List;

/**
 * Which door a wedged mob should try next — the decision core of the on-train stuck probe.
 *
 * <p>The path-aware reflex only toggles a door it can prove is in the way. When a mob has
 * nonetheless been stuck for a while, the probe stops proving and starts trying: it toggles
 * a nearby door and gives the mob a moment to walk. Which door, and to which state:</p>
 * <ol>
 *   <li><b>Fresh probe</b> (no previous probe door, or that door is no longer nearby): the
 *       nearest door that obstructs travel along the train's axis (X) — the assumption that is
 *       almost always right on a Dungeon Train — else simply the nearest door. Toggle it.</li>
 *   <li><b>Second strike on the same door</b>: the first guess didn't free the mob, so toggle
 *       it back — legitimately trying the other state.</li>
 *   <li><b>Third strike and beyond</b>: both states of that door have been tried; pick a
 *       different nearby door by the same preference, or, if it is the only one, try it again.</li>
 * </ol>
 *
 * <p>Pure logic, no Minecraft types — doors arrive as {@link DoorCandidate}s keyed by an opaque
 * {@code long} (the caller uses the packed block position). Unit-tested directly; the live scan
 * and toggle are exercised by the in-game Gate 2 test. Stateless: the caller tracks the last
 * probe door and how many consecutive strikes it has had.</p>
 */
public final class StuckDoorPolicy {

    private StuckDoorPolicy() {}

    /** Sentinel for "no door" keys. */
    public static final long NO_DOOR = Long.MIN_VALUE;

    /**
     * A hand-openable door near the mob.
     *
     * @param key       opaque identity (the caller's packed position)
     * @param facingIsX whether the door's facing axis is X (else Z)
     * @param open      whether the door is currently open
     * @param distSq    squared distance from the mob, for "nearest" ordering
     */
    public record DoorCandidate(long key, boolean facingIsX, boolean open, long distSq) {

        /** Whether this door, as it stands, blocks travel along the train's axis (X). */
        public boolean obstructsTrainAxis() {
            return DoorObstruction.obstructs(facingIsX, open, true);
        }
    }

    /** The door to toggle and the state to set it to. */
    public record Choice(long key, boolean desiredOpen) {}

    /**
     * Choose the door to toggle for this probe.
     *
     * @param doors               hand doors within reach (any order)
     * @param lastProbeKey        the door the previous probe toggled, or {@link #NO_DOOR}
     * @param strikesOnLastProbe  consecutive probes already spent on {@code lastProbeKey}
     * @return the choice, or {@code null} if there is no door to try
     */
    public static Choice pick(List<DoorCandidate> doors, long lastProbeKey, int strikesOnLastProbe) {
        if (doors.isEmpty()) {
            return null;
        }
        DoorCandidate last = find(doors, lastProbeKey);
        if (last != null && strikesOnLastProbe >= 1) {
            if (strikesOnLastProbe == 1) {
                return toggle(last); // second strike: try the other state of the same door
            }
            DoorCandidate other = preferred(doors, lastProbeKey);
            return toggle(other != null ? other : last);
        }
        return toggle(preferred(doors, NO_DOOR));
    }

    /**
     * Whether the path-aware reflex may act on {@code key}: never the door a probe just set
     * while that probe's pin is live, so the reflex can't undo the attempt before the mob has
     * had its chance to walk through.
     */
    public static boolean reflexMayTouch(long key, long pinnedKey, int pinTicks) {
        return pinTicks <= 0 || key != pinnedKey;
    }

    /** Nearest door that obstructs the train axis, else nearest door; {@code exclude} is skipped. */
    private static DoorCandidate preferred(List<DoorCandidate> doors, long exclude) {
        DoorCandidate bestObstructing = null;
        DoorCandidate bestAny = null;
        for (DoorCandidate d : doors) {
            if (d.key() == exclude) {
                continue;
            }
            if (bestAny == null || d.distSq() < bestAny.distSq()) {
                bestAny = d;
            }
            if (d.obstructsTrainAxis() && (bestObstructing == null || d.distSq() < bestObstructing.distSq())) {
                bestObstructing = d;
            }
        }
        return bestObstructing != null ? bestObstructing : bestAny;
    }

    private static DoorCandidate find(List<DoorCandidate> doors, long key) {
        if (key == NO_DOOR) {
            return null;
        }
        for (DoorCandidate d : doors) {
            if (d.key() == key) {
                return d;
            }
        }
        return null;
    }

    private static Choice toggle(DoorCandidate d) {
        return new Choice(d.key(), !d.open());
    }
}
