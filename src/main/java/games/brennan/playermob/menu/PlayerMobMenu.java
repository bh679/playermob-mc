package games.brennan.playermob.menu;

import com.mojang.datafixers.util.Pair;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for editing a {@link PlayerMobEntity}'s gear and backpack —
 * the mob equivalent of the player inventory screen, opened by right-clicking
 * a PlayerMob empty-handed in Creative (see {@code PlayerMobEntity.mobInteract}).
 *
 * <p><b>Slot order (must be identical on server and client</b> — vanilla
 * container sync indexes by position):</p>
 * <pre>
 *   0..3   armor: head, chest, legs, feet   (equipment adapter)
 *   4      off-hand                          (equipment adapter)
 *   5      main-hand                         (equipment adapter)
 *   6..13  backpack (8)                      (mob's SimpleContainer)
 *   14..40 player main inventory (27)
 *   41..49 player hotbar (9)
 * </pre>
 *
 * <p>The mob's equipment lives in {@link net.minecraft.world.entity.Mob} slots
 * (no {@link Container} of its own), so {@link EquipmentContainer} adapts the
 * six equipment slots to the {@link Container} interface the menu needs.
 * Backpack edits hit the mob's own {@code SimpleContainer} directly, so they
 * persist through the existing {@code InventoryCarrier} NBT path; equipment
 * edits re-sync to clients via vanilla entity equipment tracking. No custom
 * packets — once opened, vanilla syncs every slot.</p>
 *
 * <p>Vanilla types only — this class is shared by all three loaders and must
 * not touch any loader API.</p>
 */
public class PlayerMobMenu extends AbstractContainerMenu {

    /** Equipment slot order — index in the adapter maps to one of these. */
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND
    };

    private static final int EQUIPMENT_COUNT = 6;
    /** Must match {@code PlayerMobEntity}'s backpack size. */
    private static final int BACKPACK_COUNT = 8;

    /** The mob being edited. Null only on a client fallback (see {@link #fromEntityId}). */
    private final PlayerMobEntity mob;
    private final Container equipment;
    private final Container backpack;

    /**
     * Server-side constructor — binds directly to the live {@code mob}.
     */
    public PlayerMobMenu(int syncId, Inventory playerInv, PlayerMobEntity mob) {
        this(syncId, playerInv, mob, new EquipmentContainer(mob), mob.getInventory());
    }

    private PlayerMobMenu(int syncId, Inventory playerInv, PlayerMobEntity mob,
                          Container equipment, Container backpack) {
        super(PlayerMobRegistry.PLAYER_MOB_MENU, syncId);
        checkContainerSize(equipment, EQUIPMENT_COUNT);
        checkContainerSize(backpack, BACKPACK_COUNT);
        this.mob = mob;
        this.equipment = equipment;
        this.backpack = backpack;

        addEquipmentSlots();
        addBackpackSlots();
        addPlayerInventory(playerInv);
    }

    /**
     * Client/loader factory: resolve the mob from its network-supplied entity
     * id. The menu-open packet can arrive a tick before the entity-track
     * packet, so a missing or wrong entity falls back to same-size empty
     * containers — this method must <b>never</b> return null and must build
     * the exact same slot count as the server, or sync will garble.
     */
    public static PlayerMobMenu fromEntityId(int syncId, Inventory playerInv, int entityId) {
        Entity entity = playerInv.player.level().getEntity(entityId);
        if (entity instanceof PlayerMobEntity resolved) {
            return new PlayerMobMenu(syncId, playerInv, resolved);
        }
        return new PlayerMobMenu(syncId, playerInv, null,
            new SimpleContainer(EQUIPMENT_COUNT), new SimpleContainer(BACKPACK_COUNT));
    }

    /**
     * The mob being edited. {@code null} only on the client fallback path (when
     * {@link #fromEntityId} couldn't resolve the entity yet) — callers that read
     * live state, like the screen's objectives column, must null-check.
     */
    public PlayerMobEntity getMob() {
        return mob;
    }

    // ---- Slot layout ------------------------------------------------------

    private void addEquipmentSlots() {
        // Armor column (x=8) + hands column (x=30), grouped on the left like
        // the player screen. Empty-slot icons come from vanilla sprites.
        addEquipmentSlot(0, 8, 18, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET);     // head
        addEquipmentSlot(1, 8, 36, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE); // chest
        addEquipmentSlot(2, 8, 54, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS);   // legs
        addEquipmentSlot(3, 8, 72, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS);      // feet
        addEquipmentSlot(4, 30, 36, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);    // off-hand
        addEquipmentSlot(5, 30, 18, null);                                     // main-hand
    }

    private void addEquipmentSlot(int index, int x, int y, ResourceLocation emptyIcon) {
        // 1.21.1 has no Slot.setBackground — the empty-slot icon is supplied by
        // overriding getNoItemIcon(), which AbstractContainerScreen blits when
        // the slot is empty. Any item may be placed (Creative editor).
        addSlot(new Slot(equipment, index, x, y) {
            @Override
            public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                return emptyIcon == null
                    ? super.getNoItemIcon()
                    : Pair.of(InventoryMenu.BLOCK_ATLAS, emptyIcon);
            }
        });
    }

    private void addBackpackSlots() {
        // 4×2 grid on the right.
        for (int i = 0; i < BACKPACK_COUNT; i++) {
            int col = i % 4;
            int row = i / 4;
            addSlot(new Slot(backpack, i, 62 + col * 18, 18 + row * 18));
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, 9 + row * 9 + col, 8 + col * 18, 104 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, 8 + col * 18, 162));
        }
    }

    // ---- Menu behaviour ---------------------------------------------------

    @Override
    public boolean stillValid(Player player) {
        // Always non-null server-side (the only side that auto-closes on this).
        return mob != null && mob.isAlive() && mob.distanceToSqr(player) <= 64.0;
    }

    /**
     * Handle the Creative disposition-editor +/- buttons, sent over the vanilla
     * container-button channel ({@code MultiPlayerGameMode.handleInventoryButtonClick}
     * → here on the server). Server-authoritative: only a Creative player editing a
     * live mob may change disposition. Ids {@code 0..3} map to trait adjustments
     * ({@link games.brennan.playermob.entity.TraitEditButtons}); higher ids map to
     * per-relationship feeling adjustments
     * ({@link games.brennan.playermob.entity.FeelingEditButtons}). All clamped; no
     * custom packets.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (mob == null || !player.isCreative()) {
            return false;
        }
        return mob.applyTraitEditButton(id) || mob.applyFeelingEditButton(id);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();

        int mobEnd = EQUIPMENT_COUNT + BACKPACK_COUNT; // 14
        int playerEnd = this.slots.size();             // 50

        if (index < mobEnd) {
            // From the mob's slots → player inventory.
            if (!this.moveItemStackTo(stack, mobEnd, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // From player inventory → backpack storage (equipment is drag-only,
            // so shift-click can't accidentally route a sword into the head slot).
            if (!this.moveItemStackTo(stack, EQUIPMENT_COUNT, mobEnd, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    // ---- Equipment adapter ------------------------------------------------

    /**
     * Adapts a {@link PlayerMobEntity}'s six equipment slots to a
     * {@link Container} so the menu can hold standard {@link Slot}s over them.
     * Reads/writes go straight through {@code getItemBySlot}/{@code setItemSlot};
     * vanilla entity tracking syncs equipment changes to clients on its own.
     */
    private static final class EquipmentContainer implements Container {

        private final PlayerMobEntity mob;

        EquipmentContainer(PlayerMobEntity mob) {
            this.mob = mob;
        }

        @Override
        public int getContainerSize() {
            return EQUIPMENT_SLOTS.length;
        }

        @Override
        public boolean isEmpty() {
            for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
                if (!mob.getItemBySlot(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return valid(index) ? mob.getItemBySlot(EQUIPMENT_SLOTS[index]) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int index, int amount) {
            if (!valid(index) || amount <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack current = mob.getItemBySlot(EQUIPMENT_SLOTS[index]);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack split = current.split(amount);
            // Re-set so the entity re-evaluates equipment (attribute modifiers).
            mob.setItemSlot(EQUIPMENT_SLOTS[index], current);
            return split;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            if (!valid(index)) {
                return ItemStack.EMPTY;
            }
            ItemStack current = mob.getItemBySlot(EQUIPMENT_SLOTS[index]);
            mob.setItemSlot(EQUIPMENT_SLOTS[index], ItemStack.EMPTY);
            return current;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            if (valid(index)) {
                mob.setItemSlot(EQUIPMENT_SLOTS[index], stack);
            }
        }

        @Override
        public void setChanged() {
            // No-op: equipment changes are detected + synced by vanilla entity
            // tracking each tick.
        }

        @Override
        public boolean stillValid(Player player) {
            return mob.isAlive();
        }

        @Override
        public void clearContent() {
            for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
                mob.setItemSlot(slot, ItemStack.EMPTY);
            }
        }

        private boolean valid(int index) {
            return index >= 0 && index < EQUIPMENT_SLOTS.length;
        }
    }
}
