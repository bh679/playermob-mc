package games.brennan.playermob.compat;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The recency/proximity-weighted random pick over a pooled set of {@link ReincarnationRecord}s.
 * Pure (no world / no filesystem) so the selection curve is unit-tested directly, like
 * {@code GlobalLifeStore}'s band helpers.
 *
 * <p><b>Recency dominates.</b> Each source supplies its candidates oldest→newest; a record's
 * recency is normalised to its position <em>within its own source</em> ({@code (i+1)/n}, so the
 * newest of every source is {@code 1.0}). That keeps recency comparable across sources whose ids
 * or clocks differ, and avoids one source's absolute numbering drowning another's. The normalised
 * recency is raised to {@link #RECENCY_EXPONENT} so newer lives are <em>much</em> likelier while
 * older ones can still surface (the behaviour asked for: "still random, but much more likely to be
 * recent").</p>
 *
 * <p><b>Carriage proximity is a mild tilt.</b> For a {@link ReincarnationQuery.Mode#CARRIAGE}
 * request, a record's weight is multiplied by a gentle falloff with carriage distance, so within
 * the depth band a death nearer the spawn carriage is slightly preferred — never enough to beat a
 * much more recent life further away. Neutral (factor {@code 1}) for non-carriage requests and for
 * records with no recorded carriage.</p>
 */
public final class ReincarnationWeighting {

    /** How steeply newer lives are favoured: weight ∝ recency^EXPONENT (≥1; higher ⇒ stronger recency bias). */
    public static final double RECENCY_EXPONENT = 3.0;

    /** Carriage-distance falloff: proximity factor = 1/(1 + SCALE·distance). Small ⇒ a mild tilt only. */
    public static final double CARRIAGE_PROXIMITY_SCALE = 0.02;

    private ReincarnationWeighting() {}

    /**
     * Pick one record from {@code groups} (each a source's candidates, oldest→newest, already
     * met-filtered) weighted by recency (dominant) and carriage proximity (mild tilt), or
     * {@code null} if every group is empty. {@code rng} drives the weighted draw.
     */
    public static ReincarnationRecord choose(List<List<ReincarnationRecord>> groups,
                                             ReincarnationQuery query, RandomSource rng) {
        List<ReincarnationRecord> records = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0.0;
        for (List<ReincarnationRecord> group : groups) {
            int n = group.size();
            for (int i = 0; i < n; i++) {
                ReincarnationRecord r = group.get(i);
                double w = weight(recencyNorm(i, n), r.carriage(), query);
                records.add(r);
                weights.add(w);
                total += w;
            }
        }
        if (records.isEmpty() || total <= 0.0) {
            return records.isEmpty() ? null : records.get(records.size() - 1);
        }
        double roll = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < records.size(); i++) {
            cumulative += weights.get(i);
            if (roll < cumulative) {
                return records.get(i);
            }
        }
        return records.get(records.size() - 1); // floating-point guard
    }

    /** Position-based recency in {@code (0, 1]} for the {@code index}-th of {@code size} oldest-first records (newest = 1). */
    static double recencyNorm(int index, int size) {
        return size <= 0 ? 0.0 : (double) (index + 1) / size;
    }

    /** A record's selection weight: steep recency × mild carriage-proximity tilt (neutral off-carriage). */
    static double weight(double recencyNorm, int recordCarriage, ReincarnationQuery query) {
        return Math.pow(recencyNorm, RECENCY_EXPONENT) * proximity(recordCarriage, query);
    }

    /** Carriage-proximity factor in {@code (0, 1]}: {@code 1} at the spawn carriage, gently falling with distance. */
    static double proximity(int recordCarriage, ReincarnationQuery query) {
        if (query.mode() != ReincarnationQuery.Mode.CARRIAGE
            || recordCarriage == TrainConfinement.NO_CARRIAGE) {
            return 1.0;
        }
        int distance = Math.abs(recordCarriage - query.carriage());
        return 1.0 / (1.0 + CARRIAGE_PROXIMITY_SCALE * distance);
    }
}
