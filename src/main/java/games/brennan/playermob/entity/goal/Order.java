package games.brennan.playermob.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An immutable, transient order issued to a single PlayerMob by the
 * {@code /playermob order} command and executed by {@link CommandedActionGoal}.
 * Exactly one of {@link #targetEntity()} / {@link #targetPos()} carries the
 * destination; {@link #blockState()} is set only for {@link OrderType#PLACE}.
 *
 * <p>Never serialised — a fresh order replaces any prior one and is cleared once
 * the goal finishes it.</p>
 *
 * @param type         which action to perform
 * @param targetEntity the entity to walk to / act upon, or {@code null} for a fixed-position order
 * @param targetPos    the block position to walk to / place at, or {@code null} for an entity order
 * @param blockState   the block to place (PLACE only), else {@code null}
 */
public record Order(OrderType type, LivingEntity targetEntity, BlockPos targetPos, BlockState blockState) {

    /** Walk to (and follow) a live entity. */
    public static Order walkTo(LivingEntity entity) {
        return new Order(OrderType.WALK, entity, null, null);
    }

    /** Walk to a fixed position. */
    public static Order walkTo(BlockPos pos) {
        return new Order(OrderType.WALK, null, pos, null);
    }

    /** An entity-directed action (punch / gift / greet). */
    public static Order toward(OrderType type, LivingEntity entity) {
        return new Order(type, entity, null, null);
    }

    /** Place {@code state} at {@code pos} (conjured from air). */
    public static Order place(BlockPos pos, BlockState state) {
        return new Order(OrderType.PLACE, null, pos, state);
    }
}
