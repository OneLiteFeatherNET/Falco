package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the builder of the scheduler.
 * <p>
 * The builder exists for two reasons the constructors cannot serve: the kept-chunk count is
 * reachable only through the four parameter constructor, so changing it forces a caller to name an
 * executor and an area size as well, and two {@code int} parameters stand next to each other there,
 * which compiles when swapped and then runs with a silent misconfiguration.
 * </p>
 * <p>
 * Every test runs on a direct executor, so a pass is finished by the time {@code onTick} returns.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
class ChunkLightSchedulerBuilderTest {

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
    void testABuiltSchedulerComputesLightLikeAConstructedOne(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(service).executor(DIRECT).build();
        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8));
    }

    @Test
    void testTheBuilderRejectsAnAreaSizeBelowOne() {
        ChunkLightScheduler.Builder builder = ChunkLightScheduler.builder(new ChunkLightService());

        assertThrows(IllegalArgumentException.class, () -> builder.maxAreaSize(0));
    }

    @Test
    void testTheBuilderRejectsANegativeCacheSize() {
        ChunkLightScheduler.Builder builder = ChunkLightScheduler.builder(new ChunkLightService());

        assertThrows(IllegalArgumentException.class, () -> builder.maxCachedChunks(-1));
    }

    /**
     * The kept-chunk count is settable on its own — the value section 1.2(a) calls unreachable.
     */
    @Test
    void testTheCacheSizeIsSettableWithoutNamingAnExecutorOrAnAreaSize(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler =
                ChunkLightScheduler.builder(service).maxCachedChunks(512).executor(DIRECT).build();
        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8));
    }

    /**
     * A slot returns a new builder and leaves the one it was called on alone.
     * <p>
     * The builder is immutable because a mutable one would break an architecture rule: a class which
     * declares a field from {@code java.util.concurrent} — here the {@code Executor} — must publish
     * every field safely, and a builder writing its fields from ordinary slot methods does not.
     * The observable consequence is this test: a derived builder does not change its origin.
     * </p>
     */
    @Test
    void testASlotLeavesTheBuilderItWasCalledOnUnchanged(Env env) {
        Instance instance = env.createEmptyInstance();

        for (int x = 0; x < 4; x++) {
            instance.loadChunk(x, 0).join();
        }

        AtomicInteger wide = new AtomicInteger();
        AtomicInteger narrow = new AtomicInteger();

        ChunkLightScheduler.Builder base =
                ChunkLightScheduler.builder(new ChunkLightService()).executor(counting(wide));
        ChunkLightScheduler.Builder derived = base.maxAreaSize(2).executor(counting(narrow));

        assertNotSame(base, derived, "a slot returns a new builder");

        ChunkLightScheduler fromBase = base.build();
        ChunkLightScheduler fromDerived = derived.build();

        for (int x = 0; x < 4; x++) {
            fromBase.markDirty(instance, x, 0);
            fromDerived.markDirty(instance, x, 0);
        }
        fromBase.onTick(instance, 1L);
        fromDerived.onTick(instance, 1L);

        assertEquals(1, wide.get(), "the origin still has the default area size of 16");
        assertEquals(2, narrow.get(), "the derived builder caps areas at two chunks");
    }

    @Test
    void testAConfiguredFailureSinkReceivesTheFailureOfAnArea(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        List<Throwable> reported = new CopyOnWriteArrayList<>();
        Executor refusing = task -> {
            throw new IllegalStateException("the executor refused the area");
        };

        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(new ChunkLightService())
                .executor(refusing)
                .onFailure(reported::add)
                .build();

        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        assertEquals(1, reported.size(), "the failure reaches the configured sink");
        assertTrue(scheduler.isDirty(0, 0), "and the chunk stays dirty, as it does today");
    }

    @Test
    void testTheCompletionObserverSeesTheChunksThatWereWritten(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        List<ChunkArea> written = new ArrayList<>();

        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(new ChunkLightService())
                .executor(DIRECT)
                .onAreaCompleted((group, done) -> written.addAll(done))
                .build();

        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        assertTrue(written.contains(new ChunkArea(0, 0)), "the observer sees the chunk it lit");
    }

    /**
     * Sky light can be turned off for a dimension which carries it.
     * <p>
     * The observable difference is the amount of work: the sky pass reads the block states a second
     * time, so a source which counts its queries is asked strictly more often with the pass than
     * without it.
     * </p>
     */
    @Test
    void testSkyLightCanBeDisabledForADimensionThatHasIt(Env env) {
        Instance instance = env.createEmptyInstance();

        int withPass = countSourceQueries(env, instance, ChunkLightScheduler.SkyLight.FROM_DIMENSION);
        int withoutPass = countSourceQueries(env, instance, ChunkLightScheduler.SkyLight.DISABLED);

        assertTrue(withoutPass < withPass,
                "the sky pass costs a second read of the block states: " + withoutPass + " < " + withPass);
    }

    /**
     * Runs one pass and counts how often the light source was queried.
     *
     * @param env      the test environment
     * @param instance the instance the chunk belongs to
     * @param skyLight the sky light setting under test
     * @return the amount of queries the source received
     */
    private static int countSourceQueries(Env env, Instance instance, ChunkLightScheduler.SkyLight skyLight) {
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        AtomicInteger queries = new AtomicInteger();
        BlockLightSource registry = new MinestomBlockLightSource();
        BlockLightSource counting = new BlockLightSource() {

            @Override
            public int emission(int stateId) {
                queries.incrementAndGet();
                return registry.emission(stateId);
            }

            @Override
            public boolean blocksFace(int stateId, BlockFace face) {
                queries.incrementAndGet();
                return registry.blocksFace(stateId, face);
            }
        };

        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(new ChunkLightService(counting))
                .executor(DIRECT)
                .skyLight(skyLight)
                .build();

        scheduler.markDirty(instance, 0, 0);
        scheduler.onTick(instance, 1L);

        return queries.get();
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
