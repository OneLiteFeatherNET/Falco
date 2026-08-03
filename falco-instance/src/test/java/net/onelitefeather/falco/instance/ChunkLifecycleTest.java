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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * <p>
 * Three cases hand the load path a listener which throws, one per arm. They exist because stage 3 put
 * arbitrary third-party code between the chunk being ready and its future being completed, and a
 * throw out of that stretch used to leave the future uncompleted — which is a hang rather than a
 * failure, and a hang no assertion of this class would have noticed. Each of them therefore asserts
 * both halves: that the caller is told, and that the throw still leaves the load path.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
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
    @DisplayName("fails the load rather than hanging it when the publish listener throws")
    void testAThrowingPublishListenerFailsTheLoad(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final IllegalStateException refusal = new IllegalStateException("this scheduler already serves another instance");
        lifecycle.addListener(new ChunkLifecycleListener() {

            @Override
            public void onPublish(ChunkLifecycleEvent event) {
                throw refusal;
            }
        });
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);

        assertSame(refusal, assertThrows(IllegalStateException.class,
                        () -> lifecycle.completeLoad(INDEX, 0, 0, ChunkLoader.noop(), own)),
                "the throw is a defect of the listener and keeps going, exactly as it did before");

        // Asked before it is joined, and this order is the point of the case: completeLoad ran on
        // this thread, so a future which is not done here is never going to be, and a join would be
        // the very wait for the life of the process this case is about rather than a failure.
        assertTrue(own.isCompletedExceptionally(),
                "every caller waiting on this position has to be told; an uncompleted future is not an"
                        + " error anybody can see, it is a wait for the life of the process");
        assertSame(refusal, assertThrows(CompletionException.class, own::join).getCause());
        assertNotNull(instance.getChunk(0, 0),
                "the chunk entered the registry before the listener ran and the catch does not undo that");
    }

    @Test
    @DisplayName("fails the load rather than hanging it when the load listener throws")
    void testAThrowingLoadListenerFailsTheLoad(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final IllegalStateException refusal = new IllegalStateException("this scheduler already serves another instance");
        lifecycle.addListener(new ChunkLifecycleListener() {

            @Override
            public void onLoad(ChunkLifecycleEvent event) {
                throw refusal;
            }
        });
        final AtomicInteger events = new AtomicInteger();
        instance.eventNode().addListener(InstanceChunkLoadEvent.class, event -> events.incrementAndGet());
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);

        assertSame(refusal, assertThrows(IllegalStateException.class,
                () -> lifecycle.completeLoad(INDEX, 0, 0, ChunkLoader.noop(), own)));

        assertTrue(own.isCompletedExceptionally(),
                "the second arm of the load has the same hole as the first and is closed the same way");
        assertSame(refusal, assertThrows(CompletionException.class, own::join).getCause());
        assertEquals(0, events.get(),
                "the load never finished, so nothing may have been told that it did");
    }

    @Test
    @DisplayName("tells the loader about a discarded chunk even when the unload listener throws")
    void testAThrowingUnloadListenerStillReleasesTheDiscardedChunk(Env env) {
        final Removals removals = new Removals();
        final FalcoInstance instance = registered(env, removals);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        lifecycle.addListener(new ChunkLifecycleListener() {

            @Override
            public void onUnload(ChunkLifecycleEvent event) {
                throw new IllegalStateException("the listener of this chunk refuses to be torn down");
            }
        });
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        // The position belongs to somebody else's load, which is what a discard followed by a new
        // request leaves behind. A plain discard would complete `own` itself and hide the question
        // this case asks: on this arm the refusal below is the only completion there is.
        instance.registry().acquire(INDEX, new CompletableFuture<>());

        assertThrows(IllegalStateException.class,
                () -> lifecycle.completeLoad(INDEX, 0, 0, ChunkLoader.noop(), own));

        assertTrue(own.isCompletedExceptionally(),
                "nobody else is going to complete this future, so the refused arm has to");
        final CompletionException thrown = assertThrows(CompletionException.class, own::join);
        assertSame(FalcoInstanceException.class, thrown.getCause().getClass(),
                "the callers are told that their load was discarded before the chunk is told anything");
        assertEquals(1, removals.chunks.size(),
                "the loader may hold bookkeeping for a chunk it never handed out, and a listener which"
                        + " throws on the way out must not turn that into a leak");
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
