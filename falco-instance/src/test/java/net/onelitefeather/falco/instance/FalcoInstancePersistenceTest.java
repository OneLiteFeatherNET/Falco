package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the four save entry points of {@link FalcoInstance} do, before the code that does it
 * moves into {@code ChunkPersistence}.
 * <p>
 * None of them had a test when this class was written, and the branch that matters most had never
 * been executed at all: a loader which saves in parallel takes a different path through
 * {@code runSave} than one which does not, and a failure on either path has to reach the future the
 * caller holds rather than the exception manager of the server.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The save paths of a Falco instance")
class FalcoInstancePersistenceTest {

    /**
     * A loader which counts what it was asked to save and can be told to throw.
     */
    private static final class CountingLoader implements ChunkLoader {

        /**
         * Whether this loader claims to support saving off the calling thread.
         */
        private final boolean parallel;

        /**
         * What every save call throws, null for a loader which succeeds.
         */
        private final @Nullable RuntimeException failure;

        /**
         * How often an instance save reached this loader.
         */
        private final AtomicInteger instanceSaves = new AtomicInteger();

        /**
         * How often a chunk save reached this loader.
         */
        private final AtomicInteger chunkSaves = new AtomicInteger();

        /**
         * The thread the last save ran on.
         */
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        /**
         * Creates a loader.
         *
         * @param parallel whether it claims parallel saving
         * @param failure  what every save throws, null for none
         */
        private CountingLoader(boolean parallel, @Nullable RuntimeException failure) {
            this.parallel = parallel;
            this.failure = failure;
        }

        /**
         * Knows no chunk at all, so every position is created by the instance instead.
         *
         * @param instance the instance which asks
         * @param chunkX   the chunk X
         * @param chunkZ   the chunk Z
         * @return null, always
         */
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public boolean supportsParallelSaving() {
            return this.parallel;
        }

        @Override
        public void saveInstance(Instance instance) {
            this.lastThread.set(Thread.currentThread());
            this.instanceSaves.incrementAndGet();
            if (this.failure != null) throw this.failure;
        }

        @Override
        public void saveChunk(Chunk chunk) {
            this.lastThread.set(Thread.currentThread());
            this.chunkSaves.incrementAndGet();
            if (this.failure != null) throw this.failure;
        }

        @Override
        public void saveChunks(Collection<Chunk> chunks) {
            this.lastThread.set(Thread.currentThread());
            this.chunkSaves.addAndGet(chunks.size());
            if (this.failure != null) throw this.failure;
        }
    }

    /**
     * Creates a registered instance with the given loader.
     *
     * @param env    the environment which provides the server process
     * @param loader the loader of the instance
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env, ChunkLoader loader) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("saves the instance on the calling thread when the loader is not parallel")
    void testSaveInstanceOnTheCallingThread(Env env) {
        final CountingLoader loader = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, loader);

        instance.saveInstance().join();

        assertEquals(1, loader.instanceSaves.get());
        assertSame(Thread.currentThread(), loader.lastThread.get(),
                "a loader without parallel support must not be moved off the calling thread");
    }

    @Test
    @DisplayName("saves the instance off the calling thread when the loader is parallel")
    void testSaveInstanceOnAVirtualThread(Env env) {
        final CountingLoader loader = new CountingLoader(true, null);
        final FalcoInstance instance = registered(env, loader);

        instance.saveInstance().join();

        assertEquals(1, loader.instanceSaves.get());
        assertTrue(loader.lastThread.get().isVirtual(),
                "a loader with parallel support has to be run on a virtual thread");
    }

    @Test
    @DisplayName("hands a failing save back to the caller instead of swallowing it")
    void testAFailingSaveReachesTheCaller(Env env) {
        final RuntimeException boom = new IllegalStateException("the disk is on fire");
        final FalcoInstance instance = registered(env, new CountingLoader(false, boom));

        final CompletionException thrown = assertThrows(CompletionException.class,
                () -> instance.saveInstance().join());

        assertSame(boom, thrown.getCause(), "the failure of the loader is the failure of the future");
    }

    @Test
    @DisplayName("hands a failing parallel save back to the caller as well")
    void testAFailingParallelSaveReachesTheCaller(Env env) {
        final RuntimeException boom = new IllegalStateException("the disk is still on fire");
        final FalcoInstance instance = registered(env, new CountingLoader(true, boom));

        final CompletionException thrown = assertThrows(CompletionException.class,
                () -> instance.saveInstance().join());

        assertSame(boom, thrown.getCause(), "moving the work to a virtual thread must not lose the failure");
    }

    @Test
    @DisplayName("saves one chunk and every chunk through the loader")
    void testChunkSaves(Env env) {
        final CountingLoader loader = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, loader);
        final Chunk chunk = instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();

        instance.saveChunkToStorage(chunk).join();
        assertEquals(1, loader.chunkSaves.get());

        instance.saveChunksToStorage().join();
        assertEquals(3, loader.chunkSaves.get(), "the second call has to hand over both loaded chunks");
    }

    @Test
    @DisplayName("keeps the chunks it already has when the loader is swapped")
    void testSwappingTheLoader(Env env) {
        final CountingLoader first = new CountingLoader(false, null);
        final CountingLoader second = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, first);
        final Chunk chunk = instance.loadChunk(0, 0).join();

        instance.setChunkLoader(second);

        assertSame(second, instance.getChunkLoader());
        assertSame(chunk, instance.getChunk(0, 0), "swapping the loader must not touch loaded chunks");
        instance.saveChunkToStorage(chunk).join();
        assertEquals(0, first.chunkSaves.get(), "the old loader must not see the save");
        assertEquals(1, second.chunkSaves.get(), "the new loader has to");
    }
}
