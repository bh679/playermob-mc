package games.brennan.playermob.entity;

/**
 * A player-facing <b>spawn preset</b>: a named bundle of {@link DispositionTraits}
 * values (plus an egg spot-colour) that an archetype spawn egg or
 * {@code /summon} stamps onto a new {@link PlayerMobEntity}. The runtime
 * behaviour is fully numeric ({@link DispositionResolver}); this enum exists only
 * to give the five shipped spawn eggs a recognisable identity.
 *
 * <p>The constant <b>names</b> back the egg registry IDs
 * ({@code player_mob_<name>_spawn_egg}) and the <b>declaration order</b> fixes
 * the creative-menu order, so neither may be reordered without a content-migration
 * story. Each maps to a {@code (fightFlight, friendliness, reactionSpeed)} triple chosen
 * to reproduce the archetype's feel toward players. Reaction speed does not change what an
 * archetype decides — only how sharply it acts, so a skeptical mob reads as twitchy and a
 * passive one as unhurried:</p>
 *
 * <ul>
 *   <li>{@link #AGGRESSIVE} — territorial + high fight → watches then attacks.</li>
 *   <li>{@link #FRIENDLY} — high friendliness → greets (gifts once loved).</li>
 *   <li>{@link #PASSIVE} / {@link #SKEPTICAL} — low friendliness, mid fight → watches when close.</li>
 *   <li>{@link #SHY} — territorial + low fight → watches then flees.</li>
 * </ul>
 */
public enum Archetype {

    AGGRESSIVE(9, 1, 7, 0xB02E26), // red — presses its attack sharply
    FRIENDLY(4, 9, 5, 0x5E7C16),   // green — unremarkable reflexes, all warmth
    PASSIVE(5, 4, 4, 0x8E8E8E),    // grey — unhurried
    SKEPTICAL(5, 3, 8, 0xE0A030),  // amber — a watcher, so quick on the draw
    SHY(1, 1, 6, 0x3AB3DA);        // light blue — jumpy, quick to bolt

    public final int fightFlight;
    public final int friendliness;
    public final int reactionSpeed;
    /** Spawn-egg spot colour, so the variants read apart in the creative menu. */
    public final int eggColor;

    Archetype(int fightFlight, int friendliness, int reactionSpeed, int eggColor) {
        this.fightFlight = fightFlight;
        this.friendliness = friendliness;
        this.reactionSpeed = reactionSpeed;
        this.eggColor = eggColor;
    }
}
