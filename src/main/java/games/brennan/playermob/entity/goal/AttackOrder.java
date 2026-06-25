package games.brennan.playermob.entity.goal;

import net.minecraft.world.entity.LivingEntity;

/**
 * A commanded combat order from {@code /playermob order <name> attack <target> [limit]}.
 * Unlike the movement orders driven by {@link CommandedActionGoal}, an attack just pins a
 * combat target and lets the existing weapon-aware attack goal do the fighting — so it's
 * monitored server-side (see {@code PlayerMobEntity#tickAttackOrder}) rather than owning a
 * goal slot. The monitor clears the target and the order once the limit is met.
 *
 * <ul>
 *   <li>{@link Limit#KILL} — fight until the target dies (no extra stop condition).</li>
 *   <li>{@link Limit#SECONDS} — fight for {@code amount} seconds.</li>
 *   <li>{@link Limit#HEARTS} — fight until the target is at {@code amount} hearts (amount × 2 HP).</li>
 *   <li>{@link Limit#UNTIL_HIT} — fight until the mob is hit back (takes retaliation damage).</li>
 * </ul>
 *
 * <p>A single-strike attack ({@code /playermob order ... attack <target>} with no limit) is
 * <em>not</em> an AttackOrder — it routes through {@link CommandedActionGoal} as a one-hit
 * punch with the mob's equipped weapon.</p>
 *
 * @param target    the entity to attack (held across ticks; guarded on alive/removed each tick)
 * @param limit     the stop condition
 * @param amount    seconds or hearts, per {@code limit} ({@code 0} for KILL / UNTIL_HIT)
 * @param startTick the {@code tickCount} the order was issued, for the duration / hit-back checks
 */
public record AttackOrder(LivingEntity target, Limit limit, int amount, int startTick) {

    public enum Limit { KILL, SECONDS, HEARTS, UNTIL_HIT }
}
