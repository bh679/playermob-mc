package games.brennan.playermob.player;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle for {@link DraftBooks}: what counts as a draft (an unsigned book &amp; quill with writing),
 * and that the {@code lives.dat} / entity-tag list form round-trips. The item halves need the
 * bootstrapped registries (same dance as {@code ItemPickupPolicyTest}); {@link DraftBooks#hasWriting}
 * is pure.
 */
class DraftBooksTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- hasWriting (pure) ----

    @Test
    void hasWritingNeedsOneNonBlankPage() {
        assertFalse(DraftBooks.hasWriting(List.of()), "no pages");
        assertFalse(DraftBooks.hasWriting(List.of("", "   ", "\n")), "only blank pages");
        assertTrue(DraftBooks.hasWriting(List.of("", "Dear diary")), "one written page is enough");
    }

    @Test
    void hasWritingToleratesNullPages() {
        List<String> pages = new java.util.ArrayList<>();
        pages.add(null);
        assertFalse(DraftBooks.hasWriting(pages));
        pages.add("x");
        assertTrue(DraftBooks.hasWriting(pages));
    }

    // ---- isDraft (items) ----

    //? if >=1.21.1 {
    private static ItemStack quill(String... pages) {
        ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
        stack.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
            new net.minecraft.world.item.component.WritableBookContent(
                java.util.Arrays.stream(pages).map(net.minecraft.server.network.Filterable::passThrough).toList()));
        return stack;
    }

    private static ItemStack signed(String... pages) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
            new net.minecraft.world.item.component.WrittenBookContent(
                net.minecraft.server.network.Filterable.passThrough("Title"), "Author", 0,
                java.util.Arrays.stream(pages)
                    .map(p -> net.minecraft.server.network.Filterable.passThrough(
                        (net.minecraft.network.chat.Component) net.minecraft.network.chat.Component.literal(p)))
                    .toList(),
                false));
        return stack;
    }
    //?} else {
    /*private static ItemStack quill(String... pages) {
        ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
        ListTag list = new ListTag();
        for (String p : pages) {
            list.add(net.minecraft.nbt.StringTag.valueOf(p));
        }
        stack.getOrCreateTag().put("pages", list);
        return stack;
    }

    private static ItemStack signed(String... pages) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        ListTag list = new ListTag();
        for (String p : pages) {
            list.add(net.minecraft.nbt.StringTag.valueOf(p));
        }
        stack.getOrCreateTag().put("pages", list);
        stack.getOrCreateTag().putString("title", "Title");
        stack.getOrCreateTag().putString("author", "Author");
        return stack;
    }*/
    //?}

    @Test
    void aWrittenQuillIsADraft() {
        assertTrue(DraftBooks.isDraft(quill("Chapter one")));
        assertTrue(DraftBooks.isDraft(quill("", "page two has text")), "a later page counts");
    }

    @Test
    void aBlankQuillIsNotADraft() {
        assertFalse(DraftBooks.isDraft(new ItemStack(Items.WRITABLE_BOOK)), "fresh book & quill");
        assertFalse(DraftBooks.isDraft(quill()), "component with no pages");
        assertFalse(DraftBooks.isDraft(quill("", "  ")), "only blank pages");
    }

    @Test
    void aSignedBookIsNotADraft() {
        assertFalse(DraftBooks.isDraft(signed("finished")));
    }

    @Test
    void otherItemsAreNotDrafts() {
        assertFalse(DraftBooks.isDraft(ItemStack.EMPTY));
        assertFalse(DraftBooks.isDraft(new ItemStack(Items.BOOK)));
        assertFalse(DraftBooks.isDraft(new ItemStack(Items.PAPER)));
    }

    // ---- list <-> compounds (the lives.dat form) ----

    @Test
    void compoundListRoundTrips() {
        ListTag list = new ListTag();
        net.minecraft.nbt.CompoundTag a = new net.minecraft.nbt.CompoundTag();
        a.putString("id", "minecraft:writable_book");
        list.add(a);
        List<net.minecraft.nbt.CompoundTag> compounds = DraftBooks.toCompounds(list);
        assertEquals(1, compounds.size());
        assertEquals(list, DraftBooks.toListTag(compounds));
    }
}
