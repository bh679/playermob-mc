package games.brennan.playermob.entity;

/**
 * The behaviour a {@link PlayerMobEntity} adopts toward one specific entity,
 * computed each tick by {@link DispositionResolver} from the mob's two numeric
 * traits ({@link DispositionTraits}) and its {@link FeelingLedger} feeling toward
 * that individual. This replaces the old per-category {@code Personality} enum as
 * the value the goal classes gate on:
 *
 * <ul>
 *   <li>{@link #GREET} — approach, look, crouch-bob, and (once feeling ≥ 7) toss a
 *       gift ({@code FriendlyGreetGoal}).</li>
 *   <li>{@link #FIGHT} — target and attack (target-selector + {@code WeaponAwareAttackGoal}).</li>
 *   <li>{@link #FLEE} — run and hide ({@code FleeFromCategoryGoal}).</li>
 *   <li>{@link #WATCH} — stop and stare, ready a shield/weapon if approached
 *       ({@code SkepticalWatchGoal}).</li>
 *   <li>{@link #IGNORE} — no proactive reaction.</li>
 * </ul>
 *
 * <p>Purely computed — never stored to NBT — so unlike the old enum it carries no
 * ordinal-stability contract.</p>
 */
public enum Reaction {
    GREET,
    FIGHT,
    FLEE,
    WATCH,
    IGNORE
}
