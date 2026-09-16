package games.brennan.playermob.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps a PlayerMob menu button id to the Creative editor's per-relationship <b>Mirror</b>
 * toggle — one id per relationship row, addressed by row index in the same stable
 * {@link FeelingLedger#uuidsSorted()} order as {@link FeelingEditButtons}. Ids start right after
 * the add-relation picker's ({@link RelationPickerButtons}); every id stays {@code <= 127} for
 * the byte-wide 1.20.1 button packet (asserted by the unit test).
 */
public final class LinkEditButtons {

    /** First link-toggle id — after the last picker id, so the ranges can't overlap. */
    public static final int LINK_BASE =
        RelationPickerButtons.ADD_BASE + RelationPickerButtons.MAX_CANDIDATES * 2;
    /** One toggle per editable relationship row. */
    public static final int MAX_ROWS = FeelingEditButtons.MAX_ROWS;

    private LinkEditButtons() {
    }

    /** Button id toggling the Mirror link of relationship {@code row}. */
    public static int idFor(int row) {
        return LINK_BASE + row;
    }

    public static boolean isLinkButton(int id) {
        return id >= LINK_BASE && id < LINK_BASE + MAX_ROWS;
    }

    public static int rowOf(int id) {
        return id - LINK_BASE;
    }

    /** The individual a link button {@code id} addresses, or empty for a non-link id / stale row. */
    public static Optional<UUID> targetOf(int id, FeelingLedger ledger) {
        if (!isLinkButton(id)) {
            return Optional.empty();
        }
        List<UUID> order = ledger.uuidsSorted();
        int row = rowOf(id);
        return row < 0 || row >= order.size() ? Optional.empty() : Optional.of(order.get(row));
    }
}
