package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.utils.chunk.ChunkSupplier;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins everything a {@link FalcoInstance} does with a {@link ChunkLoader}, on both sides of the move
 * of that code into {@link ChunkPersistence}.
 * <p>
 * None of the four save entry points had a test when this class was written, and the branch that
 * matters most had never been executed at all: a loader which saves in parallel takes a different
 * path than one which does not, and a failure on either path has to reach the future the caller
 * holds rather than the exception manager of the server.
 * </p>
 * <p>
 * The three cases which do not save exist for the same reason one step later. Reading the instance
 * once at construction and telling the loader that a chunk left were delegations no test in this
 * module observed, so the two call sites of the one and the single call site of the other could have
 * vanished in the move without anything turning red. They are pinned here first and moved second.
 * </p>
 *
 * <p>
 * One case is not about a save at all. {@code loadInstance} is the only caller which reaches a
 * {@link FalcoInstance} before its constructor has finished, so it is the case which decides whether
 * the fields the instance delegates to are assigned before or after that hook.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.2.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("What a Falco instance does with its chunk loader")
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
         * How often a chunk was reported as having left the instance.
         */
        private final AtomicInteger unloads = new AtomicInteger();

        /**
         * How often this loader was asked to read the data of an instance.
         */
        private final AtomicInteger instanceLoads = new AtomicInteger();

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

        @Override
        public void unloadChunk(Chunk chunk) {
            this.unloads.incrementAndGet();
        }

        @Override
        public void loadInstance(Instance instance) {
            this.instanceLoads.incrementAndGet();
        }
    }

    /**
     * A loader which configures the instance it is handed while that instance is still being built.
     * <p>
     * {@code ChunkLoader#loadInstance} is a documented Minestom hook and it runs from the constructor
     * of the instance, so it is the one caller which can reach a {@link FalcoInstance} whose
     * constructor has not finished. Everything the instance delegates has to stand by then; a field
     * assigned after this call is read as null here and the constructor dies with a
     * {@link NullPointerException} that names it.
     * </p>
     */
    private static final class ConfiguringLoader implements ChunkLoader {

        /**
         * The supplier this loader installs while the instance is being built.
         */
        private final ChunkSupplier supplier = FalcoChunk::new;

        /**
         * What the instance reported as its supplier before this loader changed it.
         */
        private final AtomicReference<ChunkSupplier> supplierBefore = new AtomicReference<>();

        /**
         * What the instance reported about auto loading before this loader changed it.
         */
        private final AtomicBoolean autoLoadBefore = new AtomicBoolean();

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
            // This loader is about the read at construction, not about saving.
        }

        @Override
        public void loadInstance(Instance instance) {
            final FalcoInstance falco = (FalcoInstance) instance;
            this.supplierBefore.set(falco.getChunkSupplier());
            this.autoLoadBefore.set(falco.hasEnabledAutoChunkLoad());
            falco.setChunkSupplier(this.supplier);
            falco.enableAutoChunkLoad(false);
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

    @Test
    @DisplayName("reads the instance through its loader while it is built, and never again")
    void testTheInstanceIsReadOnceWhenItIsBuilt(Env env) {
        final CountingLoader first = new CountingLoader(false, null);
        final CountingLoader second = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, first);

        instance.setChunkLoader(second);

        assertEquals(1, first.instanceLoads.get(), "building an instance has to read its data once");
        assertEquals(0, second.instanceLoads.get(),
                "a loader swapped in later must not overwrite live state with what is on disk");
    }

    @Test
    @DisplayName("tells the loader that a chunk left the instance")
    void testAnUnloadReachesTheLoader(Env env) {
        final CountingLoader loader = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, loader);
        final Chunk chunk = instance.loadChunk(0, 0).join();

        instance.unloadChunk(chunk);

        assertEquals(1, loader.unloads.get(),
                "the loader may hold bookkeeping for the chunk and has to hear that it left");
    }

    @Test
    @DisplayName("is already usable when its loader reads it from the constructor")
    void testTheInstanceIsUsableWhileTheLoaderReadsIt(Env env) {
        final ConfiguringLoader loader = new ConfiguringLoader();

        final FalcoInstance instance = registered(env, loader);

        assertNotNull(loader.supplierBefore.get(),
                "the chunk supplier has to stand before the loader is let into the instance");
        assertTrue(loader.autoLoadBefore.get(),
                "auto chunk load has to report its default rather than throw while the loader reads");
        assertSame(loader.supplier, instance.getChunkSupplier(),
                "what the loader configured during the read has to survive the rest of the constructor");
        assertFalse(instance.hasEnabledAutoChunkLoad(),
                "a loader which switches auto loading off during the read has to be obeyed");
    }

    @Test
    @DisplayName("is usable on its own, without an instance driving it")
    void testThePartRunsWithoutTheFacade(Env env) {
        final CountingLoader loader = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, loader);
        final ChunkPersistence persistence = new ChunkPersistence(loader);

        persistence.saveInstance(instance).join();
        persistence.saveChunks(List.of()).join();

        assertEquals(1, loader.instanceSaves.get());
        assertSame(loader, persistence.loader());
    }

    @Test
    @DisplayName("uses a loader which saves and loads nothing when it is given none")
    void testTheDefaultLoaderIsTheNoopOne(Env env) {
        registered(env, ChunkLoader.noop());
        final ChunkPersistence persistence = new ChunkPersistence(null);

        assertNotNull(persistence.loader(), "a null loader has to become the noop loader, not stay null");
        assertNull(persistence.read(null, 0, 0), "the noop loader reads nothing");
    }
}
