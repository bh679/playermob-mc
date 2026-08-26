package games.brennan.playermob.entity;

/**
 * Maps a PlayerMob menu button id to a one-step edit of a personal trait, for
 * the Creative trait editor in {@code PlayerMobScreen}. The id values are the
 * vanilla container-button ids sent by
 * {@code MultiPlayerGameMode.handleInventoryButtonClick} and routed server-side
 * through {@code PlayerMobMenu.clickMenuButton} — no custom packets.
 *
 * <p>Pure and side-effect-light (it mutates only the {@link DispositionTraits}
 * passed in), so it unit-tests without a live world, like
 * {@link DispositionResolver} / {@code EquipmentEvaluator}. Clamping to
 * {@code [0, 10]} (and marking the trait explicit) is handled by
 * {@link DispositionTraits#setFightFlight(int)} /
 * {@link DispositionTraits#setFriendliness(int)} /
 * {@link DispositionTraits#setReactionSpeed(int)}, so this only computes the
 * adjusted value and delegates.</p>
 *
 * <p>The same id constants are referenced by the client buttons so the wire
 * contract has a single source of truth.</p>
 */
public final class TraitEditButtons {

    /** Decrease fight/flight by one. */
    public static final int FIGHT_FLIGHT_DOWN = 0;
    /** Increase fight/flight by one. */
    public static final int FIGHT_FLIGHT_UP = 1;
    /** Decrease friendliness by one. */
    public static final int FRIENDLINESS_DOWN = 2;
    /** Increase friendliness by one. */
    public static final int FRIENDLINESS_UP = 3;
    /** Decrease reaction speed by one. */
    public static final int REACTION_SPEED_DOWN = 4;
    /** Increase reaction speed by one. */
    public static final int REACTION_SPEED_UP = 5;
    /** One past the last trait id — where {@link FeelingEditButtons#FEELING_BASE} starts. */
    public static final int ID_COUNT = 6;

    private TraitEditButtons() {
    }

    /**
     * Apply the trait adjustment for {@code buttonId} to {@code traits}.
     *
     * @return {@code true} if {@code buttonId} is a known trait button (and the
     *     adjustment was applied), {@code false} for any unrecognised id (traits
     *     left untouched).
     */
    public static boolean apply(int buttonId, DispositionTraits traits) {
        switch (buttonId) {
            case FIGHT_FLIGHT_DOWN:
                traits.setFightFlight(traits.fightFlight() - 1);
                return true;
            case FIGHT_FLIGHT_UP:
                traits.setFightFlight(traits.fightFlight() + 1);
                return true;
            case FRIENDLINESS_DOWN:
                traits.setFriendliness(traits.friendliness() - 1);
                return true;
            case FRIENDLINESS_UP:
                traits.setFriendliness(traits.friendliness() + 1);
                return true;
            case REACTION_SPEED_DOWN:
                traits.setReactionSpeed(traits.reactionSpeed() - 1);
                return true;
            case REACTION_SPEED_UP:
                traits.setReactionSpeed(traits.reactionSpeed() + 1);
                return true;
            default:
                return false;
        }
    }
}
