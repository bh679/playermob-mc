package games.brennan.playermob.entity.goal;

/**
 * The kinds of one-off action a player can order a specific PlayerMob to perform
 * via {@code /playermob order <name> <action> <target>}. Each is a transient
 * command (see {@link Order} / {@link CommandedActionGoal}) — never persisted.
 *
 * <ul>
 *   <li>{@link #WALK} — path to a position or entity and stop.</li>
 *   <li>{@link #PUNCH} — walk into reach and land a single melee hit.</li>
 *   <li>{@link #ATTACK} — set the entity as a hostile combat target (the existing
 *       weapon-aware attack goal then fights it). Handled directly by the command,
 *       not by {@link CommandedActionGoal}.</li>
 *   <li>{@link #GIFT} — walk up and toss the recipient an item.</li>
 *   <li>{@link #GREET} — walk up and perform the crouch-bow greeting.</li>
 *   <li>{@link #PLACE} — walk near a position and place a block (conjured from air).</li>
 * </ul>
 */
public enum OrderType {
    WALK,
    PUNCH,
    ATTACK,
    GIFT,
    GREET,
    PLACE
}
