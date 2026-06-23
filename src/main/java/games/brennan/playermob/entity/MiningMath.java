package games.brennan.playermob.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared mining-duration math: how many ticks a mob takes to break a block, scaled by the
 * block's hardness and the held tool's speed — the same realistic timing the recovery
 * gather ({@code TrainRecoveryGoal}) and the Dungeon-Train dig-through reflex
 * ({@code DungeonTrainEnvironment#digObstructingBlock}) both pace their breaks with.
 *
 * <p>Stateless, vanilla-types-only (compiles on every loader) and unit-tested, so the
 * timing rule lives in one place rather than being copied per call site.</p>
 */
public final class MiningMath {

    private MiningMath() {}

    /** Floor on a break: even an instant-break block takes a beat, so it still reads as mining. */
    public static final int MIN_BREAK_TICKS = 5;
    /** Ceiling on a break: a very hard block with a poor tool still finishes in ~6s. */
    public static final int MAX_BREAK_TICKS = 120;

    /**
     * Realistic break duration (ticks) for {@code state} with {@code tool} in hand. Vanilla
     * mining time ≈ hardness × 30 ÷ tool-speed ticks for a harvestable block, clamped to
     * [{@link #MIN_BREAK_TICKS}, {@link #MAX_BREAK_TICKS}]. A non-positive hardness
     * (instant-break) returns the floor.
     */
    public static int breakTicks(BlockState state, BlockGetter level, BlockPos pos, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness <= 0.0f) {
            return MIN_BREAK_TICKS;   // instabreak-ish
        }
        float toolSpeed = Math.max(1.0f, tool.getDestroySpeed(state));
        int ticks = Math.round(hardness * 30.0f / toolSpeed);
        return Mth.clamp(ticks, MIN_BREAK_TICKS, MAX_BREAK_TICKS);
    }
}
