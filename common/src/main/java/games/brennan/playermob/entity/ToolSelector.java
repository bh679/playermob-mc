package games.brennan.playermob.entity;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Picks the most appropriate tool/weapon for a job out of what the mob is holding
 * plus its backpack. Pure and static — unit-tested in {@code ToolSelectorTest}.
 *
 * <p><b>Mining</b> ranks a correct-for-drops tool ahead of an incorrect one, then
 * by {@link ItemStack#getDestroySpeed(BlockState)} — so an axe wins for logs and a
 * correct-tier pickaxe wins for stone/ore, automatically picking the best tier.</p>
 *
 * <p><b>Combat</b> uses a fixed weapon priority: crossbow &gt; bow &gt; sword/axe
 * by tier. Ranged is preferred to keep the pillager-style identity (a freshly
 * crafted mob only has melee, so it picks its best sword/axe). Non-weapons (a
 * pickaxe in hand from mining, fists) rank lowest, so the mob never swaps a tool
 * in just to fight.</p>
 *
 * <p>Both methods return a <em>backpack slot index</em> to swap into the mainhand,
 * or {@code -1} when the mainhand is already the best choice (no swap needed).</p>
 */
public final class ToolSelector {

    private ToolSelector() {}

    /** Melee weapons, best → worst; earlier = preferred. Sword edges out axe at each tier. */
    private static final List<Item> MELEE_PRIORITY = List.of(
        Items.NETHERITE_SWORD, Items.NETHERITE_AXE,
        Items.DIAMOND_SWORD, Items.DIAMOND_AXE,
        Items.IRON_SWORD, Items.IRON_AXE,
        Items.STONE_SWORD, Items.STONE_AXE,
        Items.GOLDEN_SWORD, Items.GOLDEN_AXE,
        Items.WOODEN_SWORD, Items.WOODEN_AXE);

    /**
     * Backpack slot of the best mining tool for {@code state}, or {@code -1} if
     * {@code mainhand} is already best.
     */
    public static int bestToolSlot(BlockState state, ItemStack mainhand, Container backpack) {
        boolean needsTool = state.requiresCorrectToolForDrops();
        ItemStack best = mainhand;
        int bestSlot = -1;
        for (int i = 0; i < backpack.getContainerSize(); i++) {
            ItemStack candidate = backpack.getItem(i);
            if (candidate.isEmpty()) continue;
            if (isBetterTool(candidate, best, state, needsTool)) {
                best = candidate;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static boolean isBetterTool(ItemStack candidate, ItemStack current,
                                        BlockState state, boolean needsTool) {
        boolean candidateCorrect = !needsTool || candidate.isCorrectToolForDrops(state);
        boolean currentCorrect = !needsTool
            || (!current.isEmpty() && current.isCorrectToolForDrops(state));
        if (candidateCorrect != currentCorrect) return candidateCorrect; // correct beats incorrect
        float candidateSpeed = candidate.getDestroySpeed(state);
        float currentSpeed = current.isEmpty() ? 1.0f : current.getDestroySpeed(state);
        return candidateSpeed > currentSpeed; // among equals, the faster tool
    }

    /**
     * Backpack slot of the best weapon, or {@code -1} if {@code mainhand} is
     * already best (or nothing better than what's in hand exists).
     */
    public static int bestWeaponSlot(ItemStack mainhand, Container backpack) {
        int bestRank = weaponRank(mainhand);
        int bestSlot = -1;
        for (int i = 0; i < backpack.getContainerSize(); i++) {
            ItemStack candidate = backpack.getItem(i);
            if (candidate.isEmpty()) continue;
            int rank = weaponRank(candidate);
            if (rank > bestRank) {
                bestRank = rank;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static int weaponRank(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.is(Items.CROSSBOW)) return 10_000;
        if (stack.is(Items.BOW)) return 9_000;
        int idx = MELEE_PRIORITY.indexOf(stack.getItem());
        if (idx >= 0) return 1_000 + (MELEE_PRIORITY.size() - idx);
        return 0; // not a weapon — don't swap a tool/fist in just to fight
    }
}
