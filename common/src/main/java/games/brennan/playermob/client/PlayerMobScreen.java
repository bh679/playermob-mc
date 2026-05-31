package games.brennan.playermob.client;

import games.brennan.playermob.menu.PlayerMobMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Client screen for {@link PlayerMobMenu}. Draws a vanilla-styled window
 * <b>programmatically</b> (no texture asset shipped): a raised grey panel plus
 * a recessed cell behind every slot — including the player inventory — so the
 * whole thing reads like a normal Minecraft container. Armor/off-hand empty
 * icons are supplied by the menu's slot backgrounds.
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

    public PlayerMobScreen(PlayerMobMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 186;
        // Recompute since the field initialiser used the default height.
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
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
}
