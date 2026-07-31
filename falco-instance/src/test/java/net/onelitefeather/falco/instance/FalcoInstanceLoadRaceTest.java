package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives an unload against a chunk load which is still running.
 * <p>
 * {@code docs/research/instance-container.md} names this as the one concurrency defect of
 * {@code InstanceContainer} which survives every other cleanup: an unload which loses the race
 * against a load leaves a chunk behind that is published, reports itself as loaded and is no longer
 * reachable through anything the instance offers, so nothing will ever unload it again. The cases
 * here force exactly that interleaving instead of hoping for it, and they check the state of the
 * instance afterwards rather than checking that no exception escaped — the latter would pass on the
 * broken implementation just as well and would prove nothing.
 * </p>
 * <p>
 * The chosen behaviour is that an unload wins. It claims the slot of a running load, and the load
 * discards the chunk it produced when it finds its slot taken. {@link FalcoInstance#unregister} is
 * the reason a decision was needed at all, and it is documented on the members which implement it.
 * </p>
 * <p>
 * <b>The workers here are platform threads on purpose.</b> The loader of these cases parks inside
 * {@code loadChunk} until it is released, and the instance runs a loader without parallel support on
 * the calling thread, so a virtual worker would park with it. That part would be fine, but the
 * releasing and unregistering threads have to make progress while several workers sit in that
 * barrier, and a virtual thread which is never unmounted keeps its carrier. On a machine with two
 * processors the carriers are gone and the test JVM hangs instead of failing, which is why
 * {@code RegionFileConcurrencyTest} made the same choice and explains it at length.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
// The timeout is a second line of defence, not a tuning knob: a stress test which stops making
// progress hangs the whole test JVM, and the last thing the pipeline prints is then the PASSED of
// the case before it. SEPARATE_THREAD is required because the default mode measures the duration
// only after the method returned, which never happens when it is stuck.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@ExtendWith(MicrotusExtension.class)
class FalcoInstanceLoadRaceTest {

    /**
     * The time a latch or barrier is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 60L;

    /**
     * The amount of chunks which are loaded at the same time while the instance is unregistered.
     */
    private static final int PARKED_LOAD_COUNT = 8;

    /**
     * The amount of times a scenario which depends on an interleaving is repeated on a fresh
     * instance. A single run does not always place the publish of a load on the wrong side of the
     * sweep of the unregister, so the scenario is repeated until a defect is practically certain.
     */
    private static final int ATTEMPTS = 16;

    /**
     * The amount of threads which load and unload the same few chunks in the stress case.
     */
    private static final int STRESS_THREAD_COUNT = 8;

    /**
     * The amount of load and unload pairs every thread of the stress case performs.
     */
    private static final int STRESS_ROUNDS = 400;

    /**
     * The amount of distinct chunk positions the threads of the stress case compete for. A small
     * number is deliberate: the defect lives in the transition of a single position, so the threads
     * have to meet on one.
     */
    private static final int STRESS_POSITION_COUNT = 3;

    @Test
    void testUnregisterDuringARunningLoadLeavesNoChunkBehind(Env env) throws InterruptedException {
        final InstanceManager manager = env.process().instance();
        final BarrierLoader loader = new BarrierLoader(1);
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
        manager.registerInstance(instance);

        final AtomicReference<Object> outcome = new AtomicReference<>();
        final Thread worker = Thread.ofPlatform().start(() -> {
            try {
                outcome.set(instance.loadChunk(0, 0).join());
            } catch (Throwable throwable) {
                outcome.set(throwable);
            }
        });
        // The load is inside the loader now, so the unregister below cannot see the chunk in any map
        // of the instance and has to notice the load itself.
        awaitStart(loader.entered);

        instance.unregister(manager);
        loader.release.countDown();
        worker.join();

        assertTrue(instance.getChunks().isEmpty(), "the running load published its chunk after the unregister");
        assertNull(instance.getChunk(0, 0), "the running load published its chunk after the unregister");
        final CompletionException failure = assertInstanceOf(CompletionException.class, outcome.get(),
                "a load whose chunk was discarded has to fail instead of handing the dead chunk back");
        assertInstanceOf(FalcoInstanceException.class, failure.getCause());
    }

    @Test
    void testAnUnregisterWhichRacesEveryRunningLoadLeavesNoChunkBehind(Env env) throws InterruptedException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            unregisterWhileEveryLoadIsRunning(env);
        }
    }

    /**
     * Parks a load for every one of several chunks and then releases them at the same moment the
     * instance is unregistered.
     * <p>
     * Waiting for every load to be inside the loader before the unregister starts is what makes the
     * check afterwards sound: no further load can begin, so a chunk which is in the instance at the
     * end got there after the unregister and is a leak rather than a late request.
     * </p>
     *
     * @param env the environment which provides the server process
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private void unregisterWhileEveryLoadIsRunning(Env env) throws InterruptedException {
        final InstanceManager manager = env.process().instance();
        final BarrierLoader loader = new BarrierLoader(PARKED_LOAD_COUNT);
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
        manager.registerInstance(instance);

        final Queue<Chunk> handedOut = new ConcurrentLinkedQueue<>();
        final List<Thread> workers = new ArrayList<>(PARKED_LOAD_COUNT);
        for (int index = 0; index < PARKED_LOAD_COUNT; index++) {
            final int chunkX = index;
            workers.add(Thread.ofPlatform().start(() -> {
                try {
                    handedOut.add(instance.loadChunk(chunkX, 0).join());
                } catch (CompletionException expected) {
                    // A load whose chunk was discarded reports a failure; that is the point.
                }
            }));
        }
        awaitStart(loader.entered);

        // The release and the unregister leave the barrier together, so the publish of every load
        // lands on a random side of the sweep instead of always after it.
        final CyclicBarrier ready = new CyclicBarrier(2);
        final Thread releaser = Thread.ofPlatform().start(() -> {
            awaitBarrier(ready);
            loader.release.countDown();
        });
        awaitBarrier(ready);
        instance.unregister(manager);

        releaser.join();
        for (Thread worker : workers) worker.join();

        assertTrue(instance.getChunks().isEmpty(),
                "a load which was running during the unregister published its chunk into an instance nothing reaches any more");
        for (Chunk chunk : handedOut) {
            assertFalse(chunk.isLoaded(), "a chunk survived the unregister of its instance and still reports itself as loaded");
        }
    }

    @Test
    void testConcurrentLoadsAndUnloadsNeverLeaveAChunkWhichCannotBeUnloaded(Env env) throws InterruptedException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            loadAndUnloadConcurrently(env);
        }
    }

    /**
     * Loads and unloads a handful of chunk positions from several threads at once.
     * <p>
     * The check afterwards is the invariant an unload has to keep: once the instance is quiet again,
     * the chunks which report themselves as loaded are exactly the chunks the instance still holds.
     * A chunk which is in the map but reports itself as unloaded can never be unloaded again,
     * because every unload path starts by asking it; a chunk which is no longer in the map but still
     * reports itself as loaded kept its tick partition and its viewers and is ticked forever.
     * </p>
     *
     * @param env the environment which provides the server process
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private void loadAndUnloadConcurrently(Env env) throws InterruptedException {
        final InstanceManager manager = env.process().instance();
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        manager.registerInstance(instance);

        final Queue<Chunk> handedOut = new ConcurrentLinkedQueue<>();
        final Queue<String> failures = new ConcurrentLinkedQueue<>();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> workers = new ArrayList<>(STRESS_THREAD_COUNT);

        for (int index = 0; index < STRESS_THREAD_COUNT; index++) {
            final int worker = index;
            workers.add(Thread.ofPlatform().start(() -> {
                awaitStart(start);
                for (int round = 0; round < STRESS_ROUNDS; round++) {
                    final int chunkX = (worker + round) % STRESS_POSITION_COUNT;
                    final Chunk chunk;
                    try {
                        chunk = instance.loadChunk(chunkX, 0).join();
                    } catch (CompletionException exception) {
                        failures.add("a load failed although nothing cancelled it: " + exception.getCause());
                        return;
                    }
                    handedOut.add(chunk);
                    if ((worker + round) % 2 == 0) instance.unloadChunk(chunk);
                }
            }));
        }
        start.countDown();
        for (Thread worker : workers) worker.join();

        assertTrue(failures.isEmpty(), "the workers reported: " + failures);
        for (Chunk chunk : handedOut) {
            final Chunk current = instance.getChunk(chunk.getChunkX(), chunk.getChunkZ());
            if (chunk == current) {
                assertTrue(chunk.isLoaded(),
                        "the instance holds a chunk which reports itself as unloaded, so nothing can ever unload it");
            } else {
                assertFalse(chunk.isLoaded(),
                        "a chunk left the instance without being unloaded, so it keeps its tick partition forever");
            }
        }

        instance.unregister(manager);
        assertTrue(instance.getChunks().isEmpty());
        for (Chunk chunk : handedOut) {
            assertFalse(chunk.isLoaded(), "the unregister left a chunk of the instance loaded");
        }
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
     * Waits for the given barrier and fails when it is not tripped in time.
     *
     * @param barrier the barrier to wait for
     */
    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("a worker was interrupted while it waited for its barrier");
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException exception) {
            fail("a worker waited too long for its barrier: " + exception);
        }
    }

    /**
     * A chunk loader which parks every load until it is released.
     * <p>
     * It reports no parallel support, so the instance performs the load on the thread which asked
     * for the chunk. That is what lets a test hold a known number of loads in flight and then decide
     * when they finish, which is the only way to place an unload inside the window of a load rather
     * than next to it.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.3.0
     */
    private static final class BarrierLoader implements ChunkLoader {

        /**
         * Counts down once for every load which reached the loader.
         */
        private final CountDownLatch entered;

        /**
         * Releases every parked load.
         */
        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * Creates a loader which reports when the given amount of loads is parked.
         *
         * @param expected the amount of loads the test holds in flight
         */
        private BarrierLoader(int expected) {
            this.entered = new CountDownLatch(expected);
        }

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            this.entered.countDown();
            awaitStart(this.release);
            // Null means the loader knows nothing about the chunk, so the instance creates it.
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
            // Nothing is written anywhere; this loader only exists to hold a load open.
        }
    }
}
