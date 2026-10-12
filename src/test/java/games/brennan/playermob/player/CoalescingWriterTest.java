package games.brennan.playermob.player;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coalescing + flush oracle for {@link CoalescingWriter}, the IO hand-off behind
 * {@code GlobalLifeStore.save()}. A hand-run {@link FakeExecutor} makes scheduling deterministic; one
 * real-thread case checks that a flush racing the worker still leaves the newest snapshot last.
 */
class CoalescingWriterTest {

    /** Queues tasks instead of running them; the test decides when the "IO thread" runs. */
    private static final class FakeExecutor implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    @Test
    void burstOfSubmitsCoalescesToOneWriteOfTheLatest() {
        FakeExecutor exec = new FakeExecutor();
        List<String> written = new ArrayList<>();
        CoalescingWriter<String> writer = new CoalescingWriter<>(exec, written::add);

        writer.submit("a");
        writer.submit("b");
        writer.submit("c");

        assertEquals(1, exec.queue.size(), "only one drain task queued for a burst");
        assertTrue(written.isEmpty(), "nothing written on the submitting thread");
        exec.runAll();
        assertEquals(List.of("c"), written);
    }

    @Test
    void flushWritesPendingSynchronouslyAndLeavesWorkerNothing() {
        FakeExecutor exec = new FakeExecutor();
        List<String> written = new ArrayList<>();
        CoalescingWriter<String> writer = new CoalescingWriter<>(exec, written::add);

        writer.submit("a");
        writer.submit("b");
        writer.flush();
        assertEquals(List.of("b"), written);

        exec.runAll();
        assertEquals(List.of("b"), written, "worker must not re-write an already-flushed snapshot");
    }

    @Test
    void flushWithNothingPendingWritesNothing() {
        FakeExecutor exec = new FakeExecutor();
        List<String> written = new ArrayList<>();
        CoalescingWriter<String> writer = new CoalescingWriter<>(exec, written::add);

        writer.flush();
        writer.submit("a");
        exec.runAll();
        writer.flush();

        assertEquals(List.of("a"), written);
    }

    @Test
    void submitAfterDrainSchedulesAgain() {
        FakeExecutor exec = new FakeExecutor();
        List<String> written = new ArrayList<>();
        CoalescingWriter<String> writer = new CoalescingWriter<>(exec, written::add);

        writer.submit("a");
        exec.runAll();
        writer.submit("b");
        assertEquals(1, exec.queue.size(), "a fresh drain is queued once the previous one finished");
        exec.runAll();

        assertEquals(List.of("a", "b"), written);
    }

    @Test
    void submitDuringWriteIsPickedUpByAFollowUpDrain() {
        FakeExecutor exec = new FakeExecutor();
        List<String> written = new ArrayList<>();
        AtomicReference<CoalescingWriter<String>> ref = new AtomicReference<>();
        // While "a" is being written, another death lands — it must not be stranded.
        ref.set(new CoalescingWriter<>(exec, s -> {
            written.add(s);
            if (s.equals("a")) {
                ref.get().submit("b");
            }
        }));

        ref.get().submit("a");
        exec.runAll();

        assertEquals(List.of("a", "b"), written);
    }

    @Test
    void failingSinkDoesNotWedgeTheWriter() {
        FakeExecutor exec = new FakeExecutor();
        List<String> written = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        CoalescingWriter<String> writer = new CoalescingWriter<>(exec, s -> {
            if (fail.getAndSet(false)) {
                throw new IllegalStateException("disk full");
            }
            written.add(s);
        });

        writer.submit("a");
        exec.runAll(); // throws inside the sink — logged, swallowed
        writer.submit("b");
        exec.runAll();

        assertEquals(List.of("b"), written);
    }

    @Test
    void flushRacingRealWorkerLeavesNewestLast() throws InterruptedException {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        List<Integer> written = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CoalescingWriter<Integer> writer = new CoalescingWriter<>(exec, n -> {
            firstWriteStarted.countDown();
            sleepQuietly(5); // a slow disk
            written.add(n);
        });
        try {
            writer.submit(0);
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));
            for (int i = 1; i <= 200; i++) {
                writer.submit(i);
            }
            writer.flush();

            assertEquals(200, written.get(written.size() - 1), "newest snapshot is on disk after flush");
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
        // Even after the worker drains whatever it had queued, writes only ever moved forward.
        for (int i = 1; i < written.size(); i++) {
            assertTrue(written.get(i) > written.get(i - 1), "stale snapshot overwrote a newer one: " + written);
        }
        assertEquals(200, written.get(written.size() - 1));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
