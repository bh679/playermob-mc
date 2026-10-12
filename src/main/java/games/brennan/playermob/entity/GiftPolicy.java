package games.brennan.playermob.entity;

/**
 * Chooses how generous a {@link PlayerMobEntity}'s gift should be, from its
 * {@link FeelingLedger feeling} toward the recipient and its
 * {@link DispositionTraits#friendliness() friendliness} trait. A mob only ever
 * gifts someone it has come to love ({@code feeling >= }{@link
 * DispositionResolver#FEELING_LOVE 7}, gated by {@code FriendlyGreetGoal}), so in
 * practice this maps the 7–10 "love" band onto three rungs of value:
 *
 * <ul>
 *   <li>{@link GiftTier#SURPLUS} — things it won't miss: excess food, spare blocks.</li>
 *   <li>{@link GiftTier#SPARE} — a tool/weapon/armour it already holds a better
 *       duplicate of (redundant; costs it nothing).</li>
 *   <li>{@link GiftTier#UPGRADE} — a real gear upgrade for the recipient, even at
 *       its own expense (reserved for a deeply-loved friend a very friendly mob trusts).</li>
 * </ul>
 *
 * <p>Above all three sits the Totem of Undying, gifted from the pack before any rung
 * ({@link #totemsKept}): a mob that loves someone more than {@value #TOTEM_SPARE_FEELING}
 * parts with a <em>spare</em> totem (keeping one for itself), and one that loves them
 * completely ({@link FeelingRecord#MAX}) gives even its last.</p>
 *
 * <p>{@link #tierFor} picks the <b>top</b> rung the mob will reach for;
 * {@link #cascadeFrom} expands that into the ordered list of rungs to try,
 * richest-first, so a mob that lacks its top-rung item gracefully steps down to a
 * cheaper gift rather than giving nothing. Both are all-static, world-free, and
 * unit-tested like {@link DispositionResolver}; the actual item selection (which
 * spare piece, which food) lives on the entity.</p>
 */
public final class GiftPolicy {

    private GiftPolicy() {}

    /** Rung of gift value, cheapest → richest. */
    public enum GiftTier {
        /** Excess food or spare building blocks — things the mob won't miss. */
        SURPLUS,
        /** Gear the mob already holds a better duplicate of. */
        SPARE,
        /** A real gear upgrade for the recipient, at the mob's own expense. */
        UPGRADE
    }

    /** Feeling at/above which (with enough friendliness) the mob sacrifices a real upgrade. */
    static final float UPGRADE_FEELING = 9.0F;
    /** Friendliness at/above which a deeply-loved friend earns an upgrade gift. */
    static final int UPGRADE_FRIENDLINESS = 8;
    /** Feeling at/above which the mob will part with spare gear. */
    static final float SPARE_FEELING = 8.0F;
    /** Feeling strictly above which the mob gives away a spare Totem of Undying. */
    static final float TOTEM_SPARE_FEELING = 9.0F;
    /** {@link #totemsKept} when the feeling earns no totem at all. */
    public static final int NO_TOTEM = Integer.MAX_VALUE;

    /**
     * How many totems the mob keeps back before it will gift one, for the given
     * feeling (0–10): {@code 0} at {@link FeelingRecord#MAX} (it gives its last),
     * {@code 1} above {@value #TOTEM_SPARE_FEELING} (spares only), else
     * {@link #NO_TOTEM}. The mob gifts a totem when it carries more than this.
     */
    public static int totemsKept(float feeling) {
        if (feeling >= FeelingRecord.MAX) {
            return 0;
        }
        return feeling > TOTEM_SPARE_FEELING ? 1 : NO_TOTEM;
    }

    /**
     * The top gift rung for the given feeling (0–10) and friendliness (0–10). An
     * upgrade needs <em>both</em> a near-maximal feeling and high friendliness;
     * spare gear needs a strong feeling; everything else is surplus.
     */
    public static GiftTier tierFor(float feeling, int friendliness) {
        if (feeling >= UPGRADE_FEELING && friendliness >= UPGRADE_FRIENDLINESS) {
            return GiftTier.UPGRADE;
        }
        if (feeling >= SPARE_FEELING) {
            return GiftTier.SPARE;
        }
        return GiftTier.SURPLUS;
    }

    /**
     * The rungs to attempt, richest-first, cascading down from {@code top}: a mob
     * that can't satisfy its top rung falls back to a cheaper gift. {@code UPGRADE}
     * → {UPGRADE, SPARE, SURPLUS}; {@code SPARE} → {SPARE, SURPLUS}; {@code SURPLUS}
     * → {SURPLUS}.
     */
    public static GiftTier[] cascadeFrom(GiftTier top) {
        return switch (top) {
            case UPGRADE -> new GiftTier[] { GiftTier.UPGRADE, GiftTier.SPARE, GiftTier.SURPLUS };
            case SPARE -> new GiftTier[] { GiftTier.SPARE, GiftTier.SURPLUS };
            case SURPLUS -> new GiftTier[] { GiftTier.SURPLUS };
        };
    }
}
