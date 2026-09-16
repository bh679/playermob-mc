package games.brennan.playermob.entity;

/**
 * Maps a PlayerMob menu button id to the Creative editor's <b>add-relation picker</b>:
 * one {@link #REFRESH} id (re-snapshot the nearby-PlayerMob candidate list) and one
 * "pick row {@code r}" id per candidate row, with the <b>mirror</b> flag folded into the
 * low bit. Counterpart to {@link FeelingEditButtons} (which owns the per-row feeling
 * arrows); picker ids start at {@link #PICK_BASE}, immediately after the feeling rows.
 *
 * <p>The vanilla container-button channel carries only a single {@code int} — and on
 * 1.20.1 {@code ServerboundContainerButtonClickPacket} writes it as a <b>signed byte</b>,
 * so every id must stay {@code <= 127}. That is why a candidate is addressed by its
 * <b>row index</b> in the server-sent snapshot (see {@code RelationCandidates}) rather than
 * by entity id, and why {@link #MAX_CANDIDATES} is small. The client encodes
 * {@code idFor(row, mirror)}; the server decodes the row back to the UUID it snapshotted,
 * so a stale click can only ever target a mob that <em>was</em> offered — never a wrong one.</p>
 */
public final class RelationPickerButtons {

    /** First picker id — right after the last feeling-row arrow, so the ranges can't overlap. */
    public static final int PICK_BASE = FeelingEditButtons.FEELING_BASE + FeelingEditButtons.MAX_ROWS * 2;
    /** "Re-scan nearby PlayerMobs and re-sync the candidate list." Sent when the picker opens. */
    public static final int REFRESH = PICK_BASE;
    /** First "pick row" id; row {@code r} occupies {@code ADD_BASE + 2r} (plain) / {@code + 1} (mirror). */
    public static final int ADD_BASE = PICK_BASE + 1;
    /** Candidate rows the picker can address; must fit the screen's picker column. */
    public static final int MAX_CANDIDATES = 12;
    /** Hard ceiling from the 1.20.1 byte-wide button id. */
    public static final int MAX_BUTTON_ID = Byte.MAX_VALUE;

    private RelationPickerButtons() {
    }

    /** Button id that adds candidate {@code row}, also adding the reverse entry when {@code mirror}. */
    public static int idFor(int row, boolean mirror) {
        return ADD_BASE + row * 2 + (mirror ? 1 : 0);
    }

    public static boolean isRefresh(int id) {
        return id == REFRESH;
    }

    /** True if {@code id} is a "pick row" id for a row within {@link #MAX_CANDIDATES}. */
    public static boolean isPickButton(int id) {
        return id >= ADD_BASE && id < ADD_BASE + MAX_CANDIDATES * 2;
    }

    /** The candidate row index encoded in a pick button {@code id}. */
    public static int rowOf(int id) {
        return (id - ADD_BASE) / 2;
    }

    /** True if a pick button {@code id} asked for the relation to be mirrored onto the other mob. */
    public static boolean isMirror(int id) {
        return ((id - ADD_BASE) & 1) == 1;
    }
}
