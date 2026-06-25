package games.brennan.playermob.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An immutable, transient order issued to a single PlayerMob by the
 * {@code /playermob order} command and executed by {@link CommandedActionGoal}.
 * Exactly one of {@link #targetEntity()} / {@link #targetPos()} carries the
 * destination; {@link #blockState()} is set only for {@link OrderType#PLACE},
 * {@link #item()} only for {@link OrderType#USE}, and {@link #amount()} /
 * {@link #unit()} only for {@link OrderType#STEAL}'s flee.
 *
 * <p>Never serialised — a fresh order replaces any prior one and is cleared once
 * the goal finishes it.</p>
 *
 * @param target Entity the entity to walk to / act upon, or {@code null} for a fixed-position order
 * @param targetPos    the block position to walk to / place at / use at, or {@code null} for an entity order
 * @param blockState   the block to place (PLACE only), else {@code null}
 * @param amount       the steal-flee amount (seconds or blocks per {@link #unit()}), else 0
 * @param unit         how to interpret {@link #amount()} for a STEAL flee, else {@link FleeUnit#NONE}
 * @param item         the item to right-click (USE only), else {@link ItemStack#EMPTY}
 */
public record Order(OrderType type, LivingEntity targetEntity, BlockPos targetPos, BlockState blockState,
                    int amount, FleeUnit unit, ItemStack item) {

    /** How a STEAL order's post-grab flee is bounded. */
    public enum FleeUnit { NONE, SECONDS, BLOCKS }

    /** Walk to (and follow) a live entity. */
    public static Order walkTo(LivingEntity entity) {
        return new Order(OrderType.WALK, entity, null, null, 0, FleeUnit.NONE, ItemStack.EMPTY);
    }

    /** Walk to a fixed position. */
    public static Order walkTo(BlockPos pos) {
        return new Order(OrderType.WALK, null, pos, null, 0, FleeUnit.NONE, ItemStack.EMPTY);
    }

    /** An entity-directed action (punch / punch-at / gift / greet). */
    public static Order toward(OrderType type, LivingEntity entity) {
        return new Order(type, entity, null, null, 0, FleeUnit.NONE, ItemStack.EMPTY);
    }

    /** Place {@code state} at {@code pos} (conjured from air). */
    public static Order place(BlockPos pos, BlockState state) {
        return new Order(OrderType.PLACE, null, pos, state, 0, FleeUnit.NONE, ItemStack.EMPTY);
    }

    /** Take the target's held item, then flee {@code amount} (seconds/blocks per {@code unit}, 0/NONE = default). */
    public static Order steal(LivingEntity entity, int amount, FleeUnit unit) {
        return new Order(OrderType.STEAL, entity, null, null, amount, unit, ItemStack.EMPTY);
    }

    /** Right-click {@code item} on a target entity. */
    public static Order use(LivingEntity entity, ItemStack item) {
        return new Order(OrderType.USE, entity, null, null, 0, FleeUnit.NONE, item);
    }

    /** Right-click {@code item} at a position. */
    public static Order use(BlockPos pos, ItemStack item) {
        return new Order(OrderType.USE, null, pos, null, 0, FleeUnit.NONE, item);
    }
}
