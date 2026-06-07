package games.brennan.playermob.entity;

/**
 * Chooses how generous a {@link PlayerMobEntity}'s gift should be, from its
 * {@link FeelingLedger feeling} toward the recipient and its
 * {@link DispositionTraits#friendliness() friendliness} trait. A mob only ever
 * gifts someone it has come to love ({@code feeling >= }{@link
 * DispositionResolver#FEELING_LOVE 7}, gated by {@code FriendlyGreetGoal}), so in
 * practice this maps the 7–10 "love" band — wider/more friendly mobs part with
 * better things — onto three tiers:
 *
 * <ul>
 *   <li>{@link GiftTier#TRINKET} — a flower (the baseline gesture).</li>
 *   <li>{@link GiftTier#STAPLE} — a bit of food (a warmer, useful gift).</li>
 *   <li>{@link GiftTier#GEAR} — a real armour/weapon upgrade, reserved for a
 *       deeply loved friend a very friendly mob trusts.</li>
 * </ul>
 *
 * <p>All-static and world-free — takes primitives only — so it unit-tests like
 * {@link DispositionResolver}. The actual item selection (which flower, which
 * backpack piece) lives on the entity; this class only picks the <em>tier</em>.
 * {@link #tierFor} is total over the whole {@code [0,10] × [0,10]} grid (floor is
 * {@code TRINKET}) so it's defined even for feelings the gift path never reaches.</p>
 */
public final class GiftPolicy {

    private GiftPolicy() {}

    /** How valuable a gift the mob is willing to part with. */
    public enum GiftTier {
        /** A flower — the baseline friendly gesture. */
        TRINKET,
        /** A bit of food — a warmer, useful gift. */
        STAPLE,
        /** A real armour/weapon upgrade for the recipient. */
        GEAR
    }

    /** Feeling at/above which a sufficiently friendly mob donates real gear. */
    static final float GEAR_FEELING = 9.0F;
    /** Friendliness at/above which a deeply-loved friend earns a gear gift. */
    static final int GEAR_FRIENDLINESS = 8;
    /** Feeling at/above which the gift steps up from a trinket to a food staple. */
    static final float STAPLE_FEELING = 8.0F;

    /**
     * The gift tier for the given feeling (0–10) and friendliness (0–10). Gear
     * needs <em>both</em> a near-maximal feeling and a high friendliness; a warm
     * feeling alone earns a staple; everything else is a trinket.
     */
    public static GiftTier tierFor(float feeling, int friendliness) {
        if (feeling >= GEAR_FEELING && friendliness >= GEAR_FRIENDLINESS) {
            return GiftTier.GEAR;
        }
        if (feeling >= STAPLE_FEELING) {
            return GiftTier.STAPLE;
        }
        return GiftTier.TRINKET;
    }
}
