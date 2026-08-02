package net.onelitefeather.falco.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.tag.Tag;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins whose tags a shared instance writes when it is asked to save.
 * <p>
 * The defect this covers is silent by construction: Minestom's shared instance forwards the call to
 * its container, the container hands itself to the loader, the loader writes the container's tags,
 * and the operation reports success. Nothing is lost that anyone could notice at the time — the tags
 * of the view are simply never written. So the assertion has to be on the argument the loader
 * received, not on whether the call succeeded.
 * </p>
 * <p>
 * The two cases which follow cover the halves the first two never enter: a loader that saves in
 * parallel, and a loader that throws. The second pins a deviation from {@code InstanceContainer},
 * which hands a parallel failure to the {@code ExceptionManager} <em>and</em> to the returned
 * future — handled twice, logged twice. Here the caller is the only one told.
 * </p>
 * <p>
 * The last case pins the other edge of the same repair: a view is constructed with an empty tag
 * handler, so an untagged view hands the loader an empty compound. That is what the method's
 * documentation warns about — an {@code AnvilLoader} returns on an empty compound without touching
 * the file, so the container's data has to be saved through the container.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.2.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("Saving a Falco shared instance")
class FalcoSharedInstanceSaveTest {

    private static final Tag<String> OWNER = Tag.String("owner");

    @Test
    @DisplayName("hands the loader this instance, with this instance's tags")
    void testTheViewSavesItsOwnTags(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader();
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.setTag(OWNER, "shared");

        shared.saveInstance().join();

        assertEquals(1, loader.saved().size());
        assertSame(shared, loader.saved().getFirst());
        assertEquals("shared", loader.written().getFirst().getString("owner"));
    }

    @Test
    @DisplayName("leaves the container's own save alone")
    void testTheContainerStillSavesItself(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader();
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.setTag(OWNER, "shared");

        container.saveInstance().join();

        assertEquals(1, loader.saved().size());
        assertSame(container, loader.saved().getFirst());
        assertEquals("container", loader.written().getFirst().getString("owner"));
    }

    @Test
    @DisplayName("hands a parallel loader the same instance, off the calling thread")
    void testAParallelSaveStillGetsThisInstance(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader(true, null);
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.setTag(OWNER, "shared");

        shared.saveInstance().join();

        assertEquals(1, loader.saved().size());
        assertSame(shared, loader.saved().getFirst());
        assertEquals("shared", loader.written().getFirst().getString("owner"));
        assertNotSame(Thread.currentThread(), loader.threads().getFirst());
    }

    @Test
    @DisplayName("returns a failure to its caller instead of reporting it as well")
    void testAFailureIsReturnedOnceOnBothBranches(Env env) {
        final List<Throwable> handled = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(handled::add);
        for (boolean parallel : new boolean[]{false, true}) {
            final RuntimeException failure = new IllegalStateException("no");
            final RecordingChunkLoader loader = new RecordingChunkLoader(parallel, failure);
            final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
            final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
            env.process().instance().registerSharedInstance(shared);

            final CompletableFuture<Void> future = shared.saveInstance();

            final CompletionException thrown = assertThrows(CompletionException.class, future::join);
            assertSame(failure, thrown.getCause());
        }
        assertEquals(List.of(), handled);
    }

    @Test
    @DisplayName("hands a fresh view's empty data over, which an anvil loader would drop")
    void testAFreshViewHasNothingToWrite(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader();
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);

        shared.saveInstance().join();

        assertSame(shared, loader.saved().getFirst());
        assertEquals(CompoundBinaryTag.empty(), loader.written().getFirst());
    }

    /**
     * A loader which records the instance it was asked to save, the tags that instance carried at
     * that moment, and the thread it was asked on.
     * <p>
     * The thread is recorded because {@code supportsParallelSaving} is what selects between the two
     * halves of the method under test, and only the recorded thread tells the halves apart from the
     * outside — both of them complete the same future with the same value.
     * </p>
     */
    private static final class RecordingChunkLoader implements ChunkLoader {

        private final List<Instance> saved = new CopyOnWriteArrayList<>();
        private final List<CompoundBinaryTag> written = new CopyOnWriteArrayList<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private final boolean parallel;
        private final @Nullable RuntimeException failure;

        private RecordingChunkLoader() {
            this(false, null);
        }

        private RecordingChunkLoader(boolean parallel, @Nullable RuntimeException failure) {
            this.parallel = parallel;
            this.failure = failure;
        }

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
            this.saved.add(instance);
            this.written.add(instance.tagHandler().asCompound());
            this.threads.add(Thread.currentThread());
            if (this.failure != null) throw this.failure;
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        private List<Instance> saved() {
            return this.saved;
        }

        private List<CompoundBinaryTag> written() {
            return this.written;
        }

        private List<Thread> threads() {
            return this.threads;
        }
    }
}
