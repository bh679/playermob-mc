package games.brennan.playermob.compat;

import java.util.function.Supplier;

/**
 * A tiny "same entity, same tick, same spot: same answer" cache, for per-tick lookups a goal asks
 * several times in one pass (the train environment's "which carriage is this mob on?": a goal's
 * {@code canUse} asks it for the mob and for the target, twice each).
 *
 * <p>A fixed ring of {@link #SLOTS} entries keyed by key identity, game tick and exact position. Any
 * change of tick or position misses, so an answer is never reused for a moved entity or a later
 * tick, and a missing answer ({@code null}) is cached like any other. No map, and with
 * {@link #lookup} / {@link #put} no allocation at all.</p>
 *
 * <p>Server-thread only, unsynchronised, like the goals that call it.</p>
 *
 * @param <V> the cached answer
 */
public final class PositionMemo<V> {

    /** Entries kept: the mob and its target, with room for a neighbour or two. */
    public static final int SLOTS = 4;

    private final Object[] keys = new Object[SLOTS];
    private final long[] ticks = new long[SLOTS];
    private final double[] xs = new double[SLOTS];
    private final double[] ys = new double[SLOTS];
    private final double[] zs = new double[SLOTS];
    private final Object[] values = new Object[SLOTS];
    private int next;

    /** What {@link #lookup} returns when the question has not been asked yet this tick. */
    public static final Object MISS = new Object();

    /**
     * The remembered answer for {@code key} at {@code (x, y, z)} on {@code tick} (possibly
     * {@code null}), or {@link #MISS} when it was not asked yet. On a miss, compute the answer and
     * {@link #put} it.
     */
    public Object lookup(Object key, long tick, double x, double y, double z) {
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == key && ticks[i] == tick && xs[i] == x && ys[i] == y && zs[i] == z) {
                return values[i];
            }
        }
        return MISS;
    }

    /** Remember {@code value} for this exact question, dropping the oldest entry when full. */
    public void put(Object key, long tick, double x, double y, double z, V value) {
        int slot = next;
        next = (next + 1) % SLOTS;
        keys[slot] = key;
        ticks[slot] = tick;
        xs[slot] = x;
        ys[slot] = y;
        zs[slot] = z;
        values[slot] = value;
    }

    /** {@link #lookup}, then on a miss {@code compute} and {@link #put}. Convenience for tests and cold paths. */
    @SuppressWarnings("unchecked")
    public V get(Object key, long tick, double x, double y, double z, Supplier<V> compute) {
        Object hit = lookup(key, tick, x, y, z);
        if (hit != MISS) {
            return (V) hit;
        }
        V value = compute.get();
        put(key, tick, x, y, z, value);
        return value;
    }

    /** Forget everything (a server stop, or a test). */
    public void clear() {
        for (int i = 0; i < SLOTS; i++) {
            keys[i] = null;
            values[i] = null;
        }
        next = 0;
    }
}
