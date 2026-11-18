package games.brennan.playermob.entity;

/**
 * Which kind of action a reaction-speed delay belongs to. An echo carries a separate reaction
 * speed per channel, each scored from how quickly the player did that kind of thing in the life
 * the echo came from (see {@code player.ReflexTally}). Every {@code react*} helper on
 * {@link PlayerMobEntity} names the channel it reads.
 *
 * <p>{@link #OVERALL} is the single {@code ReactionSpeed} trait every mob has always carried. It is
 * what a channel falls back to when the mob has no value of its own for it — every wild PlayerMob,
 * every save from before channels existed — so those mobs behave exactly as before. It is also the
 * channel for delays no player action maps to (social scans, the skeptical stare).</p>
 */
public enum ReactionChannel {
    /** The single overall trait; also the fallback for every other channel. */
    OVERALL(null),
    /** Hitting back and swinging in hand-to-hand fights. */
    MELEE("Melee"),
    /** Committing to, settling for and re-firing ranged shots. */
    RANGED("Ranged"),
    /** Seeing incoming arrows coming and blocking them. */
    DEFENCE("Defence"),
    /** Placing blocks: rigs, TNT set-ups, rebuilding a way back to the train. */
    BUILDING("Building"),
    /** Moving: carriages, gaps, doors, following, fleeing, getting back to the train. */
    MOVEMENT("Movement"),
    /** Handling items: swaps, buckets, flint, containers, pick-ups, harvests. */
    ITEMS("Items");

    /** NBT key inside the {@code ReactionSpeeds} compound; {@code null} for {@link #OVERALL}. */
    private final String key;

    ReactionChannel(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** The channels that carry a value of their own (every one but {@link #OVERALL}). */
    public static final ReactionChannel[] SPECIFIC = {MELEE, RANGED, DEFENCE, BUILDING, MOVEMENT, ITEMS};
}
