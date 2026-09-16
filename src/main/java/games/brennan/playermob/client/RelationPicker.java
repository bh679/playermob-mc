package games.brennan.playermob.client;

import games.brennan.playermob.entity.RelationPickerButtons;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * State + widgets for the Creative editor's <b>add-relation picker</b>, which takes over
 * {@link PlayerMobScreen}'s objectives column while open: a <b>Mirror</b> toggle, <b>Cancel</b>,
 * and one full-width row button per candidate the server offered (see
 * {@code PlayerMobMenu.candidateEntityIds()}). Clicking a row sends
 * {@link RelationPickerButtons#idFor(int, boolean)} over the container-button channel and
 * closes the picker; the server resolves the row against its own snapshot.
 *
 * <p>Owns only the widgets and the row ↔ entity-id list; the faces and names drawn over the
 * row buttons come from the screen (they need its version-split graphics code). Row buttons
 * are rebuilt only when the synced id list changes, mirroring how the screen rebuilds its
 * per-relationship arrows. {@code open} and {@code mirror} survive a screen re-init (resize)
 * because the screen keeps this object and calls {@link #init} again.</p>
 */
@Environment(EnvType.CLIENT)
public final class RelationPicker {

    /** Column-local layout (y from the window top, x from the column's left edge). */
    public static final int HEADER_Y = 6;
    public static final int CONTROLS_Y = 18;
    public static final int CONTROLS_H = 12;
    public static final int ROWS_Y = 34;
    public static final int ROW_H = 13;
    public static final int PAD = 4;
    public static final int WIDTH = 116;
    private static final int MIRROR_W = 70;
    private static final int CANCEL_W = WIDTH - MIRROR_W - PAD;

    private final IntConsumer sendButton;
    private final Consumer<AbstractWidget> addWidget;
    private final Consumer<AbstractWidget> removeWidget;

    private boolean open = false;
    /** Add the reverse entry on a picked PlayerMob too and link the pair (see LinkEditButtons). Default on. */
    private boolean mirror = true;

    private int columnX;
    private int windowTop;
    private Button mirrorToggle;
    private Button cancel;
    private final List<Button> rowButtons = new ArrayList<>();
    /** Entity ids the row buttons were last built for (row order = server order). */
    private List<Integer> ids = List.of();

    public RelationPicker(IntConsumer sendButton, Consumer<AbstractWidget> addWidget,
                          Consumer<AbstractWidget> removeWidget) {
        this.sendButton = sendButton;
        this.addWidget = addWidget;
        this.removeWidget = removeWidget;
    }

    /** (Re)create the control widgets at the column's absolute origin. Called from the screen's {@code init()}. */
    public void init(int columnX, int windowTop) {
        this.columnX = columnX;
        this.windowTop = windowTop;
        int y = windowTop + CONTROLS_Y;
        mirrorToggle = Button.builder(mirrorLabel(), b -> toggleMirror())
            .bounds(columnX + PAD, y, MIRROR_W, CONTROLS_H).build();
        cancel = Button.builder(Component.literal("Cancel"), b -> close())
            .bounds(columnX + PAD + MIRROR_W + PAD, y, CANCEL_W, CONTROLS_H).build();
        addWidget.accept(mirrorToggle);
        addWidget.accept(cancel);
        rowButtons.clear();
        rebuildRows(ids);
        applyVisibility();
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isMirror() {
        return mirror;
    }

    /** Row order of the candidates currently shown — the same order the server snapshotted. */
    public List<Integer> candidateIds() {
        return ids;
    }

    /** Show the picker and ask the server for a fresh candidate snapshot. */
    public void open() {
        open = true;
        sendButton.accept(RelationPickerButtons.REFRESH);
        applyVisibility();
    }

    public void close() {
        open = false;
        applyVisibility();
    }

    /** Keep the row buttons aligned with the synced id list; cheap when nothing changed. */
    public void tick(List<Integer> syncedIds) {
        if (!syncedIds.equals(ids)) {
            rebuildRows(syncedIds);
            applyVisibility();
        }
    }

    /** Enable/disable row {@code row} (a row whose entity the client can't resolve yet is inert). */
    public void setRowActive(int row, boolean active) {
        if (row >= 0 && row < rowButtons.size()) {
            rowButtons.get(row).active = active;
        }
    }

    /** Absolute y of row {@code row}'s top edge. */
    public int rowY(int row) {
        return windowTop + ROWS_Y + row * ROW_H;
    }

    private void toggleMirror() {
        mirror = !mirror;
        mirrorToggle.setMessage(mirrorLabel());
    }

    private Component mirrorLabel() {
        return Component.literal(mirror ? "Mirror: On" : "Mirror: Off");
    }

    private void rebuildRows(List<Integer> syncedIds) {
        for (Button b : rowButtons) {
            removeWidget.accept(b);
        }
        rowButtons.clear();
        ids = List.copyOf(syncedIds);
        int shown = Math.min(ids.size(), RelationPickerButtons.MAX_CANDIDATES);
        for (int i = 0; i < shown; i++) {
            final int row = i;
            // Empty label: the screen draws the face + name over the button.
            Button b = Button.builder(Component.empty(), btn -> pick(row))
                .bounds(columnX + PAD, rowY(row), WIDTH, ROW_H - 1).build();
            rowButtons.add(b);
            addWidget.accept(b);
        }
    }

    private void pick(int row) {
        sendButton.accept(RelationPickerButtons.idFor(row, mirror));
        close();
    }

    private void applyVisibility() {
        if (mirrorToggle != null) {
            mirrorToggle.visible = open;
        }
        if (cancel != null) {
            cancel.visible = open;
        }
        for (Button b : rowButtons) {
            b.visible = open;
        }
    }
}
