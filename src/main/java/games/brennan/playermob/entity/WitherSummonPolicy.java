package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision logic for {@link games.brennan.playermob.entity.goal.WitherSummonGoal} — kept out of the goal so
 * it can be unit-tested without a live world (mirrors {@link EndCrystalCombatPolicy} / {@link TntCombatPolicy}).
 *
 * <p>Building a wither is the biggest panic button a player has, so a PlayerMob only reaches for it when every
 * one of four things is true at once:</p>
 * <ol>
 *   <li>it carries the <b>kit</b> — 4 soul blocks and 3 wither skeleton skulls ({@link #hasKit});</li>
 *   <li>it is <b>losing</b> — below half hearts ({@link #isDesperate});</li>
 *   <li>it is <b>furious</b> by nature — top-end fight/flight ({@link #isFurious});</li>
 *   <li>it <b>hates</b> whoever it is fighting ({@link #hatesTarget}).</li>
 * </ol>
 *
 * <p>Soul <b>soil</b> counts alongside soul <b>sand</b>: vanilla's wither pattern accepts either, so a mob that
 * scavenged one kind shouldn't be stuck. Skulls must be the wither skeleton kind — no other head works.</p>
 *
 * <p>{@link #placementCandidates} lays out the vanilla spawn pattern ({@code ^^^} / {@code ###} / {@code ~#~}):
 * a bottom soul block, three soul blocks in a row above it, and three skulls above those. The goal places the
 * blocks; the vanilla spawn check does the rest.</p>
 */
public final class WitherSummonPolicy {

    private WitherSummonPolicy() {}

    /** Soul blocks the pattern needs (bottom + a row of three). */
    public static final int SOUL_BLOCKS_NEEDED = 4;
    /** Wither skeleton skulls the pattern needs (the top row). */
    public static final int SKULLS_NEEDED = 3;

    /** Fight/flight at or above this is "fully aggressive" — the top fifth of the 0-10 trait range. */
    public static final int FF_WITHER_MIN = 8;

    /** How far in front of the mob (in blocks, along its chosen facing) the rig's bottom block is laid. */
    private static final int RIG_DISTANCE = 3;

    /**
     * The seven blocks of a vanilla wither rig, in build order: the {@code bottom} soul block, the three soul
     * blocks of the {@code mid} row above it, and the three skulls above those. {@code midCenter} sits directly
     * on {@code bottom}, and {@code skullCenter} directly on {@code midCenter} — that centre column is the one
     * the goal finishes on, so the last skull it places is a real block placement vanilla can react to.
     */
    public record Rig(BlockPos bottom, BlockPos midCenter, BlockPos midLeft, BlockPos midRight,
                      BlockPos skullCenter, BlockPos skullLeft, BlockPos skullRight) {

        /** The four soul-block positions, bottom first. */
        public List<BlockPos> soulPositions() {
            return List.of(bottom, midCenter, midLeft, midRight);
        }

        /** The three skull positions, with the centre one <em>last</em> (it triggers the spawn). */
        public List<BlockPos> skullPositions() {
            return List.of(skullLeft, skullRight, skullCenter);
        }

        /** Every block the rig occupies — what the goal validates before it starts building. */
        public List<BlockPos> allPositions() {
            List<BlockPos> all = new ArrayList<>(soulPositions());
            all.addAll(skullPositions());
            return List.copyOf(all);
        }
    }

    /** The slot of the first soul sand / soul soil in {@code container}, or {@code -1} if it carries none. */
    public static int firstSoulSlot(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (isSoulBlock(container.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The slot of the first wither skeleton skull in {@code container}, or {@code -1} if it carries none. */
    public static int firstSkullSlot(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(Items.WITHER_SKELETON_SKULL)) {
                return i;
            }
        }
        return -1;
    }

    /** True for the two blocks vanilla's wither pattern accepts as its body: soul sand and soul soil. */
    public static boolean isSoulBlock(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.is(Items.SOUL_SAND) || stack.is(Items.SOUL_SOIL));
    }

    /** How many soul sand / soul soil items {@code container} holds in total. */
    public static int soulBlockCount(Container container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (isSoulBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** How many wither skeleton skulls {@code container} holds in total. */
    public static int skullCount(Container container) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(Items.WITHER_SKELETON_SKULL)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** True when {@code container} holds a full wither kit: {@value #SOUL_BLOCKS_NEEDED} soul blocks + {@value #SKULLS_NEEDED} skulls. */
    public static boolean hasKit(Container container) {
        return soulBlockCount(container) >= SOUL_BLOCKS_NEEDED && skullCount(container) >= SKULLS_NEEDED;
    }

    /** True when the mob is losing — strictly below half its hearts. Guards a non-positive max health. */
    public static boolean isDesperate(float health, float maxHealth) {
        return maxHealth > 0.0F && health < maxHealth * 0.5F;
    }

    /** True when the mob is aggressive enough by nature to reach for a wither ({@link #FF_WITHER_MIN}+). */
    public static boolean isFurious(int fightFlight) {
        return fightFlight >= FF_WITHER_MIN;
    }

    /** True when {@code feeling} toward the current enemy is hate (the same threshold the disposition uses). */
    public static boolean hatesTarget(float feeling) {
        return feeling <= DispositionResolver.FEELING_HATE;
    }

    /** The four horizontal unit directions the rig can be laid along/across (its arms run perpendicular). */
    private static final int[][] HORIZONTAL_4 = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * Ordered candidate rigs, best first — so the goal can fall back to another facing when terrain blocks its
     * preferred one (the same shape as {@link EndCrystalCombatPolicy#placementCandidates}). The rig's bottom
     * block is laid {@value #RIG_DISTANCE} blocks from the mob along a horizontal direction, with the three-wide
     * rows running <em>across</em> that direction. Only the forward hemisphere is offered — directions that don't
     * point away from the target — ordered by how squarely they face it, so the wither rises toward the enemy
     * rather than behind the mob's back. When mob and target share a column all four directions are offered,
     * north first. The caller keeps the Y from the positions passed in and validates each candidate.
     */
    public static List<Rig> placementCandidates(BlockPos mobPos, BlockPos targetPos) {
        int tdx = targetPos.getX() - mobPos.getX();
        int tdz = targetPos.getZ() - mobPos.getZ();
        List<int[]> dirs = new ArrayList<>();
        for (int[] d : HORIZONTAL_4) {
            if (dirScore(d, tdx, tdz) >= 0.0) { // drop the rear hemisphere (the rig would go up behind the mob)
                dirs.add(d);
            }
        }
        dirs.sort((a, b) -> Double.compare(dirScore(b, tdx, tdz), dirScore(a, tdx, tdz))); // best-aligned first (stable)
        List<Rig> out = new ArrayList<>(dirs.size());
        for (int[] d : dirs) {
            out.add(rigAt(mobPos.offset(d[0] * RIG_DISTANCE, 0, d[1] * RIG_DISTANCE), d));
        }
        return out;
    }

    /** Build the vanilla T around {@code bottom}, with the rows running across the {@code dir} the mob faces. */
    private static Rig rigAt(BlockPos bottom, int[] dir) {
        int armX = dir[1];  // perpendicular to the facing: the rows run across it
        int armZ = -dir[0];
        BlockPos base = bottom.immutable();
        BlockPos midCenter = base.above();
        BlockPos skullCenter = base.above(2);
        return new Rig(base,
            midCenter,
            midCenter.offset(-armX, 0, -armZ).immutable(),
            midCenter.offset(armX, 0, armZ).immutable(),
            skullCenter,
            skullCenter.offset(-armX, 0, -armZ).immutable(),
            skullCenter.offset(armX, 0, armZ).immutable());
    }

    /** How squarely direction {@code d} faces the target vector — cosine similarity (length-normalised dot product). */
    private static double dirScore(int[] d, int tdx, int tdz) {
        double len = Math.sqrt((double) d[0] * d[0] + (double) d[1] * d[1]);
        return (d[0] * tdx + d[1] * tdz) / len;
    }
}
