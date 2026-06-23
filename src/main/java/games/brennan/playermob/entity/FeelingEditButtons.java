package games.brennan.playermob.entity;

import java.util.List;
import java.util.UUID;

/**
 * Maps a PlayerMob menu button id to a one-step edit of one <b>relationship
 * feeling</b>, for the Creative editor's per-relationship {@code [-]}/{@code [+]}
 * buttons. Counterpart to {@link TraitEditButtons} (which owns ids {@code 0..3});
 * feeling ids start at {@link #FEELING_BASE}.
 *
 * <p>The vanilla container-button channel carries only a single {@code int}, so a
 * relationship is addressed by its <b>row index</b> in a stable, value-independent
 * order — {@link FeelingLedger#uuidsSorted()} (the same met-individual set the
 * client sees via the synced field, sorted by UUID). The client encodes
 * {@code idFor(row, up)} on each button and the server decodes the row back to the
 * same UUID. Because both sides use the <em>current</em> sorted order, the button
 * beside a row always edits the individual shown there, and editing a value never
 * reorders the list (so a row stays put while clicked).</p>
 *
 * <p>Pure and side-effect-light (it mutates only the {@link FeelingLedger} passed
 * in; clamping lives in {@link FeelingLedger#adjust(UUID, float)}), so it unit-tests
 * without a live world — exactly like {@link TraitEditButtons#apply(int, DispositionTraits)}.</p>
 */
public final class FeelingEditButtons {

    /** Feeling button ids start here ({@code 0..3} are reserved by {@link TraitEditButtons}). */
    public static final int FEELING_BASE = 4;
    /** Number of editable relationship rows; must equal the screen's row cap. */
    public static final int MAX_ROWS = 7;
    /** Feeling change per click. */
    public static final float STEP = 1.0F;

    private FeelingEditButtons() {
    }

    /** Button id for the {@code [-]} ({@code up=false}) / {@code [+]} ({@code up=true}) of relationship {@code row}. */
    public static int idFor(int row, boolean up) {
        return FEELING_BASE + row * 2 + (up ? 1 : 0);
    }

    /** True if {@code id} is one of the feeling buttons (in range for {@link #MAX_ROWS} rows). */
    public static boolean isFeelingButton(int id) {
        return id >= FEELING_BASE && id < FEELING_BASE + MAX_ROWS * 2;
    }

    /** The relationship row index encoded in a feeling button {@code id}. */
    public static int rowOf(int id) {
        return (id - FEELING_BASE) / 2;
    }

    /** True if a feeling button {@code id} is a {@code [+]} (increase). */
    public static boolean isUp(int id) {
        return ((id - FEELING_BASE) & 1) == 1;
    }

    /**
     * Apply the feeling adjustment for {@code id} to {@code ledger}: step the
     * row-th individual (in {@link FeelingLedger#nonDefaultUuidsSorted()} order)
     * by ±{@link #STEP}, clamped to {@code [0, 10]} by the ledger.
     *
     * @return {@code true} if {@code id} is a feeling button addressing a present
     *     row (a non-feeling id, or a stale/out-of-range row, returns {@code false}
     *     and leaves the ledger untouched).
     */
    public static boolean apply(int id, FeelingLedger ledger) {
        if (!isFeelingButton(id)) {
            return false;
        }
        List<UUID> order = ledger.uuidsSorted();
        int row = rowOf(id);
        if (row < 0 || row >= order.size()) {
            return false;
        }
        ledger.adjust(order.get(row), isUp(id) ? STEP : -STEP);
        return true;
    }
}
