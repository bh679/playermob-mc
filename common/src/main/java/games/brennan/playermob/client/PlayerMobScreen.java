package games.brennan.playermob.client;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.TraitEditButtons;
import games.brennan.playermob.menu.PlayerMobMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client screen for {@link PlayerMobMenu}. Draws a vanilla-styled window
 * <b>programmatically</b> (no texture asset shipped): a raised grey panel plus
 * a recessed cell behind every slot — including the player inventory — so the
 * whole thing reads like a normal Minecraft container. Armor/off-hand empty
 * icons are supplied by the menu's slot backgrounds.
 *
 * <p>A right-hand <b>disposition panel</b> shows the mob's two personal traits
 * (Fight/Flight, Friendliness) and a <b>Relationships</b> list — one row per
 * individual the mob has a feeling toward, each with that target's face, name,
 * and feeling (0–10, hate→love). All read live from the entity's synced
 * disposition fields, so values update while the menu is open. Each trait has
 * {@code [-]}/{@code [+]} buttons that edit it in Creative over the vanilla
 * container-button channel (see {@link PlayerMobMenu#clickMenuButton}); the
 * server clamps and re-syncs, so the panel reflects the edit next frame.</p>
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

    // Disposition panel layout (relative to the window origin).
    private static final int INVENTORY_WIDTH = 176;   // the original window's content width
    private static final int PANEL_X = INVENTORY_WIDTH + 4;
    private static final int PANEL_TOP = 8;
    private static final int BAR_WIDTH = 108;
    private static final int FACE_SIZE = 8;
    private static final int ROW_HEIGHT = 12;
    private static final int MAX_RELATIONSHIP_ROWS = 8;
    private static final int LABEL_COLOR = 0x404040;
    private static final int VALUE_COLOR = 0x202020;
    private static final int MUTED_COLOR = 0x808080;

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

    /** Names are stable for a session — resolve once per UUID. */
    private final Map<UUID, String> nameCache = new HashMap<>();

    public PlayerMobScreen(PlayerMobMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 300; // 176 inventory + ~124 disposition panel (incl. edit buttons)
        this.imageHeight = 186;
        // Recompute since the field initialiser used the default height.
        this.inventoryLabelY = this.imageHeight - 94;
    }

    /**
     * Add the Creative trait-edit buttons. Runs after {@code super.init()} has
     * set {@code leftPos}/{@code topPos}, so button bounds resolve to absolute
     * screen coordinates. Skipped on the client fallback (no resolved mob — the
     * panel renders "(no data)" instead).
     */
    @Override
    protected void init() {
        super.init();
        if (this.menu.getMob() == null) {
            return;
        }
        int top = this.topPos + PANEL_TOP;
        addTraitButtons(top + FF_LABEL_DY, TraitEditButtons.FIGHT_FLIGHT_DOWN, TraitEditButtons.FIGHT_FLIGHT_UP);
        addTraitButtons(top + FRIEND_LABEL_DY, TraitEditButtons.FRIENDLINESS_DOWN, TraitEditButtons.FRIENDLINESS_UP);
    }

    /** A {@code [-] [+]} button pair for one trait, on its label line. */
    private void addTraitButtons(int labelY, int downId, int upId) {
        int y = labelY - 2; // centre the 12px button on the ~8px label text
        addRenderableWidget(Button.builder(Component.literal("-"), b -> sendButton(downId))
            .bounds(minusX(), y, BUTTON_SIZE, BUTTON_SIZE).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> sendButton(upId))
            .bounds(plusX(), y, BUTTON_SIZE, BUTTON_SIZE).build());
    }

    /** Send a trait edit over the vanilla container-button channel; the server clamps + re-syncs. */
    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    // Edit-cluster geometry (right-aligned to the bar). Depends on leftPos, set by init().
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

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawDispositionPanel(guiGraphics);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
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
    }

    /** Draws an 18×18 recessed cell whose 16×16 interior sits at ({@code sx},{@code sy}). */
    private void drawSlotRecess(GuiGraphics g, int sx, int sy) {
        int x0 = sx - 1;
        int y0 = sy - 1;
        g.fill(x0, y0, x0 + 18, y0 + 18, SLOT_BG);
        g.fill(x0, y0, x0 + 18, y0 + 1, SLOT_SHADOW);          // top
        g.fill(x0, y0, x0 + 1, y0 + 18, SLOT_SHADOW);          // left
        g.fill(x0, y0 + 17, x0 + 18, y0 + 18, SLOT_HILIGHT);   // bottom
        g.fill(x0 + 17, y0, x0 + 18, y0 + 18, SLOT_HILIGHT);   // right
    }

    // ---- Disposition panel ------------------------------------------------

    private void drawDispositionPanel(GuiGraphics g) {
        int x = this.leftPos + PANEL_X;
        int top = this.topPos + PANEL_TOP;
        PlayerMobEntity mob = this.menu.getMob();
        if (mob == null) {
            g.drawString(this.font, Component.literal("(no data)"), x, top, MUTED_COLOR, false);
            return;
        }

        g.drawString(this.font, Component.literal("Traits"), x, top + TRAITS_HEADER_DY, LABEL_COLOR, false);
        drawTrait(g, x, top + FF_LABEL_DY, top + FF_BAR_DY, "Fight/Flight", mob.getSyncedFightFlight());
        drawTrait(g, x, top + FRIEND_LABEL_DY, top + FRIEND_BAR_DY, "Friendliness", mob.getSyncedFriendliness());

        g.drawString(this.font, Component.literal("Relationships"), x, top + REL_HEADER_DY, LABEL_COLOR, false);
        int y = top + REL_ROWS_DY;

        Map<UUID, Float> feelings = mob.getSyncedFeelings();
        if (feelings.isEmpty()) {
            g.drawString(this.font, Component.literal("none yet"), x, y, MUTED_COLOR, false);
            return;
        }
        List<Map.Entry<UUID, Float>> rows = new ArrayList<>(feelings.entrySet());
        rows.sort(Comparator.comparingDouble((Map.Entry<UUID, Float> e) -> e.getValue()).reversed());

        int shown = Math.min(MAX_RELATIONSHIP_ROWS, rows.size());
        for (int i = 0; i < shown; i++) {
            drawRelationshipRow(g, x, y, rows.get(i).getKey(), rows.get(i).getValue());
            y += ROW_HEIGHT;
        }
        if (rows.size() > shown) {
            g.drawString(this.font, Component.literal("+" + (rows.size() - shown) + " more"),
                x, y, MUTED_COLOR, false);
        }
    }

    /**
     * One trait row: the label on the left, the live value centred between its
     * {@code [-]}/{@code [+]} edit buttons (those are widgets added in
     * {@link #init()}), and a 0–10 fill bar below.
     */
    private void drawTrait(GuiGraphics g, int x, int labelY, int barY, String label, int value) {
        g.drawString(this.font, Component.literal(label), x, labelY, VALUE_COLOR, false);
        String text = String.valueOf(value);
        g.drawString(this.font, Component.literal(text),
            valueCenterX() - this.font.width(text) / 2, labelY, VALUE_COLOR, false);
        g.fill(x, barY, x + BAR_WIDTH, barY + 3, 0xFF555555);
        int filled = Math.round(BAR_WIDTH * clamp01(value / 10f));
        g.fill(x, barY, x + filled, barY + 3, 0xFF4060C0);
    }

    private void drawRelationshipRow(GuiGraphics g, int x, int y, UUID id, float feeling) {
        PlayerFaceRenderer.draw(g, resolveFaceTexture(id), x, y, FACE_SIZE, true, false);
        String name = nameCache.computeIfAbsent(id, this::computeName);
        g.drawString(this.font, Component.literal(trim(name)), x + FACE_SIZE + 3, y, VALUE_COLOR, false);
        String value = String.valueOf(Math.round(feeling));
        int vx = x + BAR_WIDTH - this.font.width(value);
        g.drawString(this.font, Component.literal(value), vx, y, feelingColor(feeling), false);
    }

    // ---- Identity / face resolution (client-side) -------------------------

    private String computeName(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) {
                return info.getProfile().getName();
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
    private ResourceLocation resolveFaceTexture(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) {
                ResourceLocation texture = info.getSkin().texture();
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
        return DefaultPlayerSkin.get(id).texture();
    }

    // ---- helpers ----------------------------------------------------------

    /** Truncate a name to fit the relationship row's name column. */
    private String trim(String name) {
        int maxWidth = BAR_WIDTH - (FACE_SIZE + 3) - this.font.width("10") - 2;
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
