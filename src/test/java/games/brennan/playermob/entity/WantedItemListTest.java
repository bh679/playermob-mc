package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link WantedItemList} — the config-driven extra-pickup parser/matcher.
 *
 * <p>Same {@link Bootstrap} dance as {@code ItemPickupPolicyTest}: vanilla item registries must
 * be bootstrapped before any {@link Items} reference or {@code matches} by id (which reads the
 * item's registry key). Runtime <b>tag</b> matching (e.g. {@code #scguns:ammo}) can't be
 * asserted here — datapack tags don't bind in Bootstrap (mirrors the {@code COMMON_WOODS} /
 * {@code ItemTags.LOGS} fallback in {@link ItemPickupPolicy}) — so it's verified in-game at
 * Gate 2; here we only assert tag <em>parsing</em> structurally.</p>
 */
class WantedItemListTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void blankInputIsEmpty() {
        assertTrue(WantedItemList.parse(null).isEmpty());
        assertTrue(WantedItemList.parse("").isEmpty());
        assertTrue(WantedItemList.parse("   ").isEmpty());
        assertTrue(WantedItemList.EMPTY.isEmpty());
    }

    @Test
    void parsesIdsAndTagsAcrossSeparators() {
        // Commas, spaces, and mixed runs all separate; a bare path defaults to the minecraft namespace.
        WantedItemList list = WantedItemList.parse("minecraft:diamond, stick  #minecraft:logs,#c:ingots");
        assertEquals(2, list.itemIds().size(), "diamond + stick");
        assertTrue(list.itemIds().contains("minecraft:diamond"));
        assertTrue(list.itemIds().contains("minecraft:stick"), "bare path defaults to minecraft namespace");
        assertEquals(2, list.tags().size(), "#minecraft:logs + #c:ingots");
        assertFalse(list.isEmpty());
    }

    @Test
    void skipsMalformedEntriesButKeepsTheRest() {
        // Empty namespace, empty path, and an illegal uppercase char are dropped; the good id survives.
        WantedItemList list = WantedItemList.parse("minecraft:diamond, :nope, bad:, scguns:Bad_Caps");
        assertEquals(1, list.itemIds().size());
        assertTrue(list.itemIds().contains("minecraft:diamond"));
        assertTrue(list.tags().isEmpty());
    }

    @Test
    void allJunkCollapsesToEmpty() {
        assertTrue(WantedItemList.parse(": , bad: , :bad").isEmpty());
    }

    @Test
    void matchesByItemId() {
        WantedItemList list = WantedItemList.parse("minecraft:stick");
        assertTrue(list.matches(new ItemStack(Items.STICK)), "listed id matches");
        assertFalse(list.matches(new ItemStack(Items.DIAMOND)), "unlisted id does not match");
        assertFalse(list.matches(ItemStack.EMPTY), "empty stack never matches");
    }

    @Test
    void emptyListMatchesNothing() {
        assertFalse(WantedItemList.EMPTY.matches(new ItemStack(Items.STICK)));
    }

    @Test
    void modNamespacedIdParsesAndStoresCanonicalKey() {
        // A modded id (no such item loaded in test) still parses + stores its canonical key string,
        // so it would match the real item at runtime even though no stack here carries it.
        WantedItemList list = WantedItemList.parse("scguns:assault_rifle");
        assertTrue(list.itemIds().contains("scguns:assault_rifle"));
        assertFalse(list.matches(new ItemStack(Items.STICK)), "doesn't match an unrelated item");
    }
}
