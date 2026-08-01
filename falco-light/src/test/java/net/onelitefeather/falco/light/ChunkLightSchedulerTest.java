package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the tick cycle of the scheduler.
 * <p>
 * Every test here runs on a direct executor, so a pass is finished by the time {@code onTick}
 * returns and the assertions describe a fully deterministic run. That is the entire reason the
 * executor is injectable: the alternative would be tests which wait for a background thread and
 * fail on a loaded machine for reasons that have nothing to do with the scheduler.
 * </p>
 * <p>
 * The properties under test are the ones a reader cannot derive from the types alone: that a tick
 * computes each dirty chunk once, that the same tick timestamp arriving from many chunks triggers
 * only one pass, that a clean tick submits no work at all, and that a chunk which changed while its
 * area was being computed is not silently marked as done.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class ChunkLightSchedulerTest {

    /**
     * Runs every task on the calling thread, so a tick is finished when onTick returns.
     */
    private static final Executor DIRECT = Runnable::run;

    /**
     * Places a block in the given chunk while holding its write lock.
     *
     * @param chunk the chunk which receives the block
     * @param x     the x coordinate inside the chunk
     * @param y     the y coordinate of the block
     * @param z     the z coordinate inside the chunk
     * @param block the block to place
     */
    private static void place(Chunk chunk, int x, int y, int z, Block block) {
        chunk.lockWriteLock();
        try {
            chunk.setBlock(x, y, z, block);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    @Test
    void testATickComputesEveryDirtyChunkOnce(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, 16);
        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8));
    }

    @Test
    void testASecondTickWithoutChangesComputesNothing(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        ChunkLightService service = new ChunkLightService();
        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, counting(runs), 16);

        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);
        scheduler.onTick(instance, 2L);

        assertEquals(1, runs.get(), "a clean tick must not submit work");
    }

    @Test
    void testTheSameTimestampTriggersOnlyOnePass(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        ChunkLightService service = new ChunkLightService();
        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, counting(runs), 16);

        scheduler.markDirty(instance, 0, 0);
        // every chunk of the instance reports the same tick timestamp
        scheduler.onTick(instance, 5L);
        scheduler.onTick(instance, 5L);
        scheduler.onTick(instance, 5L);

        assertEquals(1, runs.get());
    }

    @Test
    void testAChunkChangedDuringComputationStaysDirty(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        ChunkLightService service = new ChunkLightService();
        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, counting(runs), 16);

        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);
        // a newer revision than the one the pass recorded
        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 2L);

        assertEquals(2, runs.get(), "the changed chunk has to be computed again");
    }

    @Test
    void testAChunkWhichChangesInsideThePassStaysDirty(Env env) {
        // The revision is raised from inside the block state read, which is exactly what a block
        // change arriving while the light is being computed looks like from the scheduler's side.
        // The result is then built from block states that are already gone, so the chunk must not
        // be cleared from the dirty set.
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler[] holder = new ChunkLightScheduler[1];
        AtomicBoolean bumped = new AtomicBoolean();
        BlockLightSource registry = new MinestomBlockLightSource();
        // The raise happens while the area reads the block states, which is after the pass recorded
        // the revision it started from and before it writes anything back.
        BlockLightSource racing = new BlockLightSource() {

            @Override
            public int emission(int stateId) {
                if (bumped.compareAndSet(false, true)) {
                    holder[0].markDirty(instance, 0, 0);
                }
                return registry.emission(stateId);
            }

            @Override
            public boolean blocksFace(int stateId, BlockFace face) {
                if (bumped.compareAndSet(false, true)) {
                    holder[0].markDirty(instance, 0, 0);
                }
                return registry.blocksFace(stateId, face);
            }
        };
        holder[0] = new ChunkLightScheduler(new ChunkLightService(racing), counting(runs), 16);

        holder[0].markDirty(instance, 0, 0);
        holder[0].onTick(instance, 1L);

        assertTrue(holder[0].isDirty(0, 0), "a chunk that changed during its own pass stays dirty");

        holder[0].onTick(instance, 2L);

        assertEquals(2, runs.get(), "and it is therefore computed again on the next tick");
    }

    @Test
    void testARejectedAreaLeavesItsChunksDirtyAndDoesNotStopTheOthers(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();
        instance.loadChunk(10, 10).join();

        List<Throwable> reported = collectExceptions(env);
        AtomicInteger runs = new AtomicInteger();
        Executor failingFirst = task -> {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("the executor refused the first area");
            }
            task.run();
        };
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), failingFirst, 16);

        scheduler.markDirty(instance, 0, 0);
        scheduler.markDirty(instance, 10, 10);
        scheduler.onTick(instance, 1L);

        // Both areas were offered to the executor even though the first one blew up, and the chunk
        // of the rejected area is still dirty rather than lost.
        assertEquals(2, runs.get(), "one refused area must not stop the next one");
        assertEquals(1, reported.size(), "the refusal has to reach the exception manager");
        assertTrue(scheduler.isDirty(0, 0) || scheduler.isDirty(10, 10), "a refused area keeps its chunk dirty");
    }

    @Test
    void testAnAreaWhichThrewIsNotLeftInFlightForever(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        List<Throwable> reported = collectExceptions(env);
        AtomicInteger runs = new AtomicInteger();
        ChunkLightService exploding = new ChunkLightService(new BlockLightSource() {

            @Override
            public int emission(int stateId) {
                throw new IllegalStateException("the light source refused to answer");
            }

            @Override
            public boolean blocksFace(int stateId, BlockFace face) {
                throw new IllegalStateException("the light source refused to answer");
            }
        });
        ChunkLightScheduler scheduler = new ChunkLightScheduler(exploding, counting(runs), 16);

        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);
        scheduler.onTick(instance, 2L);

        // If the in flight mark survived the exception, the second tick would find the chunk
        // blocked and never submit it again.
        assertEquals(2, runs.get(), "a failed area has to be retried instead of freezing its chunks");
        assertEquals(2, reported.size(), "every failure is reported rather than swallowed");
        assertTrue(scheduler.isDirty(0, 0), "a failed area leaves its chunks dirty");
    }

    /**
     * Replaces the exception handler of the test server so a reported failure is collected instead
     * of failing the test outright, which is what Cyano installs by default.
     *
     * @param env the environment whose server process is reconfigured
     * @return the list which receives every reported failure
     */
    private static List<Throwable> collectExceptions(Env env) {
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        return reported;
    }

    @Test
    void testASchedulerRefusesASecondInstance(Env env) {
        Instance first = env.createEmptyInstance();
        Instance second = env.createEmptyInstance();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), DIRECT, 16);

        scheduler.markDirty(first, 0, 0);

        assertThrows(IllegalStateException.class, () -> scheduler.markDirty(second, 0, 0));
    }

    @Test
    void testTwoNeighbouringChunksAreComputedAsOneArea(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk left = instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();
        place(left, 15, 40, 8, Block.GLOWSTONE);

        AtomicInteger runs = new AtomicInteger();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, counting(runs), 16);

        scheduler.markDirty(instance, 0, 0);
        scheduler.markDirty(instance, 1, 0);
        scheduler.onTick(instance, 1L);

        assertEquals(1, runs.get(), "two chunks sharing a border belong to one area and one task");
        assertEquals(14, service.blockLightAt(instance.getChunk(1, 0), 0, 40, 8));
    }

    @Test
    void testTheAreaCapSplitsOneTickIntoSeveralTasks(Env env) {
        Instance instance = env.createEmptyInstance();

        for (int x = 0; x < 4; x++) {
            instance.loadChunk(x, 0).join();
        }

        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), counting(runs), 2);

        for (int x = 0; x < 4; x++) {
            scheduler.markDirty(instance, x, 0);
        }
        scheduler.onTick(instance, 1L);

        assertEquals(2, runs.get(), "four connected chunks capped at two form two areas");
    }

    /**
     * A coordinate that carries no chunk is dropped by the pass instead of being kept forever.
     * <p>
     * Nothing but a completed computation used to clear the dirty set, and a chunk which is not
     * loaded is never computed. The entry therefore survived every following pass, was claimed
     * again on every tick and never became clean again.
     * </p>
     */
    @Test
    void testAMarkedCoordinateWithoutAChunkStopsBeingDirty(Env env) {
        Instance instance = env.createEmptyInstance();

        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), DIRECT, 16);
        scheduler.markDirty(instance, 5, 5);
        scheduler.onTick(instance, 1L);

        assertFalse(scheduler.isDirty(5, 5), "a coordinate without a chunk cannot stay dirty forever");
    }

    /**
     * A chunk which is unloaded between the mark and the pass is dropped as well.
     * <p>
     * This is the shape the defect takes in a running server: the chunk exists when the block
     * changes and is gone by the time the tick arrives.
     * </p>
     */
    @Test
    void testAChunkUnloadedBeforeThePassStopsBeingDirty(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), DIRECT, 16);
        scheduler.markDirty(instance, 0, 0);
        instance.unloadChunk(chunk);
        scheduler.onTick(instance, 1L);

        assertFalse(scheduler.isDirty(0, 0), "a chunk that left the instance cannot stay dirty forever");
    }

    /**
     * A chunk which is still loaded keeps the existing behaviour: it is computed, then clean.
     * <p>
     * The guard for the two tests above — without it they would also pass if the pass simply
     * cleared everything it claimed.
     * </p>
     */
    @Test
    void testALoadedChunkIsStillComputedRatherThanDropped(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, 16);
        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        assertFalse(scheduler.isDirty(0, 0));
        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8), "the chunk was lit, not discarded");
    }

    /**
     * The default executor is reachable by name, so a caller can pass it back in.
     * <p>
     * Without it the three and four parameter constructors cannot be used without replacing the
     * threading policy: a caller who only wants a different area size has to invent an executor.
     * </p>
     */
    @Test
    void testTheDefaultExecutorCanBeNamedByACaller(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler =
                new ChunkLightScheduler(service, ChunkLightScheduler.defaultExecutor(), 16);
        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        assertTrue(await(() -> service.blockLightAt(chunk, 8, 40, 8) == 15),
                "the default executor runs the pass on a virtual thread");
    }

    /**
     * Waits for a condition the default executor fulfils on another thread.
     *
     * @param condition the condition to wait for
     * @return true if the condition held within the timeout, false if it timed out
     */
    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    /**
     * Wraps the direct executor so a test can count how many tasks were submitted.
     *
     * @param runs the counter which is raised once per submitted task
     * @return an executor which counts and then runs on the calling thread
     */
    private static Executor counting(AtomicInteger runs) {
        return task -> {
            runs.incrementAndGet();
            task.run();
        };
    }
}
