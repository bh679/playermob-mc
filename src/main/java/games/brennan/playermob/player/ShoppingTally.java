package games.brennan.playermob.player;

import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One life's evidence of the player's <em>shopping</em>: of the traders it came across — villagers
 * with a job and wandering traders — how many it actually traded with. Distilled at death into the
 * echo's {@code Shopping} score (0–10), which decides how keen the echo is to trade (see
 * {@code ShopWithVillagerGoal}).
 *
 * <p><b>Scored as a rate, not a volume.</b> Every trader the player walks near counts as met, once
 * per life; trading with it (at least once) makes it a hit, walking past it a miss. The score is
 * {@code 10 × traded / (traded + missed + PRIOR_WEIGHT)} — a share with a prior at 0, like
 * {@link ReflexTally}, so one trade can't jump straight to 10 and a long life of trading with one
 * villager an hour while ignoring the rest doesn't keep climbing.</p>
 *
 * <p><b>Late misses weigh less.</b> Deep into a long life (2 h+) people simply stop trading, which
 * is expected, not a lack of interest. A trader skipped in the first {@value #FULL_MISS_MINUTES}
 * minutes counts as a full miss; after that a miss weighs less and less, down to
 * {@value #LATE_MISS_WEIGHT} from {@value #LATE_MISS_MINUTES} minutes on. A trade always counts in
 * full. The weight is fixed when the trader is first met.</p>
 *
 * <p>Held in {@link PlayerLifeStore} in its own map, beside — not inside — {@link PlayerLifeRecord},
 * so it stays self-contained. Immutable value type: every {@code with…} returns a fresh tally.
 * Pure (no world) so the scoring is unit-testable — see {@code ShoppingTallyTest}. The trade and
 * emerald counts are kept for the debug readout only.</p>
 */
public final class ShoppingTally {

    // ---- Scoring ----------------------------------------------------------
    /** Weight of the prior at 0 every score starts from — three misses' worth. */
    static final double PRIOR_WEIGHT = 3.0;
    /** Life minutes before which a skipped trader is a full miss. */
    static final double FULL_MISS_MINUTES = 60.0;
    /** Life minutes from which a skipped trader weighs only {@link #LATE_MISS_WEIGHT}. */
    static final double LATE_MISS_MINUTES = 180.0;
    /** The least a skipped trader ever weighs. */
    static final double LATE_MISS_WEIGHT = 0.25;
    /** Most traders remembered individually; older ones fold into the archived totals. */
    static final int MAX_REMEMBERED = 256;

    // ---- NBT --------------------------------------------------------------
    public static final String TAG = "Shopping";
    static final String TAG_TRADES = "Trades";
    static final String TAG_SPENT = "EmeraldsSpent";
    static final String TAG_EARNED = "EmeraldsEarned";
    static final String TAG_ARCHIVED_TRADED = "ArchivedTraded";
    static final String TAG_ARCHIVED_MISSED = "ArchivedMissed";
    static final String TAG_MET = "Met";
    static final String TAG_ID = "Id";
    static final String TAG_WEIGHT = "Weight";
    static final String TAG_TRADED = "Traded";
    static final String TAG_VISITS = "Visits";
    static final String TAG_VISIT_TICKS = "VisitTicks";
    private static final double TICKS_PER_SECOND = 20.0;

    /** One trader met this life: how much skipping it weighs, and whether the player traded with it. */
    record Met(float missWeight, boolean traded) {}

    public static final ShoppingTally EMPTY = new ShoppingTally(0, 0, 0, 0, 0F, new LinkedHashMap<>(), 0, 0L);

    private final int trades;
    private final int emeraldsSpent;
    private final int emeraldsEarned;
    /** Traders folded out of {@link #met} (the oldest, past {@link #MAX_REMEMBERED}) that were traded with. */
    private final int archivedTraded;
    /** Summed miss weight of folded-out traders never traded with. */
    private final float archivedMissed;
    /** Traders met this life, oldest first. Never mutated after construction. */
    private final Map<UUID, Met> met;
    /** Trading screens the player opened this life, and the ticks they were open in total. */
    private final int visits;
    private final long visitTicks;

    private ShoppingTally(int trades, int emeraldsSpent, int emeraldsEarned, int archivedTraded,
                          float archivedMissed, LinkedHashMap<UUID, Met> met, int visits, long visitTicks) {
        this.trades = Math.max(0, trades);
        this.emeraldsSpent = Math.max(0, emeraldsSpent);
        this.emeraldsEarned = Math.max(0, emeraldsEarned);
        this.archivedTraded = Math.max(0, archivedTraded);
        this.archivedMissed = Math.max(0F, archivedMissed);
        this.met = Collections.unmodifiableMap(met);
        this.visits = Math.max(0, visits);
        this.visitTicks = Math.max(0L, visitTicks);
    }

    public int trades() { return trades; }
    public int emeraldsSpent() { return emeraldsSpent; }
    public int emeraldsEarned() { return emeraldsEarned; }
    public int visits() { return visits; }

    /**
     * Average time the player kept a trading screen open, in seconds — how long the echo lingers at
     * a trader. 0 when the player never opened one (the echo then rolls its own).
     */
    public double averageVisitSeconds() {
        return visits == 0 ? 0.0 : visitTicks / (double) visits / TICKS_PER_SECOND;
    }

    /** Traders traded with this life. */
    public int tradedCount() {
        int n = archivedTraded;
        for (Met m : met.values()) {
            if (m.traded()) n++;
        }
        return n;
    }

    /** Summed weight of traders walked past without a trade. */
    public double missedWeight() {
        double w = archivedMissed;
        for (Met m : met.values()) {
            if (!m.traded()) w += m.missWeight();
        }
        return w;
    }

    public boolean isEmpty() {
        return trades == 0 && emeraldsSpent == 0 && emeraldsEarned == 0 && archivedTraded == 0
            && archivedMissed == 0F && met.isEmpty() && visits == 0 && visitTicks == 0L;
    }

    /** How much skipping a trader met {@code lifeMinutes} into the life weighs (1 → {@value #LATE_MISS_WEIGHT}). */
    static double missWeight(double lifeMinutes) {
        if (lifeMinutes <= FULL_MISS_MINUTES) return 1.0;
        if (lifeMinutes >= LATE_MISS_MINUTES) return LATE_MISS_WEIGHT;
        double t = (lifeMinutes - FULL_MISS_MINUTES) / (LATE_MISS_MINUTES - FULL_MISS_MINUTES);
        return 1.0 - t * (1.0 - LATE_MISS_WEIGHT);
    }

    // ---- Credits ----------------------------------------------------------

    /** The player came near trader {@code id}, {@code lifeMinutes} into this life. Once per trader. */
    public ShoppingTally withMet(UUID id, double lifeMinutes) {
        if (id == null || met.containsKey(id)) return this;
        LinkedHashMap<UUID, Met> next = new LinkedHashMap<>(met);
        next.put(id, new Met((float) missWeight(lifeMinutes), false));
        return fold(trades, emeraldsSpent, emeraldsEarned, next);
    }

    /**
     * One completed trade with trader {@code id} that paid {@code spent} emeralds and returned
     * {@code earned}. Marks the trader traded (meeting it now if it wasn't yet).
     */
    public ShoppingTally withTrade(UUID id, double lifeMinutes, int spent, int earned) {
        LinkedHashMap<UUID, Met> next = new LinkedHashMap<>(met);
        if (id != null) {
            Met prior = next.get(id);
            float weight = prior != null ? prior.missWeight() : (float) missWeight(lifeMinutes);
            next.put(id, new Met(weight, true));
        }
        return fold(trades + 1, emeraldsSpent + Math.max(0, spent), emeraldsEarned + Math.max(0, earned), next);
    }

    /** One trading screen the player had open for {@code ticks}. */
    public ShoppingTally withVisit(long ticks) {
        return new ShoppingTally(trades, emeraldsSpent, emeraldsEarned, archivedTraded, archivedMissed,
            new LinkedHashMap<>(met), visits + 1, visitTicks + Math.max(0L, ticks));
    }

    /** Build a tally from {@code next}, folding the oldest entries past {@link #MAX_REMEMBERED} into the archive. */
    private ShoppingTally fold(int trades, int spent, int earned, LinkedHashMap<UUID, Met> next) {
        int foldedTraded = archivedTraded;
        float foldedMissed = archivedMissed;
        Iterator<Met> oldest = next.values().iterator();
        while (next.size() > MAX_REMEMBERED && oldest.hasNext()) {
            Met m = oldest.next();
            if (m.traded()) foldedTraded++;
            else foldedMissed += m.missWeight();
            oldest.remove();
        }
        return new ShoppingTally(trades, spent, earned, foldedTraded, foldedMissed, next, visits, visitTicks);
    }

    // ---- Scoring ----------------------------------------------------------

    /** Unrounded score in {@code [0, 10)}. */
    double rawScore() {
        double traded = tradedCount();
        return 10.0 * traded / (traded + missedWeight() + PRIOR_WEIGHT);
    }

    /** The echo's shopping score, rounded and clamped to {@code [0, 10]}. */
    public int score() {
        long rounded = Math.round(rawScore());
        return (int) Math.max(DispositionTraits.MIN, Math.min(DispositionTraits.MAX, rounded));
    }

    /** Folded-out misses, rounded up, for the readout's met count. */
    private int archivedMissedCount() {
        return (int) Math.ceil(archivedMissed);
    }

    /** Operator-facing breakdown for the {@code /playermob life} readout. */
    public String breakdown() {
        return String.format(Locale.ROOT,
            "shopping %d (raw %.2f) | traded with %d of %d traders met (missed weight %.2f)"
                + " | avg visit %.1fs (%d visits) | trades %d | emeralds spent %d earned %d",
            score(), rawScore(), tradedCount(), met.size() + archivedTraded + archivedMissedCount(),
            missedWeight(), averageVisitSeconds(), visits, trades, emeraldsSpent, emeraldsEarned);
    }

    // ---- NBT --------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_TRADES, trades);
        tag.putInt(TAG_SPENT, emeraldsSpent);
        tag.putInt(TAG_EARNED, emeraldsEarned);
        tag.putInt(TAG_ARCHIVED_TRADED, archivedTraded);
        tag.putFloat(TAG_ARCHIVED_MISSED, archivedMissed);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Met> e : met.entrySet()) {
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUUID(entry, TAG_ID, e.getKey());
            entry.putFloat(TAG_WEIGHT, e.getValue().missWeight());
            entry.putBoolean(TAG_TRADED, e.getValue().traded());
            list.add(entry);
        }
        tag.put(TAG_MET, list);
        tag.putInt(TAG_VISITS, visits);
        tag.putLong(TAG_VISIT_TICKS, visitTicks);
        return tag;
    }

    /** Read back; every missing key is 0/empty, so an absent tag loads as {@link #EMPTY}. */
    public static ShoppingTally load(CompoundTag tag) {
        LinkedHashMap<UUID, Met> met = new LinkedHashMap<>();
        ListTag list = NbtCompat.getListOfType(tag, TAG_MET, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = NbtCompat.compoundAt(list, i);
            if (NbtCompat.hasUUID(entry, TAG_ID)) {
                met.put(NbtCompat.getUUID(entry, TAG_ID), new Met(
                    NbtCompat.getFloatOr(entry, TAG_WEIGHT, 1F),
                    NbtCompat.getBooleanOr(entry, TAG_TRADED, false)));
            }
        }
        return new ShoppingTally(
            NbtCompat.getIntOr(tag, TAG_TRADES, 0),
            NbtCompat.getIntOr(tag, TAG_SPENT, 0),
            NbtCompat.getIntOr(tag, TAG_EARNED, 0),
            NbtCompat.getIntOr(tag, TAG_ARCHIVED_TRADED, 0),
            NbtCompat.getFloatOr(tag, TAG_ARCHIVED_MISSED, 0F),
            met,
            NbtCompat.getIntOr(tag, TAG_VISITS, 0),
            NbtCompat.getLongOr(tag, TAG_VISIT_TICKS, 0L));
    }
}
