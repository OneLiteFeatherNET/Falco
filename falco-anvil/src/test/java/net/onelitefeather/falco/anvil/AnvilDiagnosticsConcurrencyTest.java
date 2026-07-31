package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stresses the throttling and the counters of the diagnostics from many threads at once.
 * <p>
 * A loader reports from every thread which loads or saves a chunk, so the throttling has to elect
 * exactly one reporter per distinct name no matter how many threads compete for it. Losing that
 * property floods the log of a broken world with thousands of identical lines, and losing an
 * increment makes the summary of a shutdown report fewer chunks than were really processed.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class AnvilDiagnosticsConcurrencyTest {

    /**
     * The time a latch is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 60L;

    @Test
    void testConcurrentReportsElectExactlyOneWinnerForEveryName() throws InterruptedException, ExecutionException {
        // Every thread reports every name, only in a different order. A tracking set which loses an
        // update lets two threads believe they were the first for the same name, which is what
        // turns a single warning into one warning per chunk of a broken world.
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 16;
        int nameCount = 32;
        AtomicIntegerArray blockWinners = new AtomicIntegerArray(nameCount);
        AtomicIntegerArray biomeWinners = new AtomicIntegerArray(nameCount);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                int offset = thread;
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    for (int step = 0; step < nameCount; step++) {
                        int name = (offset + step) % nameCount;

                        if (diagnostics.reportUnknownBlock("falco:block_" + name)) {
                            blockWinners.incrementAndGet(name);
                        }
                        if (diagnostics.reportUnknownBiome("falco:biome_" + name)) {
                            biomeWinners.incrementAndGet(name);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        }

        for (int name = 0; name < nameCount; name++) {
            assertEquals(1, blockWinners.get(name), "the block name " + name + " was reported by more than one thread");
            assertEquals(1, biomeWinners.get(name), "the biome name " + name + " was reported by more than one thread");
        }
        assertEquals(nameCount, diagnostics.unknownBlockCount());
        assertEquals(nameCount, diagnostics.unknownBiomeCount());
    }

    @Test
    void testConcurrentReportsAreRejectedOnceTheCapIsReached() throws InterruptedException, ExecutionException {
        // The cap is filled before the threads start, so the state every thread observes is defined
        // by the happens before edge the executor establishes. Not a single further name may be
        // tracked afterwards, which is the property that keeps the heap of a server bounded when a
        // broken world holds an unlimited amount of unknown names.
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 32;

        for (int name = 0; name < AnvilDiagnostics.MAX_TRACKED_NAMES; name++) {
            assertTrue(diagnostics.reportUnknownBlock("falco:filler_" + name));
        }

        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                int name = thread;
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    return diagnostics.reportUnknownBlock("falco:late_" + name);
                }));
            }
            start.countDown();

            int winners = 0;

            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners++;
                }
            }
            assertEquals(0, winners, "no report may pass once the cap is reached");
        }
        assertEquals(AnvilDiagnostics.MAX_TRACKED_NAMES, diagnostics.unknownBlockCount());
    }

    @Test
    void testConcurrentReportsKeepTheTrackedNamesBounded() throws InterruptedException, ExecutionException {
        // The threads race for the cap from an empty state with far more names than the cap allows.
        // The cap is a check followed by an insert and is therefore a soft one under concurrency:
        // every thread which is between the check and the insert can add one name beyond it, so the
        // set may hold up to one extra name per thread. What must never happen is that the set
        // grows towards the amount of offered names, and that two threads win the same name.
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 32;
        int namesPerThread = 64;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                int owner = thread;
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    int accepted = 0;

                    for (int name = 0; name < namesPerThread; name++) {
                        // Every thread owns its own names, so a name can only be won once and the
                        // amount of winners has to match the size of the tracking set exactly.
                        if (diagnostics.reportUnknownBlock("falco:" + owner + "_" + name)) {
                            accepted++;
                        }
                    }
                    return accepted;
                }));
            }
            start.countDown();

            int winners = 0;

            for (Future<Integer> future : futures) {
                winners += future.get();
            }
            assertEquals(diagnostics.unknownBlockCount(), winners, "every tracked name has to have exactly one winner");
        }

        int tracked = diagnostics.unknownBlockCount();

        assertTrue(tracked >= AnvilDiagnostics.MAX_TRACKED_NAMES, "the cap has to be filled but only " + tracked + " names were tracked");
        assertTrue(
                tracked <= AnvilDiagnostics.MAX_TRACKED_NAMES + threadCount,
                "the cap may be exceeded by at most one name per racing thread but " + tracked + " names were tracked"
        );
    }

    @Test
    void testConcurrentOnceOnlyFlagsElectExactlyOneWinner() throws InterruptedException, ExecutionException {
        // Both flags exist so a world which was generated elsewhere logs its problem once instead of
        // once per chunk. Two winners mean the flag is not a compare and set anymore.
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 64;
        AtomicIntegerArray winners = new AtomicIntegerArray(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    if (diagnostics.reportPartialChunk()) {
                        winners.incrementAndGet(0);
                    }
                    if (diagnostics.reportSectionOutOfRange()) {
                        winners.incrementAndGet(1);
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        }

        assertEquals(1, winners.get(0), "a partial chunk may be reported by exactly one thread");
        assertEquals(1, winners.get(1), "a section outside of the world may be reported by exactly one thread");
    }

    @Test
    void testConcurrentCountingKeepsEveryCounterExact() throws InterruptedException, ExecutionException {
        // The summary of a shutdown must not lose a chunk. Every thread touches all three counters
        // in the same run, which is what a loader does while it loads, saves and fails at once.
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 16;
        int perThread = 2000;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    for (int step = 0; step < perThread; step++) {
                        diagnostics.countChunkLoaded();

                        if (step % 2 == 0) {
                            diagnostics.countChunkSaved();
                        }
                        if (step % 4 == 0) {
                            diagnostics.countError();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        }

        assertEquals((long) threadCount * perThread, diagnostics.chunksLoaded());
        assertEquals((long) threadCount * perThread / 2, diagnostics.chunksSaved());
        assertEquals((long) threadCount * perThread / 4, diagnostics.errors());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void testConcurrentSkipReportsKeepEverySkipCounterExact() throws InterruptedException, ExecutionException {
        // The three skip reasons are the only evidence a user has for a loader which returns
        // nothing, and the loader reports them from every thread which loads a chunk. A lost
        // increment turns "all sixty-four chunks are not fully generated" into a smaller number
        // which no longer accounts for the chunks that went missing.
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 16;
        int perThread = 2000;
        int statusCount = 4;
        AtomicIntegerArray statusWinners = new AtomicIntegerArray(statusCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, platformThreads());

        try {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int thread = 0; thread < threadCount; thread++) {
                futures.add(executor.submit(() -> {
                    awaitStart(start);

                    for (int step = 0; step < perThread; step++) {
                        diagnostics.reportMissingRegionFile();

                        if (step % 2 == 0) {
                            diagnostics.reportMissingChunkEntry();
                        }
                        int status = step % statusCount;

                        if (diagnostics.reportPartialChunk("falco:status_" + status)) {
                            statusWinners.incrementAndGet(status);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            awaitAll(futures);
        } finally {
            executor.shutdownNow();
        }

        assertEquals((long) threadCount * perThread, diagnostics.chunksSkippedWithoutRegionFile());
        assertEquals((long) threadCount * perThread / 2, diagnostics.chunksSkippedWithoutEntry());
        assertEquals((long) threadCount * perThread, diagnostics.chunksSkippedAsPartial());
        assertEquals(
                (long) threadCount * perThread + (long) threadCount * perThread / 2 + (long) threadCount * perThread,
                diagnostics.chunksSkipped()
        );

        for (int status = 0; status < statusCount; status++) {
            assertEquals(1, statusWinners.get(status), "the status " + status + " was logged by more than one thread");
            assertEquals(
                    (long) threadCount * perThread / statusCount,
                    diagnostics.partialChunkStatuses().get("falco:status_" + status),
                    "the status " + status + " lost an increment"
            );
        }
    }

    /**
     * Builds the factory for the platform threads the skip counters are stressed from.
     * <p>
     * Platform threads for the reason the class comment of {@code RegionFileConcurrencyTest} gives:
     * a virtual thread which never blocks never releases its carrier, so a pool of them can starve
     * the very threads a barrier is waiting for and hang the test JVM instead of failing it.
     * </p>
     *
     * @return the factory of the pool
     */
    private static ThreadFactory platformThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "falco-diagnostics-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Waits for the given latch and fails when it is not released in time.
     *
     * @param latch the latch to wait for
     */
    private static void awaitStart(CountDownLatch latch) {
        try {
            assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "a worker waited too long for its barrier");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("a worker was interrupted while it waited for its barrier");
        }
    }

    /**
     * Waits for every given task and propagates the failure of the first broken one.
     *
     * @param futures the tasks to wait for
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a task failed
     */
    private static void awaitAll(List<Future<?>> futures) throws InterruptedException, ExecutionException {
        for (Future<?> future : futures) {
            future.get();
        }
    }
}
