package games.brennan.playermob.entity;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The book a PlayerMob holds while it reads (see {@code ReadBookGoal}), and the offhand item it
 * set aside to hold it — owned by the entity, not the goal, so every way a read can end (finish,
 * interruption, death, a player opening the mob's inventory) restores the hand the same way, and a
 * save mid-read records the mob as it would be without the book ({@link #presentForSave}).
 *
 * <p>Two kinds of book:</p>
 * <ul>
 *   <li>A <b>prop</b> — a plain bookshelf's book, or a copy of a lectern's book. It exists only in
 *       the mob's hand: ending the read deletes it, so reading can never mint items.</li>
 *   <li>A <b>real</b> book taken out of a chiseled bookshelf. Ending the read hands it back to the
 *       caller, which puts it back on the shelf or drops it.</li>
 * </ul>
 */
public final class ReadingHands {

    private boolean active;
    private boolean prop;
    /** The exact stack placed in the offhand — compared by identity to spot interference. */
    private ItemStack held = ItemStack.EMPTY;
    /** What the offhand held before the book. */
    private ItemStack stash = ItemStack.EMPTY;

    public boolean isActive() {
        return active;
    }

    /** Whether the book in hand is a prop (never leaves the mob's hand). */
    public boolean isProp() {
        return active && prop;
    }

    /** Put {@code book} in the mob's offhand, setting the current offhand item aside. */
    public void begin(PlayerMobEntity mob, ItemStack book, boolean isProp) {
        if (active) {
            end(mob);
        }
        this.stash = mob.getItemBySlot(EquipmentSlot.OFFHAND).copy();
        this.held = book;
        this.prop = isProp;
        this.active = true;
        mob.setItemSlot(EquipmentSlot.OFFHAND, book);
    }

    /** True while the mob still holds the very book {@link #begin} gave it. */
    public boolean stillHeld(PlayerMobEntity mob) {
        return active && mob.getItemBySlot(EquipmentSlot.OFFHAND) == held;
    }

    /**
     * End the read: restore the set-aside offhand item and return the real book that was in hand,
     * or {@link ItemStack#EMPTY} for a prop (deleted) or when nothing was active. If something else
     * took the offhand meanwhile, that item stays and the set-aside one goes to the backpack.
     */
    public ItemStack end(PlayerMobEntity mob) {
        if (!active) {
            return ItemStack.EMPTY;
        }
        active = false;
        ItemStack inHand = mob.getItemBySlot(EquipmentSlot.OFFHAND);
        ItemStack real = ItemStack.EMPTY;
        ItemStack restore = stash;
        stash = ItemStack.EMPTY;
        if (inHand == held) {
            real = prop ? ItemStack.EMPTY : held;
            mob.setItemSlot(EquipmentSlot.OFFHAND, restore);
        } else if (inHand.isEmpty()) {
            mob.setItemSlot(EquipmentSlot.OFFHAND, restore);
        } else if (!restore.isEmpty()) {
            stow(mob, restore);
        }
        held = ItemStack.EMPTY;
        return real;
    }

    /**
     * Finish the read: restore the set-aside offhand item and hand back the book that was being
     * read — prop or real — for the caller to drop and burn. {@link ItemStack#EMPTY} when the book
     * is no longer in hand (something took it) or nothing was active.
     */
    public ItemStack finish(PlayerMobEntity mob) {
        if (!stillHeld(mob)) {
            end(mob);
            return ItemStack.EMPTY;
        }
        ItemStack book = held;
        boolean wasProp = prop;
        prop = false; // so end() hands the book back instead of deleting it
        ItemStack out = end(mob);
        return wasProp ? book : out;
    }

    /**
     * End the read wherever the mob is — death, a save, an opened inventory: a real book goes into
     * the backpack (the goal only takes one when there is room), or, failing that, to the ground.
     */
    public void settle(PlayerMobEntity mob) {
        ItemStack real = end(mob);
        if (!real.isEmpty()) {
            stow(mob, real);
        }
    }

    /**
     * Make the mob look, to a save, as if it weren't reading — without ending the read. Anything
     * that serialises the entity (an autosave, a chunk unloading, {@code /data get}, a mod
     * snapshotting it) must not interrupt a book or move it, so instead of {@link #settle} the save
     * sees the set-aside offhand item back in hand and a real book in a free backpack slot — where
     * it would belong if the mob were unloaded mid-read. Run the returned action right after the
     * save to put everything back exactly as it was.
     */
    public Runnable presentForSave(PlayerMobEntity mob) {
        if (!stillHeld(mob)) {
            return () -> { };
        }
        int slot = -1;
        if (!prop) {
            for (int i = 0; i < mob.getInventory().getContainerSize(); i++) {
                if (mob.getInventory().getItem(i).isEmpty()) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                // No room to record the real book anywhere else: save it in hand, as it is.
                return () -> { };
            }
            mob.getInventory().setItem(slot, held);
        }
        ItemStack book = held;
        int bookSlot = slot;
        mob.setItemSlot(EquipmentSlot.OFFHAND, stash);
        return () -> {
            if (bookSlot >= 0 && mob.getInventory().getItem(bookSlot) == book) {
                mob.getInventory().setItem(bookSlot, ItemStack.EMPTY);
            }
            mob.setItemSlot(EquipmentSlot.OFFHAND, book);
        };
    }

    private static void stow(PlayerMobEntity mob, ItemStack stack) {
        ItemStack left = mob.getInventory().addItem(stack);
        if (!left.isEmpty()) {
            mob.dropAtLocation(left);
        }
    }
}
