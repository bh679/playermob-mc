package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link ToolSelector}'s <b>weapon</b> ranking, which is
 * decided purely from item identity ({@code crossbow > bow > sword/axe by tier})
 * and so is fully deterministic under a bare {@link Bootstrap#bootStrap()}.
 *
 * <p>The <b>mining-tool</b> path ({@link ToolSelector#bestToolSlot}) is
 * intentionally <em>not</em> asserted here: it ranks via
 * {@link ItemStack#getDestroySpeed} / {@link ItemStack#isCorrectToolForDrops},
 * which read the block's tool/tag data components — those aren't bound without a
 * loaded datapack, so in a unit test they return defaults and can't be
 * exercised. That path is verified in-game at Gate 2 (like the goals). Only the
 * structural empty-backpack case is checked here.</p>
 */
class ToolSelectorTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SimpleContainer pack(Item... items) {
        SimpleContainer c = new SimpleContainer(Math.max(1, items.length));
        for (int i = 0; i < items.length; i++) c.setItem(i, new ItemStack(items[i]));
        return c;
    }

    // ---- Weapon selection (item-identity ranking; datapack-independent) ----

    @Test
    void swordChosenOverHeldPickaxe() {
        assertEquals(0, ToolSelector.bestWeaponSlot(
            new ItemStack(Items.STONE_PICKAXE), pack(Items.WOODEN_SWORD)),
            "swap the mining pickaxe out for a real weapon");
    }

    @Test
    void crossbowPreferredOverSword() {
        assertEquals(0, ToolSelector.bestWeaponSlot(
            new ItemStack(Items.IRON_SWORD), pack(Items.CROSSBOW)),
            "ranged is preferred when available");
    }

    @Test
    void higherTierSwordWins() {
        assertEquals(0, ToolSelector.bestWeaponSlot(
            new ItemStack(Items.WOODEN_SWORD), pack(Items.DIAMOND_SWORD)));
    }

    @Test
    void keepsMainhandWhenAlreadyBestWeapon() {
        assertEquals(-1, ToolSelector.bestWeaponSlot(
            new ItemStack(Items.DIAMOND_SWORD), pack(Items.WOODEN_SWORD)));
    }

    @Test
    void doesNotGrabAToolAsAWeapon() {
        assertEquals(-1, ToolSelector.bestWeaponSlot(
            ItemStack.EMPTY, pack(Items.STONE_PICKAXE)),
            "a pickaxe isn't worth equipping as a weapon");
    }

    @Test
    void emptyBackpackKeepsMainhandWeapon() {
        assertEquals(-1, ToolSelector.bestWeaponSlot(
            new ItemStack(Items.WOODEN_SWORD), new SimpleContainer(4)));
    }

    // ---- Mining-tool selection (structural only; see class javadoc) --------

    @Test
    void emptyBackpackKeepsMainhandTool() {
        // No candidates → never swaps, regardless of the (datapack-dependent) speed checks.
        assertEquals(-1, ToolSelector.bestToolSlot(
            Blocks.STONE.defaultBlockState(), new ItemStack(Items.STONE_PICKAXE),
            new SimpleContainer(4)));
    }
}
