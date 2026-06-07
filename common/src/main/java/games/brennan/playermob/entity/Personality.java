package games.brennan.playermob.entity;

/**
 * A behavioural disposition a {@link PlayerMobEntity} can hold toward a single
 * {@link TargetCategory} of entities. A mob holds one personality <em>per
 * category</em> (see {@link PersonalityProfile}) — e.g. passive toward players
 * but aggressive toward zombies.
 *
 * <p>The behaviour each personality drives is generalised across categories:</p>
 * <ul>
 *   <li>{@link #AGGRESSIVE} — target and attack (vanilla target-selector +
 *       {@code WeaponAwareAttackGoal}).</li>
 *   <li>{@link #FRIENDLY} — approach, look at, periodically crouch as a greeting
 *       gesture, and toss gifts (see {@code FriendlyGreetGoal}).</li>
 *   <li>{@link #PASSIVE} — ignore (no dedicated goal).</li>
 *   <li>{@link #SKEPTICAL} — stop and stare for 1–5s, then resume tasks; raise a
 *       shield / ready a weapon if the entity comes too close (see
 *       {@code SkepticalWatchGoal}).</li>
 *   <li>{@link #SHY} — flee, crouching to hide near cover (see
 *       {@code FleeFromCategoryGoal}).</li>
 * </ul>
 *
 * <p>Not every personality is valid for every category — {@link TargetCategory}
 * owns the allow-matrix (e.g. Skeptical/Shy are disallowed toward animals and
 * villagers). Stored per category as a {@code TrackedData}-free server-side
 * ordinal (rendering needs none of it — the crouch gesture rides the existing
 * shared sneak flag), persisted to NBT by {@link PersonalityProfile}.</p>
 *
 * <p><b>Ordinal stability:</b> the spawn-egg {@code entity_data} component and
 * the {@code /summon ... {PlayerPersonality:N}} NBT path both reference these
 * ordinals — do not reorder without a save-migration story. Current order:
 * {@code AGGRESSIVE=0, FRIENDLY=1, PASSIVE=2, SKEPTICAL=3, SHY=4}.</p>
 */
public enum Personality {

    AGGRESSIVE,
    FRIENDLY,
    PASSIVE,
    SKEPTICAL,
    SHY;

    /**
     * Map a stored ordinal back to a Personality. Out-of-range or negative input
     * falls back to {@link #PASSIVE} — the safe "ignore" disposition — so a
     * corrupt save or a future-stamped value degrades gracefully rather than
     * crashing. Mirrors the old {@code Stance.byOrdinal} contract.
     */
    public static Personality byOrdinal(int ordinal) {
        Personality[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return PASSIVE;
        }
        return values[ordinal];
    }
}
