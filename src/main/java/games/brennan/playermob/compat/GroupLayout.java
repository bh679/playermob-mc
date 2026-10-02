package games.brennan.playermob.compat;

/**
 * Where the rooms and end pads of one carriage group sit along the train's X axis. Pure maths over
 * primitives (no Minecraft or train-mod types), so it unit-tests without a game bootstrap.
 *
 * <p>A Dungeon Train group is not wall-to-wall rooms. A multi-carriage group carries a half-flatbed
 * <em>pad</em> at each end — a narrow walkway outside the end doors — so its bounding box reads
 * {@code [pad][room]…[room][pad]}. Adjacent groups' pads meet at a seam a fraction of a block wide,
 * which a mob simply walks across. Treating the whole box as rooms puts every room boundary in the
 * wrong place (by up to a third of the pad), which is how a mob came to believe it was in the last
 * room while still standing behind an inner door.</p>
 *
 * @param minX     low-X edge of the group's world bounding box
 * @param maxX     high-X edge
 * @param lowPidx  carriage index of the room at the low-X end
 * @param rooms    number of rooms (carriages) in the group, at least 1
 * @param pad      length of each end pad in blocks; 0 when the group has none
 */
public record GroupLayout(double minX, double maxX, int lowPidx, int rooms, double pad) {

    /** A measured pad shorter than this is bounding-box jitter, not a pad. */
    static final double MIN_PAD = 0.25;

    /**
     * Lay out a group from its world box, its carriage index range, and the length of one carriage.
     * The pad is whatever the box has left over once the rooms are accounted for, split evenly. An
     * unknown ({@code <= 0}) carriage length, or a box with nothing left over, means no pads and the
     * rooms fill the box — the layout a lone carriage has.
     */
    public static GroupLayout of(double minX, double maxX, int lowPidx, int highPidx, double carriageLength) {
        int rooms = Math.max(1, highPidx - lowPidx + 1);
        double length = Math.max(0.0, maxX - minX);
        double pad = carriageLength > 0.0 ? (length - rooms * carriageLength) / 2.0 : 0.0;
        if (pad < MIN_PAD) {
            pad = 0.0;
        }
        return new GroupLayout(minX, maxX, lowPidx, rooms, pad);
    }

    /** Low-X edge of the first room (the low end door). */
    public double roomsMinX() {
        return minX + pad;
    }

    /** High-X edge of the last room (the high end door). */
    public double roomsMaxX() {
        return maxX - pad;
    }

    /** Length of one room along X. */
    public double roomLength() {
        return (roomsMaxX() - roomsMinX()) / rooms;
    }

    /**
     * Which end pad {@code x} is on: {@code -1} the low-X pad, {@code +1} the high-X pad, {@code 0}
     * inside the rooms (always {@code 0} for a group without pads).
     */
    public int padSide(double x) {
        if (pad <= 0.0) {
            return 0;
        }
        if (x < roomsMinX()) {
            return -1;
        }
        return x > roomsMaxX() ? 1 : 0;
    }

    /** Carriage index of the room at {@code x}; a position on a pad counts as the room beside it. */
    public int roomPidx(double x) {
        double len = roomLength();
        if (len <= 0.0) {
            return lowPidx;
        }
        int index = (int) Math.floor((x - roomsMinX()) / len);
        return lowPidx + Math.max(0, Math.min(rooms - 1, index));
    }

    /** World X of the centre of the room with carriage index {@code pidx}. */
    public double roomCentreX(int pidx) {
        return roomsMinX() + (pidx - lowPidx + 0.5) * roomLength();
    }

    /**
     * World X of the centre of the next room to walk to from {@code x} in direction {@code dir}, or
     * {@code NaN} when there is none in this group (the step would leave it). From a pad, heading
     * inward, the next room is the one right beside the pad — not the one after it.
     */
    public double nextRoomCentreX(double x, int dir) {
        int step = Integer.signum(dir);
        if (step == 0) {
            return Double.NaN;
        }
        int side = padSide(x);
        if (side == step) {
            return Double.NaN;                       // on the end pad, facing out of the group
        }
        int target = side != 0 ? roomPidx(x) : roomPidx(x) + step;
        if (target < lowPidx || target > lowPidx + rooms - 1) {
            return Double.NaN;
        }
        return roomCentreX(target);
    }

    /** World X of the middle of the end pad in direction {@code dir}, or {@code NaN} without pads. */
    public double endPadCentreX(int dir) {
        if (pad <= 0.0 || dir == 0) {
            return Double.NaN;
        }
        return dir < 0 ? minX + pad / 2.0 : maxX - pad / 2.0;
    }
}
