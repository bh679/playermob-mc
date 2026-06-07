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
 * story. Each maps to a {@code (fightFlight, friendliness)} pair chosen to
 * reproduce the archetype's feel toward players:</p>
 *
 * <ul>
 *   <li>{@link #AGGRESSIVE} — territorial + high fight → watches then attacks.</li>
 *   <li>{@link #FRIENDLY} — high friendliness → greets (gifts once loved).</li>
 *   <li>{@link #PASSIVE} / {@link #SKEPTICAL} — low friendliness, mid fight → watches when close.</li>
 *   <li>{@link #SHY} — territorial + low fight → watches then flees.</li>
 * </ul>
 */
public enum Archetype {

    AGGRESSIVE(9, 1, 0xB02E26), // red
    FRIENDLY(4, 9, 0x5E7C16),   // green
    PASSIVE(5, 4, 0x8E8E8E),    // grey
    SKEPTICAL(5, 3, 0xE0A030),  // amber
    SHY(1, 1, 0x3AB3DA);        // light blue

    public final int fightFlight;
    public final int friendliness;
    /** Spawn-egg spot colour, so the variants read apart in the creative menu. */
    public final int eggColor;

    Archetype(int fightFlight, int friendliness, int eggColor) {
        this.fightFlight = fightFlight;
        this.friendliness = friendliness;
        this.eggColor = eggColor;
    }
}
