package games.brennan.playermob.player;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

/**
 * An append-mostly list whose {@link #snapshot()} is O(1) and immutable — the server thread hands a
 * snapshot to the IO thread on every death without copying the history.
 *
 * <p>A snapshot is a view over {@code (array, size)} at the moment it was taken. {@link #append} only
 * writes slots at or past the live size, which no snapshot reads, and grows by allocating a <em>new</em>
 * array, so older snapshots keep theirs. {@link #set} is copy-on-write (O(n)) so it never changes what an
 * outstanding snapshot sees. Single-writer: mutate from one thread only; snapshots may be read from any
 * thread once safely published (e.g. through an {@code AtomicReference}).</p>
 *
 * <p>Pure Java (no Minecraft types) so it can be unit-tested directly.</p>
 */
final class SnapshotLog<T> {

    private static final int INITIAL_CAPACITY = 16;

    private Object[] items = new Object[INITIAL_CAPACITY];
    private int size;

    /** Add {@code item} at the end. Amortized O(1); never disturbs an existing snapshot. */
    void append(T item) {
        if (size == items.length) {
            items = Arrays.copyOf(items, Math.max(INITIAL_CAPACITY, size * 2));
        }
        items[size++] = item;
    }

    /** Replace the element at {@code index}. Copy-on-write (O(n)): outstanding snapshots keep the old element. */
    void set(int index, T item) {
        checkIndex(index);
        Object[] copy = Arrays.copyOf(items, items.length);
        copy[index] = item;
        items = copy;
    }

    @SuppressWarnings("unchecked")
    T get(int index) {
        checkIndex(index);
        return (T) items[index];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /** An unmodifiable view of the current contents that later {@link #append}/{@link #set} calls never change. O(1). */
    List<T> snapshot() {
        return new Snapshot<>(items, size);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
    }

    private static final class Snapshot<T> extends AbstractList<T> implements RandomAccess {
        private final Object[] items;
        private final int size;

        Snapshot(Object[] items, int size) {
            this.items = items;
            this.size = size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            return (T) items[index];
        }

        @Override
        public int size() {
            return size;
        }
    }
}
