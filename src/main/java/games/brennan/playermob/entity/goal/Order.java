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
 * @param timeoutTicks how long the order stays pending before it's abandoned; {@code < 0} = never
 *                     (see {@code PlayerMobEntity#tickOrderTimeout}). Defaults to
 *                     {@link #DEFAULT_TIMEOUT_TICKS} (2 min).
 * @param interruptible when {@code true} the mob yields the order to combat/flee/float and resumes
 *                      it once the threat clears; when {@code false} it runs to completion (only
 *                      water/float can interrupt). Defaults to {@code true}.
 */
public record Order(OrderType type, LivingEntity targetEntity, BlockPos targetPos, BlockState blockState,
                    int amount, FleeUnit unit, ItemStack item, int timeoutTicks, boolean interruptible) {

    /** How a STEAL order's post-grab flee is bounded. */
    public enum FleeUnit { NONE, SECONDS, BLOCKS }

    /** Default order lifetime before abandonment: 2 minutes at 20 ticks/s. */
    public static final int DEFAULT_TIMEOUT_TICKS = 2400;

    /** Walk to (and follow) a live entity. */
    public static Order walkTo(LivingEntity entity) {
        return new Order(OrderType.WALK, entity, null, null, 0, FleeUnit.NONE, ItemStack.EMPTY,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** Walk to a fixed position. */
    public static Order walkTo(BlockPos pos) {
        return new Order(OrderType.WALK, null, pos, null, 0, FleeUnit.NONE, ItemStack.EMPTY,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** An entity-directed action (punch / punch-at / gift / greet). */
    public static Order toward(OrderType type, LivingEntity entity) {
        return new Order(type, entity, null, null, 0, FleeUnit.NONE, ItemStack.EMPTY,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** Place {@code state} at {@code pos} (conjured from air). */
    public static Order place(BlockPos pos, BlockState state) {
        return new Order(OrderType.PLACE, null, pos, state, 0, FleeUnit.NONE, ItemStack.EMPTY,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** Chase {@code entity} and place {@code state} at its live feet block (conjured from air). */
    public static Order placeAt(LivingEntity entity, BlockState state) {
        return new Order(OrderType.PLACE, entity, null, state, 0, FleeUnit.NONE, ItemStack.EMPTY,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** Walk up and toss {@code item} (conjured) to {@code entity} as a gift. */
    public static Order gift(LivingEntity entity, ItemStack item) {
        return new Order(OrderType.GIFT, entity, null, null, 0, FleeUnit.NONE, item,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** Take the target's held item, then flee {@code amount} (seconds/blocks per {@code unit}, 0/NONE = default). */
    public static Order steal(LivingEntity entity, int amount, FleeUnit unit) {
        return new Order(OrderType.STEAL, entity, null, null, amount, unit, ItemStack.EMPTY,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** Right-click {@code item} on a target entity. */
    public static Order use(LivingEntity entity, ItemStack item) {
        return new Order(OrderType.USE, entity, null, null, 0, FleeUnit.NONE, item,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /** Right-click {@code item} at a position. */
    public static Order use(BlockPos pos, ItemStack item) {
        return new Order(OrderType.USE, null, pos, null, 0, FleeUnit.NONE, item,
            DEFAULT_TIMEOUT_TICKS, true);
    }

    /**
     * A copy of this order with the given lifetime / interruptibility — used by the
     * {@code /playermob order} command to apply the {@code for <n> s} / {@code forever} /
     * {@code nonstop} flags (or the mob's NBT defaults) over a base order.
     */
    public Order withSettings(int timeoutTicks, boolean interruptible) {
        return new Order(type, targetEntity, targetPos, blockState, amount, unit, item,
            timeoutTicks, interruptible);
    }
}
