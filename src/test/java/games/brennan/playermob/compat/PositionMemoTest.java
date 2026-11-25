package games.brennan.playermob.compat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link PositionMemo}: an answer is reused only for the same key, tick and exact position. */
class PositionMemoTest {

    private final Object mob = new Object();
    private final Object target = new Object();

    private static Supplier<String> counting(AtomicInteger calls, String answer) {
        return () -> {
            calls.incrementAndGet();
            return answer;
        };
    }

    @Test
    void sameQuestionSameTickIsAskedOnce() {
        PositionMemo<String> memo = new PositionMemo<>();
        AtomicInteger calls = new AtomicInteger();
        assertEquals("A", memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "A")));
        assertEquals("A", memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "B")));
        assertEquals(1, calls.get());
    }

    @Test
    void nextTickAsksAgain() {
        PositionMemo<String> memo = new PositionMemo<>();
        AtomicInteger calls = new AtomicInteger();
        memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "A"));
        assertEquals("B", memo.get(mob, 11, 1.5, 79.0, 3.5, counting(calls, "B")));
        assertEquals(2, calls.get());
    }

    @Test
    void movedEntityAsksAgain() {
        PositionMemo<String> memo = new PositionMemo<>();
        AtomicInteger calls = new AtomicInteger();
        memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "A"));
        assertEquals("B", memo.get(mob, 10, 1.6, 79.0, 3.5, counting(calls, "B")), "x moved");
        assertEquals("C", memo.get(mob, 10, 1.6, 79.1, 3.5, counting(calls, "C")), "y moved");
        assertEquals("D", memo.get(mob, 10, 1.6, 79.1, 3.6, counting(calls, "D")), "z moved");
        assertEquals(4, calls.get());
    }

    @Test
    void anotherEntityAtTheSameSpotAsksAgain() {
        PositionMemo<String> memo = new PositionMemo<>();
        AtomicInteger calls = new AtomicInteger();
        memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "A"));
        assertEquals("B", memo.get(target, 10, 1.5, 79.0, 3.5, counting(calls, "B")));
        assertEquals("A", memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "X")), "mob's answer still held");
        assertEquals(2, calls.get());
    }

    @Test
    void aMissingAnswerIsCachedToo() {
        PositionMemo<String> memo = new PositionMemo<>();
        AtomicInteger calls = new AtomicInteger();
        assertNull(memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, null)));
        assertNull(memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "A")));
        assertEquals(1, calls.get());
    }

    @Test
    void oldestEntryIsDroppedWhenFull() {
        PositionMemo<String> memo = new PositionMemo<>();
        AtomicInteger calls = new AtomicInteger();
        Object[] keys = new Object[PositionMemo.SLOTS + 1];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new Object();
            memo.get(keys[i], 10, i, 0, 0, counting(calls, "v" + i));
        }
        assertEquals(keys.length, calls.get());
        assertEquals("v" + (keys.length - 1), memo.get(keys[keys.length - 1], 10, keys.length - 1, 0, 0, counting(calls, "x")));
        assertEquals("again", memo.get(keys[0], 10, 0, 0, 0, counting(calls, "again")), "first entry was evicted");
    }

    @Test
    void clearForgets() {
        PositionMemo<String> memo = new PositionMemo<>();
        AtomicInteger calls = new AtomicInteger();
        memo.get(mob, 10, 1.5, 79.0, 3.5, counting(calls, "A"));
        memo.clear();
        assertSame("B", memo.get(mob, 10, 1.5, 79.0, 3.5, () -> "B"));
    }

    @Test
    void lookupMissesThenPutHits() {
        PositionMemo<String> memo = new PositionMemo<>();
        assertSame(PositionMemo.MISS, memo.lookup(mob, 10, 1.5, 79.0, 3.5));
        memo.put(mob, 10, 1.5, 79.0, 3.5, null);
        assertNull(memo.lookup(mob, 10, 1.5, 79.0, 3.5), "a stored null is a hit, not a miss");
        assertSame(PositionMemo.MISS, memo.lookup(mob, 11, 1.5, 79.0, 3.5));
    }
}
