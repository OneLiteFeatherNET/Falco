package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stresses the two rules of the scheduler that only break under real concurrency.
 * <p>
 * Every chunk of an instance calls {@code onTick} with the same timestamp, and Minestom ticks its
 * chunks from a pool, so the "once per tick" gate is contended by construction. The second rule is
 * that no chunk is ever lost: a mark arriving while a pass is forming its areas, or while an area is
 * being computed, must end in a chunk that is still dirty afterwards rather than in one that was
 * quietly dropped. Both fail silently — a lost chunk is a dark patch nobody can explain, because
 * writing light also clears the update flag of the section and the server never recomputes it.
 * </p>
 * <p>
 * <b>These tests run on platform threads on purpose.</b> The workers here wait at a barrier and then
 * hammer the scheduler, and the drain loop below waits for work that other threads have to finish.
 * A virtual thread is scheduled cooperatively: one that never blocks never releases its carrier, and
 * the carrier count defaults to the number of processors. On a machine with two of them the busy
 * threads occupy both carriers, the ones that have to make progress never run, and
 * {@code ExecutorService#close} then waits for tasks that cannot finish — the whole test JVM hangs
 * instead of failing, which is far worse than a red test. The same reasoning is written out in full
 * at {@code RegionFileConcurrencyTest}.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
// The timeout is a second line of defence, not a tuning knob: a stress test that stops making
// progress used to hang the whole pipeline rather than fail. SEPARATE_THREAD is required, because
// the default mode measures the duration after the method returned, which never happens on a hang.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@ExtendWith(MicrotusExtension.class)
class ChunkLightSchedulerConcurrencyTest {

    /**
     * The time a latch is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 60L;

    /**
     * The amount of workers which use the scheduler at the same time.
     */
    private static final int THREAD_COUNT = 8;

    /**
     * The edge length of the square of chunks the workers dirty.
     */
    private static final int GRID = 4;

    /**
     * The height the light source of a chunk sits at.
     */
    private static final int SOURCE_Y = 40;

    @Test
    void testOneTimestampRunsOnlyOnePassNoMatterHowManyChunksReportIt(Env env) throws InterruptedException, ExecutionException {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), task -> {
            runs.incrementAndGet();
            task.run();
        }, 16);
        scheduler.markDirty(instance, 0, 0);

        // Every worker reports the same tick, which is what the chunks of one instance do.
        runInParallel(THREAD_COUNT, _ -> scheduler.onTick(instance, 7L));

        assertEquals(1, runs.get(), "a contended tick gate must let exactly one pass through");
    }

    @Test
    void testNoChunkIsLostWhileManyThreadsMarkAndTick(Env env) throws InterruptedException, ExecutionException {
        Instance instance = env.createEmptyInstance();
        List<Chunk> chunks = lampGrid(instance);

        // A real pool, so areas genuinely run beside the threads that mark and tick.
        try (ExecutorService pool = Executors.newFixedThreadPool(4, Thread.ofPlatform().factory())) {
            ChunkLightService service = new ChunkLightService();
            ChunkLightScheduler scheduler = new ChunkLightScheduler(service, pool, 8);
            AtomicInteger clock = new AtomicInteger();

            runInParallel(THREAD_COUNT, worker -> {
                for (int round = 0; round < GRID * GRID; round++) {
                    int index = (worker * 7 + round * 3) % (GRID * GRID);
                    scheduler.markDirty(instance, index % GRID, index / GRID);
                    scheduler.onTick(instance, clock.incrementAndGet());
                }
            });

            drain(scheduler, instance, clock);

            for (Chunk chunk : chunks) {
                assertEquals(15, service.blockLightAt(chunk, 8, SOURCE_Y, 8),
                        "the chunk " + chunk.getChunkX() + "/" + chunk.getChunkZ() + " never received its light");
            }
        }
    }

    /**
     * Ticks the scheduler until no chunk is dirty any more.
     * <p>
     * A chunk that was marked while its own area was being computed stays dirty on purpose, so the
     * set only empties once a pass has run after the last mark. Nothing marks anything here any
     * more, so this terminates; the deadline only turns a defect into a failure instead of a hang.
     * </p>
     *
     * @param scheduler the scheduler to drain
     * @param instance  the instance whose chunks are lit
     * @param clock     the source of the tick timestamps
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private static void drain(ChunkLightScheduler scheduler, Instance instance, AtomicInteger clock) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);

        while (System.nanoTime() < deadline) {
            if (nothingDirty(scheduler)) {
                return;
            }
            scheduler.onTick(instance, clock.incrementAndGet());
            Thread.sleep(2L);
        }
        assertTrue(nothingDirty(scheduler), "the scheduler never finished its dirty set");
    }

    /**
     * Checks whether the scheduler has no chunk of the grid left to light.
     *
     * @param scheduler the scheduler to ask
     * @return true if no chunk of the grid is dirty, otherwise false
     */
    private static boolean nothingDirty(ChunkLightScheduler scheduler) {
        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                if (scheduler.isDirty(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The work a single worker performs.
     */
    @FunctionalInterface
    private interface Worker {

        /**
         * Runs the work of the worker with the given number.
         *
         * @param worker the number of the worker
         */
        void run(int worker);
    }

    /**
     * Starts the given amount of platform threads and waits for all of them.
     * <p>
     * Every worker waits at the same barrier before it starts, so the calls overlap instead of
     * running one after the other, which is the only way a contended gate can be observed at all.
     * </p>
     *
     * @param count  the amount of workers to start
     * @param worker the work a single worker performs
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if a worker failed
     */
    private static void runInParallel(int count, Worker worker) throws InterruptedException, ExecutionException {
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(count, Thread.ofPlatform().factory())) {
            List<Future<?>> futures = new ArrayList<>(count);

            for (int index = 0; index < count; index++) {
                int number = index;
                futures.add(executor.submit(() -> {
                    awaitStart(start);
                    worker.run(number);
                    return null;
                }));
            }
            start.countDown();

            for (Future<?> future : futures) {
                future.get();
            }
        }
    }

    /**
     * Loads a square of chunks and puts one light source into each of them.
     *
     * @param instance the instance which holds the chunks
     * @return the prepared chunks
     */
    private static List<Chunk> lampGrid(Instance instance) {
        List<Chunk> chunks = new ArrayList<>(GRID * GRID);

        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                Chunk chunk = instance.loadChunk(x, z).join();

                chunk.lockWriteLock();
                try {
                    chunk.setBlock(8, SOURCE_Y, 8, Block.GLOWSTONE);
                } finally {
                    chunk.unlockWriteLock();
                }
                chunks.add(chunk);
            }
        }
        return chunks;
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
}
