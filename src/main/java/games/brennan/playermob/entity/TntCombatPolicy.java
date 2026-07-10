package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic for {@link games.brennan.playermob.entity.goal.TntCombatGoal} — kept out of the
 * goal so it can be unit-tested without a live world (mirrors {@link DispositionResolver} / {@code MiningMath}).
 *
 * <p>Answers three questions the goal needs each cycle:</p>
 * <ol>
 *   <li>Which item in the backpack is the best <b>igniter</b> for TNT, and in what priority
 *       ({@link #selectIgniterSlot})?</li>
 *   <li>How is a given igniter <b>used</b> to light TNT ({@link #classify} → {@link IgnitionStrategy})?</li>
 *   <li>Where should the TNT block be <b>placed</b> relative to the mob and its target
 *       ({@link #placementCandidates})?</li>
 * </ol>
 *
 * <p>Igniters are recognised by item identity and {@code instanceof} on the placed block — deliberately
 * <b>not</b> by datapack tags, which don't bind under the test {@code Bootstrap} (see the
 * no-tag-binding rule). This keeps the classifier testable and datapack-independent, and naturally
 * covers every modded button / lever / pressure-plate that extends the vanilla block classes.</p>
 */
public final class TntCombatPolicy {

    private TntCombatPolicy() {}

    /** How a PlayerMob lights placed TNT with a particular igniter. */
    public enum IgnitionStrategy {
        /** Right-click the TNT block with the held item (flint &amp; steel, fire charge) — vanilla primes + consumes it. */
        RIGHT_CLICK,
        /** Place the actual component on top of the TNT in its powered/pressed state (redstone block, lever,
         *  button, pressure plate) so a real redstone signal primes the TNT below — placed and used. */
        PLACE_PRIME
    }

    /**
     * The ignition strategy for {@code stack}, or {@code null} if it can't light TNT. Order of the checks
     * doesn't matter here (each item maps to exactly one strategy); the <em>preference</em> order between
     * different igniters lives in {@link #igniterRank}.
     */
    public static IgnitionStrategy classify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE) {
            return IgnitionStrategy.RIGHT_CLICK;
        }
        if (item == Items.REDSTONE_BLOCK) {
            return IgnitionStrategy.PLACE_PRIME;
        }
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof LeverBlock || block instanceof ButtonBlock || block instanceof BasePressurePlateBlock) {
                return IgnitionStrategy.PLACE_PRIME;
            }
        }
        return null;
    }

    /** True when {@code stack} is any TNT-lighting item. */
    public static boolean isIgniter(ItemStack stack) {
        return classify(stack) != null;
    }

    /**
     * Preference rank of an igniter (lower = preferred): flint&amp;steel &lt; fire charge &lt; redstone block
     * &lt; lever &lt; button &lt; pressure plate. The deterministic, no-residue lighters rank first; the
     * trap-laying pressure plate ranks last. Returns {@code -1} for a non-igniter.
     */
    static int igniterRank(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        Item item = stack.getItem();
        if (item == Items.FLINT_AND_STEEL) return 0;
        if (item == Items.FIRE_CHARGE) return 1;
        if (item == Items.REDSTONE_BLOCK) return 2;
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof LeverBlock) return 3;
            if (block instanceof ButtonBlock) return 4;
            if (block instanceof BasePressurePlateBlock) return 5;
        }
        return -1;
    }

    /**
     * The slot of the most-preferred igniter in {@code container}, or {@code -1} if it holds none.
     * Ties (same rank in two slots) resolve to the lower slot index.
     */
    public static int selectIgniterSlot(Container container) {
        int bestSlot = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < container.getContainerSize(); i++) {
            int rank = igniterRank(container.getItem(i));
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** True when {@code container} holds at least one item that can light TNT. */
    public static boolean hasIgniter(Container container) {
        return selectIgniterSlot(container) >= 0;
    }

    /** The slot of the first TNT stack in {@code container}, or {@code -1} if it carries none. */
    public static int firstTntSlot(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(Items.TNT)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Ordered, de-duplicated candidate block positions to place the TNT at, best first:
     * one step from the mob toward the target (drop it between them), the mob's own feet,
     * the block just short of the target, then the target's feet. The goal validates each
     * (replaceable + supported) and uses the first that works. All at the mob's foot level —
     * the caller adjusts Y from the actual mob/target block positions passed in.
     */
    public static List<BlockPos> placementCandidates(BlockPos mobPos, BlockPos targetPos) {
        int dx = Integer.signum(targetPos.getX() - mobPos.getX());
        int dz = Integer.signum(targetPos.getZ() - mobPos.getZ());
        Set<BlockPos> ordered = new LinkedHashSet<>();
        if (dx != 0 || dz != 0) {
            ordered.add(mobPos.offset(dx, 0, dz)); // one step toward the target
        }
        ordered.add(mobPos.immutable());            // the mob's own footprint
        if (dx != 0 || dz != 0) {
            ordered.add(targetPos.offset(-dx, 0, -dz)); // just short of the target
        }
        ordered.add(targetPos.immutable());         // the target's feet
        return new ArrayList<>(ordered);
    }
}
