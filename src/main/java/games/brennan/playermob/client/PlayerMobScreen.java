package games.brennan.playermob.client;

import games.brennan.playermob.entity.FeelingEditButtons;
import games.brennan.playermob.entity.LinkEditButtons;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.TraitEditButtons;
import games.brennan.playermob.menu.PlayerMobMenu;
import games.brennan.playermob.menu.RelationCandidates;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if >=26 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client screen for {@link PlayerMobMenu}. Draws a vanilla-styled window
 * <b>programmatically</b> (no texture asset shipped): a raised grey panel plus
 * a recessed cell behind every slot — including the player inventory — so the
 * whole thing reads like a normal Minecraft container. Armor/off-hand empty
 * icons are supplied by the menu's slot backgrounds.
 *
 * <p>A middle <b>disposition panel</b> shows the mob's two personal traits
 * (Fight/Flight, Friendliness) and a <b>Relationships</b> list — one row per
 * individual the mob has met, each with that target's face, name, and feeling
 * (0–10, hate→love) plus, for a PlayerMob target, that mob's feeling <em>back</em> toward
 * this one ({@code ←7.0}, read from the other mob's own synced ledger). All read live from the entity's synced disposition fields,
 * so values update while the menu is open. An <b>Edit</b> toggle in the panel
 * header reveals the per-trait and per-relationship {@code [-]}/{@code [+]}
 * buttons (hidden by default) that edit values in Creative over the vanilla
 * container-button channel (see {@link PlayerMobMenu#clickMenuButton}); the
 * server clamps and re-syncs, so the panel reflects the edit next frame. Each PlayerMob row
 * also has an {@code [M]} <b>Mirror</b> toggle ({@link LinkEditButtons}) that links the two
 * mobs' feelings for editor edits (green underline = linked).
 * Relationship rows are ordered by UUID (stable) so a row stays put while you
 * adjust it. In edit mode the Relationships header also gains a {@code [+]} that opens the
 * <b>add-relation picker</b> ({@link RelationPicker}) — a list of nearby PlayerMobs the
 * server snapshotted, shown in place of the objectives column, with a Mirror toggle. A
 * right-hand <b>objectives column</b> shows the mob's live goal stack.</p>
 *
 * <p>{@link Environment} {@code CLIENT}-only — stripped from dedicated server
 * jars at load time, same pattern as {@code PlayerMobRenderer}. Registered per
 * loader via {@code MenuScreens.register} / {@code RegisterMenuScreensEvent}.</p>
 */
@Environment(EnvType.CLIENT)
public class PlayerMobScreen extends AbstractContainerScreen<PlayerMobMenu> {

    // Vanilla GUI palette.
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_HILIGHT = 0xFFFFFFFF;
    private static final int PANEL_SHADOW = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int SLOT_SHADOW = 0xFF373737; // top/left inner edge
    private static final int SLOT_HILIGHT = 0xFFFFFFFF; // bottom/right inner edge

    // ---- Disposition (feelings) panel — immediately right of the slots ----
    private static final int INVENTORY_WIDTH = 176;   // the original window's content width
    private static final int PANEL_X = INVENTORY_WIDTH + 4;
    private static final int PANEL_TOP = 8;
    private static final int BAR_WIDTH = 124;
    private static final int FACE_SIZE = 8;
    private static final int ROW_HEIGHT = 13;
    // Editable relationship rows shown; shared with FeelingEditButtons so the row
    // index ↔ button id mapping covers exactly the rows that have buttons.
    private static final int MAX_RELATIONSHIP_ROWS = FeelingEditButtons.MAX_ROWS;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int VALUE_COLOR = 0xFF202020;
    private static final int MUTED_COLOR = 0xFF808080;
    // Wide enough for the bars + the right-aligned edit buttons before the objectives divider.
    private static final int DISPOSITION_WIDTH = 136;

    /**
     * Trait rows sit {@value #TRAIT_ROW_PITCH}px apart, so each trait added below costs the
     * window that much height (see {@link #WINDOW_HEIGHT}). Kept as one constant so the row
     * offsets and the window height can't drift apart.
     */
    private static final int TRAIT_ROW_PITCH = 23;

    /**
     * Window height. The disposition panel was tuned to fill a 186px window exactly with two
     * trait rows and {@link #MAX_RELATIONSHIP_ROWS} relationships, so the third trait row costs
     * one {@link #TRAIT_ROW_PITCH}. Growing the window rather than compressing the rows keeps
     * every existing row at its tuned spacing.
     */
    private static final int WINDOW_HEIGHT = 186 + TRAIT_ROW_PITCH;
    /** The "Inventory" label's y in the original 186px window — pinned, see the constructor. */
    private static final int INVENTORY_LABEL_Y = 186 - 94;

    // Vertical offsets of each panel element from the panel top (topPos + PANEL_TOP).
    // Shared by the renderer (labels/bars) and init() (edit buttons) so they stay aligned.
    private static final int TRAITS_HEADER_DY = 0;
    private static final int FF_LABEL_DY = 12;
    private static final int FF_BAR_DY = 26;
    private static final int FRIEND_LABEL_DY = 35;
    private static final int FRIEND_BAR_DY = 49;
    private static final int REACT_LABEL_DY = 58;
    private static final int REACT_BAR_DY = 72;
    private static final int REL_HEADER_DY = 81;
    private static final int REL_ROWS_DY = 92;

    // Trait edit cluster: [-] value [+], right-aligned to the bar's right edge.
    private static final int BUTTON_SIZE = 12;
    private static final int BUTTON_GAP = 2;
    private static final int VALUE_FIELD_W = 14; // room for the value drawn between the buttons
    private static final int CLUSTER_W = BUTTON_SIZE * 2 + BUTTON_GAP * 2 + VALUE_FIELD_W;

    // "Edit"/"Done" toggle (top-right of the panel header) that reveals/hides every edit arrow.
    private static final int EDIT_TOGGLE_W = 30;
    private static final int EDIT_TOGGLE_H = 12;

    // Per-relationship edit buttons: [M] [-] [+] at the right of each row.
    private static final int REL_BTN_SIZE = 10;
    private static final int REL_BTN_GAP = 1;
    private static final int LINKED_COLOR = 0xFF30B030;

    // ---- Creative objectives column — right of the disposition panel ----
    private static final int OBJECTIVES_X = INVENTORY_WIDTH + DISPOSITION_WIDTH;
    private static final int OBJECTIVES_GUTTER = 124; // width reserved for the objectives column
    private static final int OBJECTIVES_HEADER_COLOR = 0xFF404040;
    private static final int OBJECTIVES_TEXT_COLOR = 0xFF404040;
    private static final int OBJECTIVES_SUB_COLOR = 0xFF707070;

    /** Name / face / feeling-back lookups for the relationship rows (names cached per session). */
    private final RelationIdentity identity = new RelationIdentity();

    /** Per-relationship feeling edit buttons, rebuilt when the relationship set changes. */
    private final List<Button> relationshipButtons = new ArrayList<>();
    /** Per-relationship {@code [M]} Mirror toggles (row-indexed); hidden on player rows. */
    private final List<Button> linkButtons = new ArrayList<>();
    /** Stable UUID order the relationship buttons were last built for (change ⇒ rebuild). */
    private List<UUID> relationshipOrder = List.of();

    /**
     * When {@code false} (default) the per-trait and per-relationship {@code [-]}/{@code [+]} edit
     * arrows are hidden behind {@link #editToggle}; the disposition display (labels, values, bars,
     * relationship rows, objectives) stays visible regardless.
     */
    private boolean editMode = false;
    /** Persistent header toggle that flips {@link #editMode}; relabels "Edit" ⇄ "Done". */
    private Button editToggle;
    /** The six trait {@code [-]}/{@code [+]} arrows (two per trait), hidden unless {@link #editMode}. */
    private final List<Button> traitButtons = new ArrayList<>();
    /** {@code [+]} on the Relationships header that opens {@link #picker}; hidden unless {@link #editMode}. */
    private Button addRelationButton;
    /** The add-relation picker's state + widgets; survives re-init so a resize keeps it open. */
    private final RelationPicker picker = new RelationPicker(
        this::sendButton, this::addRenderableWidget, this::removeWidget);

    public PlayerMobScreen(PlayerMobMenu menu, Inventory playerInv, Component title) {
        //? if >=26 {
        /*// 26.x made imageWidth/imageHeight final — they're passed through the 5-arg super ctor.
        // (176 inventory + disposition panel + objectives column.)
        super(menu, playerInv, title,
            INVENTORY_WIDTH + DISPOSITION_WIDTH + OBJECTIVES_GUTTER, WINDOW_HEIGHT);
        *///?} else {
        super(menu, playerInv, title);
        // 176 inventory + disposition panel + objectives column.
        this.imageWidth = INVENTORY_WIDTH + DISPOSITION_WIDTH + OBJECTIVES_GUTTER;
        this.imageHeight = WINDOW_HEIGHT;
        //?}
        // Pinned to the slot grid, NOT derived from imageHeight: the menu's slot coordinates are
        // absolute, so growing the window for a third trait row must not drag the "Inventory"
        // label away from the slots it labels. The extra height shows as a strip below the hotbar.
        this.inventoryLabelY = INVENTORY_LABEL_Y;
    }

    /**
     * Add the Creative disposition-edit buttons: the persistent {@code Edit} toggle plus
     * the (initially hidden) three trait pairs and one pair per relationship row. Runs after
     * {@code super.init()} has set {@code leftPos}/{@code topPos}, so button bounds resolve to
     * absolute screen coordinates. Skipped on the client fallback (no resolved mob — the panel
     * renders "(no data)" instead).
     */
    @Override
    protected void init() {
        super.init();
        if (this.menu.getMob() == null) {
            return;
        }
        int top = this.topPos + PANEL_TOP;
        addEditToggle(top);
        traitButtons.clear();
        addTraitButtons(top + FF_LABEL_DY, TraitEditButtons.FIGHT_FLIGHT_DOWN, TraitEditButtons.FIGHT_FLIGHT_UP);
        addTraitButtons(top + FRIEND_LABEL_DY, TraitEditButtons.FRIENDLINESS_DOWN, TraitEditButtons.FRIENDLINESS_UP);
        addTraitButtons(top + REACT_LABEL_DY, TraitEditButtons.REACTION_SPEED_DOWN, TraitEditButtons.REACTION_SPEED_UP);
        rebuildRelationshipButtons();
        addAddRelationButton(top);
        picker.init(this.leftPos + OBJECTIVES_X, this.topPos);
    }

    /** The {@code [+]} on the Relationships header line, right-aligned like the row arrows. */
    private void addAddRelationButton(int top) {
        addRelationButton = Button.builder(Component.literal("+"), b -> picker.open())
            .bounds(relPlusX(), top + REL_HEADER_DY - 1, REL_BTN_SIZE, REL_BTN_SIZE).build();
        addRelationButton.visible = editMode;
        addRenderableWidget(addRelationButton);
    }

    /**
     * Add the persistent header {@link #editToggle}, right-aligned to the bar's right edge on the
     * "Traits" line. Always visible (the menu only opens in Creative); pressing it flips
     * {@link #editMode}. Re-created on every {@link #init()} (incl. resize), so its label is seeded
     * from the current {@code editMode} to survive a re-init.
     */
    private void addEditToggle(int top) {
        int x = this.leftPos + PANEL_X + BAR_WIDTH - EDIT_TOGGLE_W;
        int y = top + TRAITS_HEADER_DY - 2; // centre the 12px button on the header text
        editToggle = Button.builder(editLabel(), b -> toggleEditMode())
            .bounds(x, y, EDIT_TOGGLE_W, EDIT_TOGGLE_H).build();
        addRenderableWidget(editToggle);
    }

    private Component editLabel() {
        return Component.literal(editMode ? "Done" : "Edit");
    }

    /** Flip edit mode: relabel the toggle and show/hide every trait + relationship edit arrow. */
    private void toggleEditMode() {
        editMode = !editMode;
        if (editToggle != null) {
            editToggle.setMessage(editLabel());
        }
        applyEditVisibility();
    }

    /** Apply {@link #editMode} to the visibility of every edit arrow (trait + relationship). */
    private void applyEditVisibility() {
        for (Button b : traitButtons) {
            b.visible = editMode;
        }
        for (Button b : relationshipButtons) {
            b.visible = editMode;
        }
        for (int row = 0; row < linkButtons.size(); row++) {
            linkButtons.get(row).visible = editMode && !RelationIdentity.isPlayer(relationshipOrder.get(row));
        }
        if (addRelationButton != null) {
            addRelationButton.visible = editMode;
        }
        if (!editMode) {
            picker.close(); // "Done" also dismisses an open picker
        }
    }

    /**
     * Rebuild the per-relationship feeling buttons from the mob's current synced
     * feelings, in the same stable UUID order the panel renders and the server maps.
     * Called from {@link #init()} and from {@link #containerTick()} when the
     * relationship set changes (a new individual met while the menu is open). Mere
     * value edits leave the UUID order unchanged, so they don't trigger a rebuild.
     */
    private void rebuildRelationshipButtons() {
        for (Button b : relationshipButtons) {
            this.removeWidget(b);
        }
        for (Button b : linkButtons) {
            this.removeWidget(b);
        }
        relationshipButtons.clear();
        linkButtons.clear();
        PlayerMobEntity mob = this.menu.getMob();
        if (mob == null) {
            relationshipOrder = List.of();
            return;
        }
        relationshipOrder = stableFeelingOrder(mob);
        int rowsTop = this.topPos + PANEL_TOP + REL_ROWS_DY;
        int shown = Math.min(MAX_RELATIONSHIP_ROWS, relationshipOrder.size());
        for (int i = 0; i < shown; i++) {
            int by = rowsTop + i * ROW_HEIGHT; // align button top with the row's face/text
            final int row = i;
            addRelationshipButton(relMinusX(), by, FeelingEditButtons.idFor(row, false), "-");
            addRelationshipButton(relPlusX(), by, FeelingEditButtons.idFor(row, true), "+");
            addLinkButton(relLinkX(), by, LinkEditButtons.idFor(row), relationshipOrder.get(row));
        }
    }

    /** The {@code [M]} Mirror toggle for one row — only meaningful for a PlayerMob target. */
    private void addLinkButton(int x, int y, int id, UUID target) {
        Button b = Button.builder(Component.literal("M"), btn -> sendButton(id))
            .bounds(x, y, REL_BTN_SIZE, REL_BTN_SIZE)
            .tooltip(Tooltip.create(Component.literal("Mirror: keep this feeling and the other mob's feeling back in sync")))
            .build();
        b.visible = editMode && !RelationIdentity.isPlayer(target);
        linkButtons.add(b);
        this.addRenderableWidget(b);
    }

    private void addRelationshipButton(int x, int y, int id, String glyph) {
        Button b = Button.builder(Component.literal(glyph), btn -> sendButton(id))
            .bounds(x, y, REL_BTN_SIZE, REL_BTN_SIZE).build();
        b.visible = editMode; // hidden until the Edit toggle is on (also covers the containerTick rebuild)
        relationshipButtons.add(b);
        this.addRenderableWidget(b);
    }

    /**
     * Keep the relationship buttons aligned with the live list. Cheap: only rebuilds
     * when the stable UUID order actually changes (a new individual met), which
     * editing a feeling value does not.
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        PlayerMobEntity mob = this.menu.getMob();
        if (mob != null && !stableFeelingOrder(mob).equals(relationshipOrder)) {
            rebuildRelationshipButtons();
        }
        picker.tick(this.menu.candidateEntityIds());
    }

    /**
     * The mob's relationships in the stable order shared with the server's
     * {@code FeelingLedger.uuidsSorted()} — the synced (all met) set, sorted by
     * UUID. So row index ↔ button id resolves to the same individual on both sides.
     */
    private static List<UUID> stableFeelingOrder(PlayerMobEntity mob) {
        List<UUID> ids = new ArrayList<>(mob.getSyncedFeelings().keySet());
        ids.sort(null); // UUID is Comparable — ascending, matches the server
        return ids;
    }

    private int relMinusX() {
        return this.leftPos + PANEL_X + BAR_WIDTH - 2 * REL_BTN_SIZE - REL_BTN_GAP;
    }

    private int relPlusX() {
        return this.leftPos + PANEL_X + BAR_WIDTH - REL_BTN_SIZE;
    }

    private int relLinkX() {
        return relMinusX() - REL_BTN_SIZE - REL_BTN_GAP;
    }

    /** A {@code [-] [+]} button pair for one trait, on its label line. Hidden unless {@link #editMode}. */
    private void addTraitButtons(int labelY, int downId, int upId) {
        int y = labelY - 2; // centre the 12px button on the ~8px label text
        addTraitArrow(minusX(), y, downId, "-");
        addTraitArrow(plusX(), y, upId, "+");
    }

    private void addTraitArrow(int x, int y, int id, String glyph) {
        Button b = Button.builder(Component.literal(glyph), btn -> sendButton(id))
            .bounds(x, y, BUTTON_SIZE, BUTTON_SIZE).build();
        b.visible = editMode;
        traitButtons.add(b);
        addRenderableWidget(b);
    }

    /** Send a disposition edit over the vanilla container-button channel; the server clamps + re-syncs. */
    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    // Trait edit-cluster geometry (right-aligned to the bar). Depends on leftPos, set by init().
    private int clusterLeft() {
        return this.leftPos + PANEL_X + BAR_WIDTH - CLUSTER_W;
    }

    private int minusX() {
        return clusterLeft();
    }

    private int plusX() {
        return clusterLeft() + BUTTON_SIZE + BUTTON_GAP + VALUE_FIELD_W + BUTTON_GAP;
    }

    private int valueCenterX() {
        return clusterLeft() + BUTTON_SIZE + BUTTON_GAP + VALUE_FIELD_W / 2;
    }

    //? if >=26 {
    /*// 26.x renders screens via extractRenderState → extractContents → extractLabels. extractContents
    // runs Screen.extractRenderState (which extracts the renderable WIDGETS) FIRST, then translates
    // the pose to (leftPos, topPos) and calls extractLabels (before extractSlots). So we split it:
    //  - the panel + slot recesses go in extractContents BEFORE super, in absolute coords, so they
    //    sit behind BOTH the edit-button widgets and the slot items;
    //  - the disposition panel + objectives column go in extractLabels (window-local), on top.
    // Tooltips are handled by the base automatically (no renderTooltip).
    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        renderBg(g, partialTick, mouseX, mouseY); // absolute coords, BEFORE widgets + slots
        super.extractContents(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        // extractLabels runs pose-translated to (leftPos, topPos). drawDispositionPanel and the coord
        // helpers all ADD leftPos/topPos for absolute space, so zero them for the duration: every
        // coordinate resolves window-local, the base translate puts it back, and value text still
        // lines up with the absolute edit-button widgets. (No g.pose() manipulation — the extractor
        // batches draws, so a mid-extract translate corrupts the slot rendering.)
        int lp = this.leftPos;
        int tp = this.topPos;
        this.leftPos = 0;
        this.topPos = 0;
        try {
            drawDispositionPanel(g);
            drawObjectivesColumn(g, mouseX, mouseY);
        } finally {
            this.leftPos = lp;
            this.topPos = tp;
        }
    }
    *///?} else {
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawDispositionPanel(guiGraphics);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
    //?}

    // pre-26: the base window-local label hook. On 26.x there is no renderLabels override —
    // extractLabels (above) calls drawObjectivesColumn directly in the same window-local space.
    //? if <26 {
    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderLabels(guiGraphics, mouseX, mouseY);
        drawObjectivesColumn(guiGraphics, mouseX, mouseY);
    }
    //?}

    /**
     * Draws the Creative objectives column to the right of the inventory: the
     * mob's live goal stack ("Objective" then an indented phase), read from the
     * synced {@link PlayerMobEntity#getObjectivesReadout()}. Refreshes each frame
     * as the mob's goals change. Drawn in window-local coordinate space (origin at
     * the top-left of the panel) — pre-26 from renderLabels, on 26.x from extractLabels.
     */
    //? if >=26 {
    /*private void drawObjectivesColumn(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
    *///?} else {
    private void drawObjectivesColumn(GuiGraphics guiGraphics, int mouseX, int mouseY) {
    //?}

        PlayerMobEntity mob = this.menu.getMob();
        if (mob == null) {
            return; // client fallback before the entity resolved — no live state
        }
        if (picker.isOpen()) {
            drawPickerColumn(guiGraphics);
            return;
        }

        int gx = OBJECTIVES_X + 7;
        //? if >=26 {
        /*guiGraphics.text(this.font, "Objectives", gx, 6, OBJECTIVES_HEADER_COLOR, false);
        *///?} else {
        guiGraphics.drawString(this.font, "Objectives", gx, 6, OBJECTIVES_HEADER_COLOR, false);
        //?}

        String readout = mob.getObjectivesReadout();
        if (readout == null || readout.isEmpty()) {
            readout = "Idle";
        }

        int y = 20;
        for (String entry : readout.split("\n")) {
            int sep = entry.indexOf(" — ");
            if (sep >= 0) {
                //? if >=26 {
                /*guiGraphics.text(this.font, entry.substring(0, sep),
                    gx, y, OBJECTIVES_TEXT_COLOR, false);
                *///?} else {
                guiGraphics.drawString(this.font, entry.substring(0, sep),
                    gx, y, OBJECTIVES_TEXT_COLOR, false);
                //?}
                y += this.font.lineHeight + 1;
                //? if >=26 {
                /*guiGraphics.text(this.font, "  " + entry.substring(sep + 3),
                    gx, y, OBJECTIVES_SUB_COLOR, false);
                *///?} else {
                guiGraphics.drawString(this.font, "  " + entry.substring(sep + 3),
                    gx, y, OBJECTIVES_SUB_COLOR, false);
                //?}
                y += this.font.lineHeight + 3;
            } else {
                //? if >=26 {
                /*guiGraphics.text(this.font, entry, gx, y, OBJECTIVES_TEXT_COLOR, false);
                *///?} else {
                guiGraphics.drawString(this.font, entry, gx, y, OBJECTIVES_TEXT_COLOR, false);
                //?}
                y += this.font.lineHeight + 3;
            }
        }
    }

    //? if >=26 {
    /*// Called from extractRenderState on 26 (renderBg is no longer a base override — no @Override).
    protected void renderBg(GuiGraphicsExtractor guiGraphics, float partialTick, int mouseX, int mouseY) {
    *///?} else {
    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
    //?}
        int x = this.leftPos;
        int y = this.topPos;

        // Raised panel: fill + bevel (light top-left, dark bottom-right).
        guiGraphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL);
        guiGraphics.fill(x, y, x + this.imageWidth, y + 1, PANEL_HILIGHT);
        guiGraphics.fill(x, y, x + 1, y + this.imageHeight, PANEL_HILIGHT);
        guiGraphics.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, PANEL_SHADOW);
        guiGraphics.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, PANEL_SHADOW);

        // Divider between the inventory area and the disposition panel.
        guiGraphics.fill(x + INVENTORY_WIDTH, y + 4, x + INVENTORY_WIDTH + 1, y + this.imageHeight - 4, PANEL_SHADOW);
        guiGraphics.fill(x + INVENTORY_WIDTH + 1, y + 4, x + INVENTORY_WIDTH + 2, y + this.imageHeight - 4, PANEL_HILIGHT);

        // Recessed cell behind every slot.
        for (Slot slot : this.menu.slots) {
            drawSlotRecess(guiGraphics, x + slot.x, y + slot.y);
        }

        // Bevelled divider between the disposition panel and the objectives column.
        int dividerX = x + OBJECTIVES_X;
        guiGraphics.fill(dividerX - 1, y + 4, dividerX, y + this.imageHeight - 4, PANEL_SHADOW);
        guiGraphics.fill(dividerX, y + 4, dividerX + 1, y + this.imageHeight - 4, PANEL_HILIGHT);
    }

    /** Draws an 18×18 recessed cell whose 16×16 interior sits at ({@code sx},{@code sy}). */
    //? if >=26 {
    /*private void drawSlotRecess(GuiGraphicsExtractor g, int sx, int sy) {
    *///?} else {
    private void drawSlotRecess(GuiGraphics g, int sx, int sy) {
    //?}
        int x0 = sx - 1;
        int y0 = sy - 1;
        g.fill(x0, y0, x0 + 18, y0 + 18, SLOT_BG);
        g.fill(x0, y0, x0 + 18, y0 + 1, SLOT_SHADOW);          // top
        g.fill(x0, y0, x0 + 1, y0 + 18, SLOT_SHADOW);          // left
        g.fill(x0, y0 + 17, x0 + 18, y0 + 18, SLOT_HILIGHT);   // bottom
        g.fill(x0 + 17, y0, x0 + 18, y0 + 18, SLOT_HILIGHT);   // right
    }

    // ---- Disposition panel ------------------------------------------------

    //? if >=26 {
    /*private void drawDispositionPanel(GuiGraphicsExtractor g) {
    *///?} else {
    private void drawDispositionPanel(GuiGraphics g) {
    //?}
        int x = this.leftPos + PANEL_X;
        int top = this.topPos + PANEL_TOP;
        PlayerMobEntity mob = this.menu.getMob();
        if (mob == null) {
            //? if >=26 {
            /*g.text(this.font, Component.literal("(no data)"), x, top, MUTED_COLOR, false);
            *///?} else {
            g.drawString(this.font, Component.literal("(no data)"), x, top, MUTED_COLOR, false);
            //?}
            return;
        }

        //? if >=26 {
        /*g.text(this.font, Component.literal("Traits"), x, top + TRAITS_HEADER_DY, LABEL_COLOR, false);
        *///?} else {
        g.drawString(this.font, Component.literal("Traits"), x, top + TRAITS_HEADER_DY, LABEL_COLOR, false);
        //?}
        drawTrait(g, x, top + FF_LABEL_DY, top + FF_BAR_DY, "Fight/Flight", mob.getSyncedFightFlight());
        drawTrait(g, x, top + FRIEND_LABEL_DY, top + FRIEND_BAR_DY, "Friendliness", mob.getSyncedFriendliness());
        drawTrait(g, x, top + REACT_LABEL_DY, top + REACT_BAR_DY, "Reaction", mob.getSyncedReactionSpeed());

        //? if >=26 {
        /*g.text(this.font, Component.literal("Relationships"), x, top + REL_HEADER_DY, LABEL_COLOR, false);
        *///?} else {
        g.drawString(this.font, Component.literal("Relationships"), x, top + REL_HEADER_DY, LABEL_COLOR, false);
        //?}
        int y = top + REL_ROWS_DY;

        Map<UUID, Float> feelings = mob.getSyncedFeelings();
        if (feelings.isEmpty()) {
            //? if >=26 {
            /*g.text(this.font, Component.literal("none yet"), x, y, MUTED_COLOR, false);
            *///?} else {
            g.drawString(this.font, Component.literal("none yet"), x, y, MUTED_COLOR, false);
            //?}
            return;
        }
        // Same stable UUID order as the edit buttons and the server mapping, so each
        // row's [-]/[+] edits the individual shown on that row.
        List<UUID> order = stableFeelingOrder(mob);
        Set<UUID> linked = mob.getSyncedLinked();
        int shown = Math.min(MAX_RELATIONSHIP_ROWS, order.size());
        for (int i = 0; i < shown; i++) {
            UUID id = order.get(i);
            drawRelationshipRow(g, x, y, id, feelings.get(id), RelationIdentity.backFeeling(mob, id), linked.contains(id));
            y += ROW_HEIGHT;
        }
        if (order.size() > shown) {
            //? if >=26 {
            /*g.text(this.font, Component.literal("+" + (order.size() - shown) + " more"),
                x, y, MUTED_COLOR, false);
            *///?} else {
            g.drawString(this.font, Component.literal("+" + (order.size() - shown) + " more"),
                x, y, MUTED_COLOR, false);
            //?}
        }
    }

    /**
     * One trait row: the label on the left, the live value centred between its
     * {@code [-]}/{@code [+]} edit buttons (those are widgets added in
     * {@link #init()}), and a 0–10 fill bar below.
     */
    //? if >=26 {
    /*private void drawTrait(GuiGraphicsExtractor g, int x, int labelY, int barY, String label, int value) {
    *///?} else {
    private void drawTrait(GuiGraphics g, int x, int labelY, int barY, String label, int value) {
    //?}
        //? if >=26 {
        /*g.text(this.font, Component.literal(label), x, labelY, VALUE_COLOR, false);
        *///?} else {
        g.drawString(this.font, Component.literal(label), x, labelY, VALUE_COLOR, false);
        //?}
        String text = String.valueOf(value);
        //? if >=26 {
        /*g.text(this.font, Component.literal(text),
            valueCenterX() - this.font.width(text) / 2, labelY, VALUE_COLOR, false);
        *///?} else {
        g.drawString(this.font, Component.literal(text),
            valueCenterX() - this.font.width(text) / 2, labelY, VALUE_COLOR, false);
        //?}
        g.fill(x, barY, x + BAR_WIDTH, barY + 3, 0xFF555555);
        int filled = Math.round(BAR_WIDTH * clamp01(value / 10f));
        g.fill(x, barY, x + filled, barY + 3, 0xFF4060C0);
    }

    /**
     * One relationship row: face, name, the other mob's feeling back ({@code ←7.0}, muted, or
     * {@code –} for a player / unloaded mob), this mob's feeling, then the {@code [M] [-] [+]}
     * widgets. A linked row gets a green underline beneath its {@code [M]} (edit mode only,
     * where the button is).
     */
    //? if >=26 {
    /*private void drawRelationshipRow(GuiGraphicsExtractor g, int x, int y, UUID id, float feeling,
                                     Float back, boolean linked) {
        // PlayerFaceRenderer became PlayerFaceExtractor in 26.2: it blits the 8x8 base face +
        // hat overlay from the 64x64 skin using normalised (0-1) UVs internally. White, opaque.
        PlayerFaceExtractor.extractRenderState(g, RelationIdentity.faceTexture(id), x, y, FACE_SIZE, true, false, 0xFFFFFFFF);
        String name = identity.name(id);
        g.text(this.font, Component.literal(trim(name)), x + FACE_SIZE + 3, y, VALUE_COLOR, false);
        String value = String.format(Locale.ROOT, "%.1f", feeling);
        int vx = relLinkX() - 2 - this.font.width(value); // just left of the [M] button
        g.text(this.font, Component.literal(value), vx, y, feelingColor(feeling), false);
        String backText = backText(back);
        g.text(this.font, Component.literal(backText), vx - 3 - this.font.width(backText), y,
            back == null ? MUTED_COLOR : feelingColor(back), false);
        if (linked && editMode) {
            g.fill(relLinkX(), y + REL_BTN_SIZE, relLinkX() + REL_BTN_SIZE, y + REL_BTN_SIZE + 1, LINKED_COLOR);
        }
    }
    *///?} else {
    private void drawRelationshipRow(GuiGraphics g, int x, int y, UUID id, float feeling,
                                     Float back, boolean linked) {
        PlayerFaceRenderer.draw(g, RelationIdentity.faceTexture(id), x, y, FACE_SIZE, true, false);
        String name = identity.name(id);
        g.drawString(this.font, Component.literal(trim(name)), x + FACE_SIZE + 3, y, VALUE_COLOR, false);
        String value = String.format(Locale.ROOT, "%.1f", feeling);
        int vx = relLinkX() - 2 - this.font.width(value); // just left of the [M] button
        g.drawString(this.font, Component.literal(value), vx, y, feelingColor(feeling), false);
        String backText = backText(back);
        g.drawString(this.font, Component.literal(backText), vx - 3 - this.font.width(backText), y,
            back == null ? MUTED_COLOR : feelingColor(back), false);
        if (linked && editMode) {
            g.fill(relLinkX(), y + REL_BTN_SIZE, relLinkX() + REL_BTN_SIZE, y + REL_BTN_SIZE + 1, LINKED_COLOR);
        }
    }
    //?}

    /** {@code ←7.0} for a known feeling back, {@code –} when there is none to show. */
    private static String backText(Float back) {
        return back == null ? "–" : "←" + String.format(Locale.ROOT, "%.1f", back);
    }

    /**
     * The add-relation picker in place of the objectives column: header, then each candidate
     * row's face + name drawn over its (empty-labelled) row button. Window-local coordinates,
     * like {@link #drawObjectivesColumn}. A candidate whose entity hasn't been tracked yet
     * (the data-slot sync can beat the entity-track packet by a tick) shows "…" and its row
     * is disabled until it resolves.
     */
    //? if >=26 {
    /*private void drawPickerColumn(GuiGraphicsExtractor g) {
    *///?} else {
    private void drawPickerColumn(GuiGraphics g) {
    //?}
        int gx = OBJECTIVES_X + RelationPicker.PAD;
        //? if >=26 {
        /*g.text(this.font, "Add relation", gx + 3, RelationPicker.HEADER_Y, OBJECTIVES_HEADER_COLOR, false);
        *///?} else {
        g.drawString(this.font, "Add relation", gx + 3, RelationPicker.HEADER_Y, OBJECTIVES_HEADER_COLOR, false);
        //?}
        List<Integer> ids = picker.candidateIds();
        if (ids.isEmpty()) {
            //? if >=26 {
            /*g.text(this.font, "nobody nearby", gx + 3, RelationPicker.ROWS_Y + 2, OBJECTIVES_SUB_COLOR, false);
            *///?} else {
            g.drawString(this.font, "nobody nearby", gx + 3, RelationPicker.ROWS_Y + 2, OBJECTIVES_SUB_COLOR, false);
            //?}
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        for (int row = 0; row < ids.size(); row++) {
            int y = RelationPicker.ROWS_Y + row * RelationPicker.ROW_H + 2; // centre 8px face in the 12px button
            Entity e = mc.level == null ? null : mc.level.getEntity(ids.get(row));
            boolean resolved = e instanceof LivingEntity living && RelationCandidates.isCandidateKind(living);
            picker.setRowActive(row, resolved);
            if (!resolved) {
                //? if >=26 {
                /*g.text(this.font, "…", gx + 3, y, OBJECTIVES_SUB_COLOR, false);
                *///?} else {
                g.drawString(this.font, "…", gx + 3, y, OBJECTIVES_SUB_COLOR, false);
                //?}
                continue;
            }
            UUID id = e.getUUID();
            //? if >=26 {
            /*PlayerFaceExtractor.extractRenderState(g, RelationIdentity.faceTexture(id), gx + 3, y, FACE_SIZE, true, false, 0xFFFFFFFF);
            g.text(this.font, RelationIdentity.trimTo(this.font, identity.name(id), RelationPicker.WIDTH - FACE_SIZE - 10),
                gx + 3 + FACE_SIZE + 3, y, OBJECTIVES_TEXT_COLOR, false);
            *///?} else {
            PlayerFaceRenderer.draw(g, RelationIdentity.faceTexture(id), gx + 3, y, FACE_SIZE, true, false);
            g.drawString(this.font, RelationIdentity.trimTo(this.font, identity.name(id), RelationPicker.WIDTH - FACE_SIZE - 10),
                gx + 3 + FACE_SIZE + 3, y, OBJECTIVES_TEXT_COLOR, false);
            //?}
        }
    }

    // ---- helpers ----------------------------------------------------------

    /** Truncate a name to fit the relationship row's name column (face … value [-] [+]). */
    private String trim(String name) {
        return RelationIdentity.trimTo(this.font, name, BAR_WIDTH - (FACE_SIZE + 3)
            - (3 * REL_BTN_SIZE + 2 * REL_BTN_GAP) - this.font.width("←10.0 10.0") - 7);
    }

    /** Hate (red) → neutral → love (green) colour for a 0–10 feeling. */
    private static int feelingColor(float feeling) {
        float t = clamp01(feeling / 10f);
        int r = (int) (0xC0 * (1 - t) + 0x30 * t);
        int gch = (int) (0x40 * (1 - t) + 0xB0 * t);
        return 0xFF000000 | (r << 16) | (gch << 8) | 0x30;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
