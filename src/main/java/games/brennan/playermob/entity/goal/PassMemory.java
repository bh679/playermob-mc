package games.brennan.playermob.entity.goal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Which shelves a mob has recently walked past, so each one gets a single roll of the dice per
 * pass rather than one every tick it stays in range. A shelf counts as newly passed again once the
 * mob has been away from it — not seen it — for {@link #FORGET_TICKS}.
 *
 * <p>One memory per goal: the reading and writing goals roll independently on the same shelf.
 * Pure (positions are packed longs, time is passed in) so it unit-tests without a world.</p>
 */
public final class PassMemory {

    /** A shelf out of sight this long is forgotten, and passing it again rolls again. */
    static final long FORGET_TICKS = 1200L;
    /** Prune the map once it grows past this, so a long walk down a train can't grow it forever. */
    private static final int PRUNE_AT = 256;

    /** Packed position → the tick it was last seen in range. */
    private final Map<Long, Long> lastSeen = new HashMap<>();

    /**
     * The mob sees the shelf at {@code pos} in range at {@code now}. True only the first time in a
     * pass — when it wasn't seen in the previous {@link #FORGET_TICKS}. Either way it is refreshed,
     * so standing beside a shelf never re-rolls it.
     */
    public boolean firstSight(long pos, long now) {
        Long last = lastSeen.put(pos, now);
        if (lastSeen.size() > PRUNE_AT) {
            prune(now);
        }
        return last == null || now - last >= FORGET_TICKS;
    }

    private void prune(long now) {
        Iterator<Map.Entry<Long, Long>> it = lastSeen.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= FORGET_TICKS) it.remove();
        }
    }

    int size() {
        return lastSeen.size();
    }
}
