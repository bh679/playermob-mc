package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision logic for {@link games.brennan.playermob.entity.goal.EndCrystalCombatGoal} — kept out of the
 * goal so it can be unit-tested without a live world (mirrors {@link TntCombatPolicy} / {@code DispositionResolver}).
 *
 * <p>Answers what the goal needs each cycle:</p>
 * <ol>
 *   <li>Does the mob carry a full <b>kit</b> — an end crystal, an obsidian block for the crystal's base, and a
 *       solid block to hide behind ({@link #hasKit})?</li>
 *   <li>Which slots hold the crystal / base obsidian / cover block / shield
 *       ({@link #firstCrystalSlot} / {@link #firstObsidianSlot} / {@link #selectCoverSlot} / {@link #firstShieldSlot})?</li>
 *   <li>Where do the obsidian base and the cover block go relative to the mob and its target ({@link #layout})?</li>
 * </ol>
 *
 * <p>The <b>cover</b> block is any solid, full-cube block — classified by a full-block collision shape
 * ({@code isCollisionShapeFullBlock}: true for stone / dirt / obsidian / planks; false for slabs / stairs / fences /
 * torches), which is exactly what an explosion's line-of-sight ray-trace treats as cover. That check is cache-backed
 * and context-free (safe with {@code EmptyBlockGetter} under the test {@code Bootstrap}) and version-stable —
 * deliberately <b>not</b> datapack tags (see the no-tag-binding rule), and more precise than {@code canOcclude()},
 * which is {@code true} for slabs/stairs. Obsidian counts as a cover block too, but the mob
 * {@linkplain #selectCoverSlot prefers a non-obsidian block} so its obsidian is kept for crystal bases.</p>
 */
public final class EndCrystalCombatPolicy {

    private EndCrystalCombatPolicy() {}

    /** Where the rig goes: the obsidian {@code base} (with the crystal on top) and the {@code wall} cover block. */
    public record Layout(BlockPos base, BlockPos wall) {}

    /** The slot of the first end crystal in {@code container}, or {@code -1} if it carries none. */
    public static int firstCrystalSlot(Container container) {
        return firstSlotOf(container, Items.END_CRYSTAL);
    }

    /** The slot of the first obsidian block in {@code container} (the crystal's base), or {@code -1} if none. */
    public static int firstObsidianSlot(Container container) {
        return firstSlotOf(container, Items.OBSIDIAN);
    }

    /** The slot of the first (vanilla) shield in {@code container}, or {@code -1} if it carries none. */
    public static int firstShieldSlot(Container container) {
        return firstSlotOf(container, Items.SHIELD);
    }

    private static int firstSlotOf(Container container, net.minecraft.world.item.Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * True when {@code stack} is a solid, full-cube block the mob can hide behind — a {@link BlockItem} whose default
     * state has a full-block collision shape ({@code isCollisionShapeFullBlock}). Excludes non-blocks and
     * partial-collision blocks (slabs, stairs, fences, torches) that wouldn't reliably block a blast's line of sight.
     * The check is cache-backed for a registered block's default state, so {@link EmptyBlockGetter} + the origin
     * {@link BlockPos} are only inert fallbacks — it's pure and safe under the test {@code Bootstrap}.
     */
    public static boolean isCoverBlock(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return blockItem.getBlock().defaultBlockState()
            .isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** The total number of solid cover-block items in {@code container} (obsidian included). */
    public static int solidCoverCount(Container container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (isCoverBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * The slot to draw the cover block from, <b>preferring a non-obsidian</b> solid block (so the mob's obsidian
     * stays available for crystal bases); failing that, any cover block (which may itself be obsidian). Ties resolve
     * to the lower slot index. Returns {@code -1} when no cover block is present.
     */
    public static int selectCoverSlot(Container container) {
        int obsidianFallback = -1;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!isCoverBlock(stack)) {
                continue;
            }
            if (stack.is(Items.OBSIDIAN)) {
                if (obsidianFallback < 0) {
                    obsidianFallback = i;
                }
            } else {
                return i; // a non-obsidian solid block — preferred
            }
        }
        return obsidianFallback;
    }

    /**
     * True when the mob carries a full bombing kit: at least one end crystal, at least one obsidian block for the
     * crystal's base, and at least {@code 3} solid blocks in total — one for the base plus two more for the
     * 2-block-tall cover it hides behind (those cover blocks may themselves be spare obsidian). A single ground block
     * doesn't hide the crouched mob from the elevated point-blank blast, so the cover is two tall.
     */
    public static boolean hasKit(Container container) {
        return firstCrystalSlot(container) >= 0
            && firstObsidianSlot(container) >= 0
            && solidCoverCount(container) >= 3;
    }

    /** The eight horizontal unit directions ({@code dx, dz}), in clockwise order from north. */
    private static final int[][] HORIZONTAL_8 = {
        {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
    };

    /**
     * Ordered candidate rig geometries at the mob's foot level, best first — so the goal can fall back to a different
     * facing when terrain blocks its preferred one (mirrors {@link TntCombatPolicy#placementCandidates}). Each
     * {@link Layout} puts the {@code wall} cover block one step from the mob along some horizontal direction and the
     * obsidian {@code base} (the crystal seats on top of it) one step beyond that, so the cover always sits
     * <em>between</em> the mob and the crystal.
     *
     * <p>Only the <b>forward hemisphere</b> is offered (directions that don't point away from the target), ordered by
     * how squarely they face the target (cosine), so the crystal lands toward or beside the target — never behind the
     * mob. When mob and target share a column (no direction) all eight are offered, north first. The caller keeps the Y
     * from the positions passed in and validates each candidate (buildable base + a clear 2-tall cover column).</p>
     */
    public static List<Layout> placementCandidates(BlockPos mobPos, BlockPos targetPos) {
        int tdx = targetPos.getX() - mobPos.getX();
        int tdz = targetPos.getZ() - mobPos.getZ();
        List<int[]> dirs = new ArrayList<>();
        for (int[] d : HORIZONTAL_8) {
            if (dirScore(d, tdx, tdz) >= 0.0) { // drop the rear hemisphere (crystal would land behind the mob)
                dirs.add(d);
            }
        }
        dirs.sort((a, b) -> Double.compare(dirScore(b, tdx, tdz), dirScore(a, tdx, tdz))); // best-aligned first (stable)
        List<Layout> out = new ArrayList<>(dirs.size());
        for (int[] d : dirs) {
            BlockPos wall = mobPos.offset(d[0], 0, d[1]);
            BlockPos base = mobPos.offset(d[0] * 2, 0, d[1] * 2);
            out.add(new Layout(base.immutable(), wall.immutable()));
        }
        return out;
    }

    /** How squarely direction {@code d} faces the target vector — cosine similarity (length-normalised dot product). */
    private static double dirScore(int[] d, int tdx, int tdz) {
        double len = Math.sqrt((double) d[0] * d[0] + (double) d[1] * d[1]);
        return (d[0] * tdx + d[1] * tdz) / len;
    }
}
