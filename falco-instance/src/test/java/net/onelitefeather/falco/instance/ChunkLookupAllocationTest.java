package net.onelitefeather.falco.instance;

import com.sun.management.ThreadMXBean;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.management.ManagementFactory;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Counts what looking a chunk up allocates, which is US-3.05.
 * <p>
 * A {@code ConcurrentHashMap<Long, Chunk>} boxes its key on every call, and the chunk index of a
 * position is normally far outside the range {@code Long#valueOf} caches, so every lookup is an
 * object that lives until the next young collection. This counts them. It says nothing about time,
 * on purpose: the design refuses to sell the change as a speed gain, because {@code getChunk} is
 * reached on a chunk change rather than per block and {@code ChunkCache} memoises in between.
 * </p>
 *
 * <h2>Why this measures chunk 4/7 and not chunk 0/0</h2>
 * <p>
 * {@code CoordConversion#chunkIndex} is {@code ((long) chunkX << 32) | (chunkZ & 0xffffffffL)}, so
 * the index of the origin chunk is {@code 0L} — the one value in the whole world that
 * {@code Long#valueOf} hands out of its cache instead of allocating. A loop over chunk 0/0 therefore
 * reports zero bytes against the boxed map as well, and would pass before this task did anything at
 * all. The position below has a non-zero {@code chunkX}, so its index is above {@code 2^32} and no
 * autobox cache of any size can reach it. Whoever moves this position moves the point of the test.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("What a chunk lookup allocates")
class ChunkLookupAllocationTest {

    /**
     * How many lookups the measurement performs.
     */
    private static final int LOOKUPS = 500_000;

    /**
     * How many lookups run before the measurement, so the loop is compiled.
     */
    private static final int WARMUP = 100_000;

    /**
     * The X of the measured position, non-zero so that its chunk index is outside every autobox cache.
     */
    private static final int CHUNK_X = 4;

    /**
     * The Z of the measured position.
     */
    private static final int CHUNK_Z = 7;

    /**
     * Where the looked up chunk is published, so no compiler may drop the lookup.
     */
    private static volatile Object sink;

    /**
     * Performs the given number of lookups and reports what the calling thread allocated.
     *
     * @param registry the registry to look up in
     * @param times    how many lookups to perform
     * @return the bytes the calling thread allocated during the loop
     */
    private static long allocatedWhileLookingUp(ChunkRegistry registry, int times) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();

        for (int index = 0; index < times; index++) {
            sink = registry.chunk(CHUNK_X, CHUNK_Z);
        }
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    @Test
    @DisplayName("allocates nothing at all")
    void testALookupIsAllocationFree(Env env) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(),
                "this JVM cannot report per thread allocation, so the question cannot be answered here");
        threads.setThreadAllocatedMemoryEnabled(true);

        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        instance.loadChunk(CHUNK_X, CHUNK_Z).join();
        final ChunkRegistry registry = instance.registry();
        assertNotNull(registry.chunk(CHUNK_X, CHUNK_Z),
                "the position has to carry a chunk, or this loop measures a miss");

        allocatedWhileLookingUp(registry, WARMUP);
        final long allocated = allocatedWhileLookingUp(registry, LOOKUPS);

        System.out.printf("chunk lookups: %,d -> %,d B (%.3f B each)%n",
                LOOKUPS, allocated, (double) allocated / LOOKUPS);
        assertTrue(allocated < LOOKUPS, "a chunk lookup allocated " + allocated + " B over " + LOOKUPS
                + " lookups, which is more than a byte each: the index is still being boxed");
    }
}
