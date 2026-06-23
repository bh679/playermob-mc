package games.brennan.playermob.entity;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * The two locked personal traits a {@link PlayerMobEntity} is born with:
 * <b>fight/flight</b> (how it reacts when pushed — high fights, low flees) and
 * <b>friendliness</b> (how welcoming vs. territorial it is). Both are integers in
 * {@code [0, 10]}, set once at spawn and never changed afterward (relationships
 * evolve via {@link FeelingLedger}, not these). Extracted from the entity so this
 * slice is unit-testable without a live world — mirrors the old
 * {@code PersonalityProfile}.
 *
 * <p><b>Explicit-set tracking.</b> Each trait remembers whether it was set
 * deliberately (NBT load, a spawn-egg {@code entity_data} component, or
 * {@code /summon}). {@link #rollIfUnset(RandomSource)} — called from
 * {@code finalizeSpawn} — then randomises only the traits left unset. This is
 * what keeps an archetype egg's pinned values from being clobbered by the spawn
 * roll, and is robust to whether {@code finalizeSpawn} runs before or after the
 * egg's NBT is merged (load is the last writer either way). A {@code /summon}
 * that pins only one trait still rolls the other.</p>
 *
 * <p>Constructor default {@link #DEFAULT}/{@code DEFAULT} (neutral) so a legacy
 * pre-trait save — which has no trait keys and whose {@code finalizeSpawn} never
 * re-runs on load — degrades to a balanced mob rather than a degenerate 0/0.</p>
 */
public final class DispositionTraits {

    public static final String TAG_FIGHT_FLIGHT = "FightFlight";
    public static final String TAG_FRIENDLINESS = "Friendliness";

    public static final int MIN = 0;
    public static final int MAX = 10;
    public static final int DEFAULT = 5;

    private int fightFlight = DEFAULT;
    private int friendliness = DEFAULT;
    private boolean fightFlightExplicit = false;
    private boolean friendlinessExplicit = false;

    public int fightFlight() {
        return fightFlight;
    }

    public int friendliness() {
        return friendliness;
    }

    /** Set fight/flight and mark it explicit (won't be re-rolled at spawn). Clamps to [0,10]. */
    public void setFightFlight(int value) {
        fightFlight = clamp(value);
        fightFlightExplicit = true;
    }

    /** Set friendliness and mark it explicit (won't be re-rolled at spawn). Clamps to [0,10]. */
    public void setFriendliness(int value) {
        friendliness = clamp(value);
        friendlinessExplicit = true;
    }

    /** Convenience: set both traits explicit (e.g. an archetype preset). */
    public void set(int fightFlight, int friendliness) {
        setFightFlight(fightFlight);
        setFriendliness(friendliness);
    }

    /** True only when both traits were set deliberately (load / egg / summon). */
    public boolean isExplicit() {
        return fightFlightExplicit && friendlinessExplicit;
    }

    /**
     * Uniformly randomise each trait in {@code [0, 10]} that was not explicitly
     * set. Called once from {@code finalizeSpawn}.
     */
    public void rollIfUnset(RandomSource random) {
        if (!fightFlightExplicit) {
            fightFlight = random.nextInt(MAX + 1);
        }
        if (!friendlinessExplicit) {
            friendliness = random.nextInt(MAX + 1);
        }
    }

    public void save(CompoundTag tag) {
        tag.putInt(TAG_FIGHT_FLIGHT, fightFlight);
        tag.putInt(TAG_FRIENDLINESS, friendliness);
    }

    /**
     * Read whichever trait keys are present (each marks that trait explicit and
     * is clamped). Missing keys keep the constructor default — so a legacy save
     * with neither key loads as a neutral 5/5 mob. Legacy {@code *Personality}
     * keys are never read.
     */
    public void load(CompoundTag tag) {
        if (tag.contains(TAG_FIGHT_FLIGHT)) {
            setFightFlight(NbtCompat.getIntOr(tag, TAG_FIGHT_FLIGHT, DEFAULT));
        }
        if (tag.contains(TAG_FRIENDLINESS)) {
            setFriendliness(NbtCompat.getIntOr(tag, TAG_FRIENDLINESS, DEFAULT));
        }
    }

    static int clamp(int value) {
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }
}
