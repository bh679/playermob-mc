package games.brennan.playermob.entity;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Classifies the blocks a PlayerMob may mine to clear a path through a Dungeon-Train
 * carriage that's packed with fill (see {@code DungeonTrainEnvironment#digObstructingBlock}).
 *
 * <p>Deliberately narrow — only the soft "fill" materials a carriage is packed with (ice,
 * the dirt family, mud, moss, snow) plus {@link BlockTags#LOGS} — so the dig reflex can tunnel
 * through a blocked carriage without chewing structural walls, glass, rails, or the
 * stone-brick bed. The bed/rails are additionally excluded by
 * {@link BlockSourcePolicy#isProtectedTrackBlock} at the call site, and the reflex only ever
 * targets a block with real collision, so passable decoration (moss carpet) is never a wall.</p>
 *
 * <p>Stateless and vanilla-types-only. The explicit-{@code Blocks} members are unit-tested
 * directly; the {@link BlockTags#LOGS} branch needs a bound datapack and is covered by the
 * in-game Gate 2 smoke test, not the unit suite (mirrors {@link BlockSourcePolicy}).</p>
 */
public final class TrainDigPolicy {

    private TrainDigPolicy() {}

    /**
     * The soft fill blocks a carriage may be packed with: ice, the dirt family, mud, moss, and snow
     * — the materials observed blocking carriages. Snow layers count because a snowy room's drift
     * 3+ layers deep (4/16 of a block or more) leaves a player-sized mob too tall for the 2-high
     * doorway beside it; 1-layer snow has no collision, so the reflex never treats it as a wall.
     * Logs are covered separately via {@link BlockTags#LOGS} so every wood/stem type counts.
     */
    private static final Set<Block> FILL = Set.of(
        // ice
        Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.FROSTED_ICE,
        // dirt family
        Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK,
        Blocks.PODZOL, Blocks.MYCELIUM, Blocks.DIRT_PATH,
        // mud
        Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS,
        // moss
        Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET,
        // snow
        Blocks.SNOW, Blocks.SNOW_BLOCK);

    /**
     * True if {@code state} is a fill block the mob may mine to pass through a blocked carriage:
     * ice, the dirt family, mud, moss, snow, or any log/stem ({@link BlockTags#LOGS}).
     */
    public static boolean isDiggableObstruction(BlockState state) {
        return FILL.contains(state.getBlock()) || state.is(BlockTags.LOGS);
    }
}
