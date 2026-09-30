package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WritableBookContent;
//?}

import java.util.ArrayList;
import java.util.List;

/**
 * "Draft" books — an unsigned book &amp; quill with writing in it — and how they ride a
 * player's death into their <em>local</em> echo so the author can keep writing.
 *
 * <p>A draft is a {@code WRITABLE_BOOK} whose content has at least one page with non-blank text.
 * A blank book &amp; quill is not a draft (nothing to lose), and a signed written book is not a
 * draft either (it is finished). {@link #collect} pulls every draft off a dying player, in slot
 * order across the main inventory + hotbar and the offhand; the caller stores them beside the
 * reincarnation snapshot (never inside it, so a relay never ships them — see
 * {@code GlobalLifeStore.DeathRecord#drafts}) and hands them to the echo's shelf
 * ({@code PlayerMobEntity#addDraftBooks}) when a local echo of that life spawns.</p>
 *
 * <p>The predicate half ({@link #hasWriting}) is pure and unit-tested; the item halves need the
 * bootstrapped registries. Serialisation uses vanilla's own {@link ItemStack} NBT form so the same
 * {@link ListTag} is valid in {@code lives.dat} and in the entity's save tag.</p>
 */
public final class DraftBooks {

    private DraftBooks() {}

    /** True when at least one page carries non-blank text. Pure; the "has writing" half of {@link #isDraft}. */
    public static boolean hasWriting(List<String> pages) {
        for (String page : pages) {
            if (page != null && !page.isBlank()) {
                return true;
            }
        }
        return false;
    }

    //? if >=1.21.1 {
    /** The raw text of every page of a writable-book component — unit-test seam for {@link #hasWriting}. */
    public static List<String> rawPages(WritableBookContent content) {
        List<String> out = new ArrayList<>(content.pages().size());
        for (Filterable<String> page : content.pages()) {
            out.add(page.raw());
        }
        return out;
    }
    //?}

    /** True for an unsigned book &amp; quill that has writing on at least one page. */
    public static boolean isDraft(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WRITABLE_BOOK)) {
            return false;
        }
        //? if >=1.21.1 {
        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        return content != null && hasWriting(rawPages(content));
        //?} else {
        /*CompoundTag tag = stack.getTag();
        if (tag == null || !NbtCompat.containsOfType(tag, "pages", Tag.TAG_LIST)) {
            return false;
        }
        ListTag pages = tag.getList("pages", Tag.TAG_STRING);
        List<String> raw = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            raw.add(pages.getString(i));
        }
        return hasWriting(raw);*/
        //?}
    }

    /**
     * Copies of every draft {@code player} is carrying — main inventory + hotbar in slot order (the
     * held slot included), then the offhand. Armour slots can't hold a book. Never empty stacks.
     */
    public static List<ItemStack> collect(Player player) {
        Inventory inv = player.getInventory();
        List<ItemStack> out = new ArrayList<>();
        //? if >=26 {
        /*net.minecraft.core.NonNullList<ItemStack> items = inv.getNonEquipmentItems();
        *///?} else {
        net.minecraft.core.NonNullList<ItemStack> items = inv.items;
        //?}
        for (ItemStack stack : items) {
            if (isDraft(stack)) {
                out.add(stack.copy());
            }
        }
        //? if >=26 {
        /*for (ItemStack stack : List.of(player.getOffhandItem())) {
        *///?} else {
        for (ItemStack stack : inv.offhand) {
        //?}
            if (isDraft(stack)) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /**
     * Serialise {@code stacks} as a list of vanilla item-stack compounds. {@code context} supplies
     * the registry lookup item components need on 1.21.1+ (any entity of that world; ignored on
     * 1.20.1). Empty stacks are skipped.
     */
    public static ListTag save(List<ItemStack> stacks, Entity context) {
        ListTag out = new ListTag();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            //? if >=1.21.1 {
            out.add(stack.save(context.registryAccess()));
            //?} else {
            /*out.add(stack.save(new CompoundTag()));*/
            //?}
        }
        return out;
    }

    /** Inverse of {@link #save}: a stack that fails to parse (unknown item) is dropped, never a crash. */
    public static List<ItemStack> load(ListTag list, Entity context) {
        List<ItemStack> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = NbtCompat.compoundAt(list, i);
            //? if >=1.21.1 {
            ItemStack stack = ItemStack.parse(context.registryAccess(), entry).orElse(ItemStack.EMPTY);
            //?} else {
            /*ItemStack stack = ItemStack.of(entry);*/
            //?}
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    /** The compounds of {@code list} as a Java list — the {@code lives.dat} form ({@code List<CompoundTag>}). */
    public static List<CompoundTag> toCompounds(ListTag list) {
        List<CompoundTag> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(NbtCompat.compoundAt(list, i));
        }
        return out;
    }

    /** Inverse of {@link #toCompounds}. */
    public static ListTag toListTag(List<CompoundTag> compounds) {
        ListTag out = new ListTag();
        for (CompoundTag tag : compounds) {
            out.add(tag.copy());
        }
        return out;
    }
}
