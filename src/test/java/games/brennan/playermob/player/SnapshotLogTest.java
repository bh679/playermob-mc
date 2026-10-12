package games.brennan.playermob.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolation oracle for {@link SnapshotLog}: a snapshot is frozen at the moment it was taken, whatever the
 * log does afterwards — the property {@code GlobalLifeStore} relies on to hand its history to the IO
 * thread without copying it.
 */
class SnapshotLogTest {

    @Test
    void snapshotIgnoresLaterAppendsIncludingGrowth() {
        SnapshotLog<Integer> log = new SnapshotLog<>();
        for (int i = 0; i < 16; i++) {
            log.append(i); // fills the initial capacity exactly
        }
        List<Integer> snap = log.snapshot();
        for (int i = 16; i < 100; i++) {
            log.append(i); // forces several re-allocations
        }
        assertEquals(16, snap.size());
        assertEquals(15, snap.get(15));
        assertEquals(100, log.size());
        assertEquals(99, log.get(99));
    }

    @Test
    void snapshotIgnoresAppendsIntoSpareCapacity() {
        SnapshotLog<String> log = new SnapshotLog<>();
        log.append("a");
        List<String> snap = log.snapshot();
        log.append("b"); // same backing array, slot past the snapshot's size
        assertEquals(List.of("a"), snap);
        assertEquals(List.of("a", "b"), log.snapshot());
    }

    @Test
    void setIsCopyOnWrite() {
        SnapshotLog<String> log = new SnapshotLog<>();
        log.append("a");
        log.append("b");
        List<String> before = log.snapshot();
        log.set(0, "A");
        assertEquals(List.of("a", "b"), before, "an outstanding snapshot keeps the old element");
        assertEquals(List.of("A", "b"), log.snapshot());
    }

    @Test
    void snapshotIsUnmodifiableAndBoundsChecked() {
        SnapshotLog<String> log = new SnapshotLog<>();
        assertTrue(log.isEmpty());
        log.append("a");
        List<String> snap = log.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> snap.set(0, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> snap.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> log.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> log.set(1, "x"));
    }
}
