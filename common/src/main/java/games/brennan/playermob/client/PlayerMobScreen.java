package games.brennan.playermob.client;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.menu.PlayerMobMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 * disposition fields, so values update while the menu is open.</p>
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
    private static final int BAR_WIDTH = 100;
    private static final int FACE_SIZE = 8;
    private static final int ROW_HEIGHT = 12;
    private static final int MAX_RELATIONSHIP_ROWS = 8;
    private static final int LABEL_COLOR = 0x404040;
    private static final int VALUE_COLOR = 0x202020;
    private static final int MUTED_COLOR = 0x808080;
    private static final int DISPOSITION_WIDTH = 112; // width of the disposition column

    // ---- Creative objectives column — right of the disposition panel ----
    private static final int OBJECTIVES_X = INVENTORY_WIDTH + DISPOSITION_WIDTH;
    private static final int OBJECTIVES_GUTTER = 124; // width reserved for the objectives column
    private static final int OBJECTIVES_HEADER_COLOR = 0x404040;
    private static final int OBJECTIVES_TEXT_COLOR = 0x404040;
    private static final int OBJECTIVES_SUB_COLOR = 0x707070;

    /** Names are stable for a session — resolve once per UUID. */
    private final Map<UUID, String> nameCache = new HashMap<>();

    public PlayerMobScreen(PlayerMobMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        // 176 inventory + 112 disposition panel + 124 objectives column.
        this.imageWidth = INVENTORY_WIDTH + DISPOSITION_WIDTH + OBJECTIVES_GUTTER;
        this.imageHeight = 186;
        // Recompute since the field initialiser used the default height.
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawDispositionPanel(guiGraphics);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /**
     * Draws the Creative objectives column to the right of the inventory: the
     * mob's live goal stack ("Objective" then an indented phase), read from the
     * synced {@link PlayerMobEntity#getObjectivesReadout()}. Refreshes each frame
     * as the mob's goals change. Drawn in {@code renderLabels} so it's in the
     * window-local coordinate space (origin at the top-left of the panel).
     */
    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderLabels(guiGraphics, mouseX, mouseY);

        PlayerMobEntity mob = this.menu.getMob();
        if (mob == null) {
            return; // client fallback before the entity resolved — no live state
        }

        int gx = OBJECTIVES_X + 7;
        guiGraphics.drawString(this.font, "Objectives", gx, 6, OBJECTIVES_HEADER_COLOR, false);

        String readout = mob.getObjectivesReadout();
        if (readout == null || readout.isEmpty()) {
            readout = "Idle";
        }

        int y = 20;
        for (String entry : readout.split("\n")) {
            int sep = entry.indexOf(" — ");
            if (sep >= 0) {
                guiGraphics.drawString(this.font, entry.substring(0, sep),
                    gx, y, OBJECTIVES_TEXT_COLOR, false);
                y += this.font.lineHeight + 1;
                guiGraphics.drawString(this.font, "  " + entry.substring(sep + 3),
                    gx, y, OBJECTIVES_SUB_COLOR, false);
                y += this.font.lineHeight + 3;
            } else {
                guiGraphics.drawString(this.font, entry, gx, y, OBJECTIVES_TEXT_COLOR, false);
                y += this.font.lineHeight + 3;
            }
        }
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

        // Bevelled divider between the disposition panel and the objectives column.
        int dividerX = x + OBJECTIVES_X;
        guiGraphics.fill(dividerX - 1, y + 4, dividerX, y + this.imageHeight - 4, PANEL_SHADOW);
        guiGraphics.fill(dividerX, y + 4, dividerX + 1, y + this.imageHeight - 4, PANEL_HILIGHT);
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
        int y = this.topPos + PANEL_TOP;
        PlayerMobEntity mob = this.menu.getMob();
        if (mob == null) {
            g.drawString(this.font, Component.literal("(no data)"), x, y, MUTED_COLOR, false);
            return;
        }

        g.drawString(this.font, Component.literal("Traits"), x, y, LABEL_COLOR, false);
        y += 11;
        y = drawTraitBar(g, x, y, "Fight/Flight", mob.getSyncedFightFlight());
        y = drawTraitBar(g, x, y, "Friendliness", mob.getSyncedFriendliness());
        y += 4;

        g.drawString(this.font, Component.literal("Relationships"), x, y, LABEL_COLOR, false);
        y += 11;

        Map<UUID, Float> feelings = mob.getSyncedFeelings();
        if (feelings.isEmpty()) {
            g.drawString(this.font, Component.literal("none yet"), x, y, MUTED_COLOR, false);
            return;
        }
        // Present (loaded / tab-list) individuals first, then strongest feeling first;
        // absent ones (met but not currently resolvable) sink to the bottom.
        List<Map.Entry<UUID, Float>> rows = new ArrayList<>(feelings.entrySet());
        rows.sort(Comparator
            .comparingInt((Map.Entry<UUID, Float> e) -> isPresent(e.getKey()) ? 0 : 1)
            .thenComparing(Map.Entry::getValue, Comparator.reverseOrder()));

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

    private int drawTraitBar(GuiGraphics g, int x, int y, String label, int value) {
        g.drawString(this.font, Component.literal(label + ": " + value), x, y, VALUE_COLOR, false);
        y += 10;
        g.fill(x, y, x + BAR_WIDTH, y + 3, 0xFF555555);
        int filled = Math.round(BAR_WIDTH * (clamp01(value / 10f)));
        g.fill(x, y, x + filled, y + 3, 0xFF4060C0);
        return y + 3 + 4;
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
     * Whether {@code id} is currently resolvable client-side — a tab-list player or
     * a loaded entity. Drives the roster's "present first, absent at the bottom"
     * sort. Mirrors the resolution {@link #computeName} / {@link #resolveFaceTexture}
     * use, so a row that sorts as "present" also renders a real name and face.
     */
    private boolean isPresent(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null && mc.getConnection().getPlayerInfo(id) != null) {
            return true;
        }
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(id)) {
                    return true;
                }
            }
        }
        return false;
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
