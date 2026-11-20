package games.brennan.playermob.entity;

/**
 * What a PlayerMob is visibly "up to" — the state behind the reaching-at-a-screen pose and the
 * floating panel the client draws in front of it (see {@code client/PlayerMobModel} and
 * {@code client/ActivityPanelLayer}). Synced as a byte ordinal, network-only; never saved.
 *
 * <p><b>Do not reorder</b> — the ordinal is the wire value, and client + server must agree.</p>
 */
public enum MobActivity {
    /** Nothing to show. */
    NONE,
    /** A chest, barrel or shulker box is open in front of the mob. */
    CONTAINER,
    /** The mob is going through its own gear — a loadout change or an idle bag-check. */
    INVENTORY,
    /** The mob is at a villager or wandering trader, going through its trades. */
    TRADING,
    /** The mob is reading a book — at a chiseled shelf or a lectern (appended: ordinal 4). */
    READING,
    /** The mob is writing in a book &amp; quill (appended: ordinal 5). */
    WRITING;

    private static final MobActivity[] VALUES = values();

    /** Total decode of the wire value — anything out of range reads as {@link #NONE}. */
    public static MobActivity fromOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : NONE;
    }

    /**
     * The activity to show this tick. An open container wins; a fight suppresses the inventory
     * look (the mob keeps swinging — it isn't standing in a menu); otherwise a pending gear-change
     * pulse or a running idle bag-check shows {@link #INVENTORY}.
     *
     * @param enabled          the {@code activityAnimations} setting
     * @param alive            dead mobs show nothing
     * @param containerOpen    a raided container is currently flagged open
     * @param inCombat         the mob has an attack target
     * @param loadoutPulseTicks ticks left on the gear-change pulse
     * @param idleBrowsing     the idle bag-check goal is running
     */
    public static MobActivity resolve(boolean enabled, boolean alive, boolean containerOpen,
                                      boolean inCombat, int loadoutPulseTicks, boolean idleBrowsing) {
        return resolve(enabled, alive, containerOpen, false, inCombat, loadoutPulseTicks, idleBrowsing);
    }

    /**
     * As above, with {@code trading}: the mob is visiting a trader (see {@code ShopWithVillagerGoal}).
     * A trade screen shows like an open container — ahead of everything but a container itself.
     */
    public static MobActivity resolve(boolean enabled, boolean alive, boolean containerOpen,
                                      boolean trading, boolean inCombat, int loadoutPulseTicks,
                                      boolean idleBrowsing) {
        return resolve(enabled, alive, containerOpen, trading, inCombat, loadoutPulseTicks, idleBrowsing,
            false, false);
    }

    /**
     * As above, with the book goals: {@code reading} — the reading goal is in its reading phase;
     * {@code writing} — the writing goal is in a writing session. Both show only when nothing else
     * is going on (and never mid-fight); writing wins over reading.
     */
    public static MobActivity resolve(boolean enabled, boolean alive, boolean containerOpen,
                                      boolean trading, boolean inCombat, int loadoutPulseTicks,
                                      boolean idleBrowsing, boolean reading, boolean writing) {
        if (!enabled || !alive) return NONE;
        if (containerOpen) return CONTAINER;
        if (trading) return TRADING;
        if (inCombat) return NONE;
        if (loadoutPulseTicks > 0 || idleBrowsing) return INVENTORY;
        if (writing) return WRITING;
        return reading ? READING : NONE;
    }
}
