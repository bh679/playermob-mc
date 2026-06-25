package games.brennan.playermob.client;

import games.brennan.playermob.compat.SkinCompat;
import games.brennan.playermob.entity.FeelingEditButtons;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.TraitEditButtons;
import games.brennan.playermob.menu.PlayerMobMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if >=26 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * (0–10, hate→love). All read live from the entity's synced disposition fields,
 * so values update while the menu is open. An <b>Edit</b> toggle in the panel
 * header reveals the per-trait and per-relationship {@code [-]}/{@code [+]}
 * buttons (hidden by default) that edit values in Creative over the vanilla
 * container-button channel (see {@link PlayerMobMenu#clickMenuButton}); the
 * server clamps and re-syncs, so the panel reflects the edit next frame.
 * Relationship rows are ordered by UUID (stable) so a row stays put while you
 * adjust it. A right-hand <b>objectives column</b> shows the mob's live goal
 * stack.</p>
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

    // Vertical offsets of each panel element from the panel top (topPos + PANEL_TOP).
    // Shared by the renderer (labels/bars) and init() (edit buttons) so they stay aligned.
    private static final int TRAITS_HEADER_DY = 0;
    private static final int FF_LABEL_DY = 12;
    private static final int FF_BAR_DY = 26;
    private static final int FRIEND_LABEL_DY = 35;
    private static final int FRIEND_BAR_DY = 49;
    private static final int REL_HEADER_DY = 58;
    private static final int REL_ROWS_DY = 69;

    // Trait edit cluster: [-] value [+], right-aligned to the bar's right edge.
    private static final int BUTTON_SIZE = 12;
    private static final int BUTTON_GAP = 2;
    private static final int VALUE_FIELD_W = 14; // room for the value drawn between the buttons
    private static final int CLUSTER_W = BUTTON_SIZE * 2 + BUTTON_GAP * 2 + VALUE_FIELD_W;

    // "Edit"/"Done" toggle (top-right of the panel header) that reveals/hides every edit arrow.
    private static final int EDIT_TOGGLE_W = 30;
    private static final int EDIT_TOGGLE_H = 12;

    // Per-relationship feeling edit buttons: [-] [+] at the right of each row.
    private static final int REL_BTN_SIZE = 10;
    private static final int REL_BTN_GAP = 1;

    // ---- Creative objectives column — right of the disposition panel ----
    private static final int OBJECTIVES_X = INVENTORY_WIDTH + DISPOSITION_WIDTH;
    private static final int OBJECTIVES_GUTTER = 124; // width reserved for the objectives column
    private static final int OBJECTIVES_HEADER_COLOR = 0xFF404040;
    private static final int OBJECTIVES_TEXT_COLOR = 0xFF404040;
    private static final int OBJECTIVES_SUB_COLOR = 0xFF707070;

    /** Names are stable for a session — resolve once per UUID. */
    private final Map<UUID, String> nameCache = new HashMap<>();

    /** Per-relationship feeling edit buttons, rebuilt when the relationship set changes. */
    private final List<Button> relationshipButtons = new ArrayList<>();
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
    /** The four trait {@code [-]}/{@code [+]} arrows (two per trait), hidden unless {@link #editMode}. */
    private final List<Button> traitButtons = new ArrayList<>();

    public PlayerMobScreen(PlayerMobMenu menu, Inventory playerInv, Component title) {
        //? if >=26 {
        /*// 26.x made imageWidth/imageHeight final — they're passed through the 5-arg super ctor.
        // (176 inventory + disposition panel + objectives column; height 186.)
        super(menu, playerInv, title,
            INVENTORY_WIDTH + DISPOSITION_WIDTH + OBJECTIVES_GUTTER, 186);
        *///?} else {
        super(menu, playerInv, title);
        // 176 inventory + disposition panel + objectives column.
        this.imageWidth = INVENTORY_WIDTH + DISPOSITION_WIDTH + OBJECTIVES_GUTTER;
        this.imageHeight = 186;
        //?}
        // Recompute since the field initialiser used the default height.
        this.inventoryLabelY = this.imageHeight - 94;
    }

    /**
     * Add the Creative disposition-edit buttons: the persistent {@code Edit} toggle plus
     * the (initially hidden) two trait pairs and one pair per relationship row. Runs after
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
        rebuildRelationshipButtons();
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
        relationshipButtons.clear();
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
        }
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
        int shown = Math.min(MAX_RELATIONSHIP_ROWS, order.size());
        for (int i = 0; i < shown; i++) {
            UUID id = order.get(i);
            drawRelationshipRow(g, x, y, id, feelings.get(id));
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

    //? if >=26 {
    /*private void drawRelationshipRow(GuiGraphicsExtractor g, int x, int y, UUID id, float feeling) {
        // PlayerFaceRenderer became PlayerFaceExtractor in 26.2: it blits the 8x8 base face +
        // hat overlay from the 64x64 skin using normalised (0-1) UVs internally. White, opaque.
        PlayerFaceExtractor.extractRenderState(g, resolveFaceTexture(id), x, y, FACE_SIZE, true, false, 0xFFFFFFFF);
        String name = nameCache.computeIfAbsent(id, this::computeName);
        g.text(this.font, Component.literal(trim(name)), x + FACE_SIZE + 3, y, VALUE_COLOR, false);
        String value = String.format(Locale.ROOT, "%.1f", feeling);
        int vx = relMinusX() - 2 - this.font.width(value); // just left of the [-] button
        g.text(this.font, Component.literal(value), vx, y, feelingColor(feeling), false);
    }
    *///?} else {
    private void drawRelationshipRow(GuiGraphics g, int x, int y, UUID id, float feeling) {
        PlayerFaceRenderer.draw(g, resolveFaceTexture(id), x, y, FACE_SIZE, true, false);
        String name = nameCache.computeIfAbsent(id, this::computeName);
        g.drawString(this.font, Component.literal(trim(name)), x + FACE_SIZE + 3, y, VALUE_COLOR, false);
        String value = String.format(Locale.ROOT, "%.1f", feeling);
        int vx = relMinusX() - 2 - this.font.width(value); // just left of the [-] button
        g.drawString(this.font, Component.literal(value), vx, y, feelingColor(feeling), false);
    }
    //?}

    // ---- Identity / face resolution (client-side) -------------------------

    private String computeName(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) {
                //? if >=26 {
                /*return info.getProfile().name(); // authlib 9: GameProfile.getName() → name()
                *///?} else {
                return info.getProfile().getName();
                //?}
            }
        }
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(id)) {
                    return e.getName().getString();
                }
            }
        }
        return id.toString().substring(0, 8);
    }

    /**
     * Resolve a face texture for {@code id}: a tab-list player's skin, else a
     * loaded PlayerMob's skin, else a generic Steve/Alex default. Re-resolved
     * each frame so async-loading player skins flip in once cached.
     */
    //? if >=26 {
    /*private Identifier resolveFaceTexture(UUID id) {
    *///?} else {
    private ResourceLocation resolveFaceTexture(UUID id) {
    //?}
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) {
                // var: SkinCompat.playerInfoTexture returns Identifier on 26, ResourceLocation pre-26.
                var texture = SkinCompat.playerInfoTexture(info);
                if (texture != null) {
                    return texture;
                }
            }
        }
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(id) && e instanceof PlayerMobEntity pm) {
                    return PlayerMobRenderer.resolveSkin(pm);
                }
            }
        }
        return SkinCompat.defaultTextureFor(id);
    }

    // ---- helpers ----------------------------------------------------------

    /** Truncate a name to fit the relationship row's name column (face … value [-] [+]). */
    private String trim(String name) {
        int maxWidth = BAR_WIDTH - (FACE_SIZE + 3)
            - (2 * REL_BTN_SIZE + REL_BTN_GAP) - this.font.width("10.0") - 4;
        if (this.font.width(name) <= maxWidth) {
            return name;
        }
        String ellipsis = "…";
        String trimmed = name;
        while (!trimmed.isEmpty() && this.font.width(trimmed + ellipsis) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
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
