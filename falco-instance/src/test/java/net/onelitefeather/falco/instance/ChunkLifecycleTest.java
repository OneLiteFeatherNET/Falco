package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reaches the publish and the load completion of a chunk without driving a full load, which is
 * US-3.02.
 * <p>
 * Both were {@code private} methods of {@code FalcoInstance} before this stage. The only way to run
 * either of them was to ask the instance for a chunk, which meant that the case they exist for —
 * a publish that is refused because an unload claimed the position while the loader was still
 * working — could not be arranged from a test at all: it needs the two to interleave, and a caller
 * driving the whole load path has no seam to interleave at. {@code FalcoInstanceLoadRaceTest} gets
 * close by running a thousand loads and unloads against each other and hoping the window is hit;
 * these cases hit it every time, deterministically, in a single thread.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The lifecycle of one chunk, driven step by step")
class ChunkLifecycleTest {

    /**
     * The position every case works on.
     */
    private static final long INDEX = CoordConversion.chunkIndex(0, 0);

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        return registered(env, null);
    }

    /**
     * Creates a registered instance with a loader in the environment of the test.
     *
     * @param env    the environment which provides the server process
     * @param loader the loader the instance reads from and reports removals to, null for none
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env, @Nullable ChunkLoader loader) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("publishes a chunk that was never loaded through a loader")
    void testPublishWithoutALoad(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final ChunkRegistry registry = instance.registry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);

        assertTrue(lifecycle.publish(INDEX, chunk, own));

        assertSame(chunk, instance.getChunk(0, 0));
        assertFalse(own.isDone(),
                "publishing does not hand the chunk to the waiting callers; completeLoad does, and that is the split");
        assertEquals(0, registry.loading(), "the position is no longer busy once its chunk is there");
    }

    @Test
    @DisplayName("refuses to publish a chunk whose position was claimed while it was being built")
    void testPublishIsRefusedAfterADiscard(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final ChunkRegistry registry = instance.registry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);

        lifecycle.discard(INDEX);

        assertFalse(lifecycle.publish(INDEX, chunk, own),
                "the position was claimed, so this chunk is not wanted any more");
        assertNull(instance.getChunk(0, 0));
        assertTrue(own.isCompletedExceptionally(),
                "the discard is what tells the callers waiting for that load; a chunk handed back"
                        + " after it was claimed looks usable and is not");
    }

    @Test
    @DisplayName("completes a load, runs the load hook of the chunk and fires the event exactly once")
    void testCompleteLoadDrivenDirectly(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        lifecycle.supplier(Announcing::new);
        final AtomicInteger events = new AtomicInteger();
        instance.eventNode().addListener(InstanceChunkLoadEvent.class, event -> events.incrementAndGet());
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);

        lifecycle.completeLoad(INDEX, 0, 0, ChunkLoader.noop(), own);

        final Chunk chunk = own.join();
        // Not isLoaded(): a chunk reports that from the moment it is constructed, so asserting it
        // here would pass against a completeLoad which never told the chunk anything at all.
        assertTrue(assertInstanceOf(Announcing.class, chunk).announced,
                "completeLoad is what runs the load hook of the chunk");
        assertSame(chunk, instance.getChunk(0, 0));
        assertEquals(1, events.get());
    }

    @Test
    @DisplayName("hands a discarded load its failure, unmarks the chunk and tells the current loader")
    void testCompleteLoadOnAClaimedPosition(Env env) {
        final Removals removals = new Removals();
        final FalcoInstance instance = registered(env, removals);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);
        lifecycle.discard(INDEX);
        final List<Chunk> produced = new ArrayList<>();

        // Deliberately not the loader of the instance: the chunk is read through the loader handed in
        // here, while the removal is reported to whichever loader is current when the publish is
        // refused. The two can differ and this case is where that is written down.
        lifecycle.completeLoad(INDEX, 0, 0, new ChunkLoader() {

            @Override
            public Chunk loadChunk(Instance owner, int chunkX, int chunkZ) {
                final FalcoChunk chunk = new FalcoChunk(owner, chunkX, chunkZ);
                produced.add(chunk);
                return chunk;
            }

            @Override
            public void saveChunk(Chunk chunk) {
            }
        }, own);

        final CompletionException thrown = assertThrows(CompletionException.class, own::join);
        assertSame(FalcoInstanceException.class, thrown.getCause().getClass(),
                "a chunk handed back after it was discarded looks usable and is not");
        assertNull(instance.getChunk(0, 0));
        assertFalse(produced.getFirst().isLoaded(),
                "a chunk which was refused has to stop reporting itself as loaded, or nothing will ever unload it");
        assertEquals(produced, removals.chunks,
                "the loader which is current when the publish is refused is the one that is told");
    }

    @Test
    @DisplayName("hands a failing loader back to the caller and gives up the slot")
    void testAFailingLoaderReleasesThePosition(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);

        lifecycle.completeLoad(INDEX, 0, 0, new ChunkLoader() {

            @Override
            public Chunk loadChunk(Instance owner, int chunkX, int chunkZ) {
                throw new IllegalStateException("the region file is a directory");
            }

            @Override
            public void saveChunk(Chunk chunk) {
            }
        }, own);

        assertThrows(CompletionException.class, own::join);
        assertEquals(0, instance.registry().loading(),
                "a failed load must not leave its position marked as busy forever");
    }

    @Test
    @DisplayName("creates a chunk through the supplier and refuses null")
    void testCreateUsesTheSupplier(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();

        assertSame(FalcoChunk.class, lifecycle.create(3, 4).getClass());

        lifecycle.supplier((owner, chunkX, chunkZ) -> null);
        assertThrows(FalcoInstanceException.class, () -> lifecycle.create(3, 4));
    }

    @Test
    @DisplayName("lets a foreign chunk type through creation and refuses it at the load")
    void testAForeignChunkTypeIsRefusedAtTheLoadRatherThanAtTheCreation(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        lifecycle.supplier(DynamicChunk::new);

        // Creation holds no opinion about the chunk type, because a caller which installed a
        // lifecycle through FalcoInstance#setChunkLifecycle may legitimately supply a foreign one.
        assertInstanceOf(DynamicChunk.class, lifecycle.create(3, 4));

        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);
        lifecycle.completeLoad(INDEX, 0, 0, ChunkLoader.noop(), own);

        final CompletionException thrown = assertThrows(CompletionException.class, own::join);
        assertSame(FalcoInstanceException.class, thrown.getCause().getClass());
        assertNull(instance.getChunk(0, 0), "a chunk this instance cannot unload must never be published");
    }

    @Test
    @DisplayName("unloads a chunk once and does nothing the second time")
    void testUnloadIsIdempotent(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final Chunk chunk = instance.loadChunk(0, 0).join();

        lifecycle.unload(chunk);
        lifecycle.unload(chunk);

        assertFalse(chunk.isLoaded());
        assertNull(instance.getChunk(0, 0));
    }

    /**
     * A chunk which writes down that its load hook was run.
     * <p>
     * {@code Chunk#onLoad()} sets no flag of its own — {@code isLoaded()} is true from construction —
     * so it is the only thing that can tell a completed load apart from one which put the chunk into
     * the registry and stopped there.
     * </p>
     */
    private static final class Announcing extends FalcoChunk {

        /**
         * Whether the load hook of this chunk was run.
         */
        private boolean announced;

        /**
         * Creates a chunk which has not been told anything yet.
         *
         * @param instance the instance the chunk belongs to
         * @param chunkX   the chunk X
         * @param chunkZ   the chunk Z
         */
        private Announcing(Instance instance, int chunkX, int chunkZ) {
            super(instance, chunkX, chunkZ);
        }

        @Override
        protected void onLoad() {
            this.announced = true;
        }
    }

    /**
     * A loader which writes down every chunk it was told about, in order.
     */
    private static final class Removals implements ChunkLoader {

        /**
         * The chunks this loader was told had left the instance.
         */
        private final List<Chunk> chunks = new ArrayList<>();

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public void unloadChunk(Chunk chunk) {
            this.chunks.add(chunk);
        }
    }
}
