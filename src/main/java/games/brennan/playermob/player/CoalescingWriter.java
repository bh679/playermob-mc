package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Hands snapshots to a background {@link Executor} for persisting, coalescing bursts: only the
 * <em>latest</em> submitted snapshot is ever written, and at most one drain task is queued at a time.
 *
 * <p>Both the worker and {@link #flush()} take the pending snapshot <em>while holding the write lock</em>,
 * so whichever takes later also writes later — an older snapshot can never overwrite a newer one. A
 * {@code flush()} therefore also waits out any write the worker has in flight.</p>
 *
 * <p>Pure Java (no Minecraft types) so it can be unit-tested with a hand-run executor. Sink failures are
 * logged and swallowed — they never kill the worker or propagate to the submitting (server) thread.</p>
 */
final class CoalescingWriter<T> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Executor executor;
    private final Consumer<T> sink;
    private final AtomicReference<T> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Object writeLock = new Object();

    CoalescingWriter(Executor executor, Consumer<T> sink) {
        this.executor = executor;
        this.sink = sink;
    }

    /** Make {@code snapshot} the next thing written, replacing any not-yet-written one. Never blocks. */
    void submit(T snapshot) {
        pending.set(snapshot);
        schedule();
    }

    /** Write any pending snapshot on the calling thread, after any in-flight write finishes. */
    void flush() {
        writePending();
    }

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return; // a drain is already queued; it will pick up the latest snapshot
        }
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            scheduled.set(false);
            LOGGER.warn("[playermob] background writer rejected; writing synchronously", e);
            writePending();
        }
    }

    private void drain() {
        writePending();
        scheduled.set(false);
        // A submit that landed between the write and clearing the flag saw "scheduled" and queued nothing.
        if (pending.get() != null) {
            schedule();
        }
    }

    private void writePending() {
        synchronized (writeLock) {
            T snapshot = pending.getAndSet(null);
            if (snapshot == null) {
                return;
            }
            try {
                sink.accept(snapshot);
            } catch (RuntimeException e) {
                LOGGER.error("[playermob] background write failed", e);
            }
        }
    }
}
