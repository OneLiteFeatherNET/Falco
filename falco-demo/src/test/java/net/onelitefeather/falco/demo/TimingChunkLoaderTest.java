package net.onelitefeather.falco.demo;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the decorator which times the loads. Only the parts which need no running server are covered
 * here: whether the parallel support of the loader below survives the wrapping, and whether closing
 * reaches it. The timing itself is exercised by starting the two server tasks, the same way the
 * measurement tasks exercise the loaders.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
class TimingChunkLoaderTest {

    /**
     * A loader which records what it was asked and answers with fixed values.
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.3.0
     */
    private static final class StubLoader implements ChunkLoader, AutoCloseable {

        private final boolean parallelLoading;

        private final boolean parallelSaving;

        private boolean closed;

        /**
         * Creates a loader which reports the given parallel support.
         *
         * @param parallelLoading whether the loader claims parallel loading
         * @param parallelSaving  whether the loader claims parallel saving
         */
        private StubLoader(boolean parallelLoading, boolean parallelSaving) {
            this.parallelLoading = parallelLoading;
            this.parallelSaving = parallelSaving;
        }

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return this.parallelLoading;
        }

        @Override
        public boolean supportsParallelSaving() {
            return this.parallelSaving;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    /**
     * A loader which cannot be closed, to cover the Minestom side.
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.3.0
     */
    private static final class PlainLoader implements ChunkLoader {

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }
    }

    @Test
    void testTheWrappedLoaderIsHandedBackForTheReport() {
        StubLoader delegate = new StubLoader(true, true);

        assertSame(delegate, new TimingChunkLoader(delegate, new LiveMetrics(0L)).delegate());
    }

    @Test
    void testTheParallelSupportOfTheLoaderBelowSurvivesTheWrapping() {
        TimingChunkLoader parallel = new TimingChunkLoader(new StubLoader(true, true), new LiveMetrics(0L));
        TimingChunkLoader serial = new TimingChunkLoader(new StubLoader(false, false), new LiveMetrics(0L));

        assertTrue(parallel.supportsParallelLoading());
        assertTrue(parallel.supportsParallelSaving());
        assertFalse(serial.supportsParallelLoading());
        assertFalse(serial.supportsParallelSaving());
    }

    @Test
    void testClosingReachesACloseableLoader() throws Exception {
        StubLoader delegate = new StubLoader(true, true);

        new TimingChunkLoader(delegate, new LiveMetrics(0L)).close();

        assertTrue(delegate.closed);
    }

    @Test
    void testClosingALoaderWhichIsNotCloseableIsAllowed() {
        TimingChunkLoader loader = new TimingChunkLoader(new PlainLoader(), new LiveMetrics(0L));

        assertDoesNotThrow(loader::close);
    }

    @Test
    void testAnAbsentChunkIsNotCountedAsALoad() {
        LiveMetrics metrics = new LiveMetrics(0L);
        TimingChunkLoader loader = new TimingChunkLoader(new PlainLoader(), metrics);

        // The stub answers null for every position, which is what a loader does for a chunk the
        // world never generated, and it never touches the instance it is handed. Timing those loads
        // would report the speed of a header lookup as the speed of the loader.
        assertNull(loader.loadChunk(null, 0, 0));
        assertNull(loader.loadChunk(null, 7, -3));

        assertEquals(0L, metrics.snapshot(1_000_000_000L).totalChunkLoads());
        assertEquals(0, metrics.snapshot(2_000_000_000L).chunkLoadMillis().count());
    }
}
