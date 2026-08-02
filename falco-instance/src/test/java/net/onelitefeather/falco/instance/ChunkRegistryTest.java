package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives every transition of a chunk position directly, without a loader and without a load.
 * <p>
 * This is half of US-3.02. The transitions used to be three {@code private} methods of a class of
 * 1 272 lines and could only be reached by loading a chunk through a loader, which meant that a test
 * of the publish had to be a test of the whole load path and could never cover the case where a
 * publish is refused — that case needs an unload to interleave with a load, which is exactly what a
 * full load path makes impossible to arrange.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The registry of chunk positions")
class ChunkRegistryTest {

    /**
     * The position every case works on.
     */
    private static final long INDEX = CoordConversion.chunkIndex(0, 0);

    /**
     * Creates a registered instance to build chunks for.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("hands the first caller the slot and every later one the same future")
    void testTheFirstCallerOwnsTheSlot(Env env) {
        registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final CompletableFuture<Chunk> first = new CompletableFuture<>();
        final CompletableFuture<Chunk> second = new CompletableFuture<>();

        assertInstanceOf(ChunkRegistry.LoadSlot.Claimed.class, registry.acquire(INDEX, first));
        final ChunkRegistry.LoadSlot slot = registry.acquire(INDEX, second);

        assertSame(first, assertInstanceOf(ChunkRegistry.LoadSlot.Running.class, slot).future(),
                "the second caller has to receive the future of the first, not one of its own");
    }

    @Test
    @DisplayName("hands back the published chunk instead of a slot")
    void testAPublishedChunkEndsTheLoad(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        final AtomicInteger insideLock = new AtomicInteger();

        assertTrue(registry.publish(INDEX, chunk, own, published -> insideLock.incrementAndGet()));

        assertEquals(1, insideLock.get(), "the step handed in has to run exactly once, while the position is held");
        assertSame(chunk, registry.chunk(INDEX));
        assertEquals(0, registry.loading(), "a published chunk releases the slot of its position");
        assertSame(chunk, assertInstanceOf(ChunkRegistry.LoadSlot.Loaded.class,
                registry.acquire(INDEX, new CompletableFuture<>())).chunk());
    }

    @Test
    @DisplayName("refuses to publish a chunk whose load was claimed")
    void testAClaimedLoadCannotPublish(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        final AtomicInteger insideLock = new AtomicInteger();

        assertSame(own, registry.discard(INDEX));
        assertFalse(registry.publish(INDEX, chunk, own, published -> insideLock.incrementAndGet()));

        assertEquals(0, insideLock.get(), "a refused publish must not run the step it was given");
        assertNull(registry.chunk(INDEX), "a refused publish leaves the position empty");
    }

    @Test
    @DisplayName("removes a chunk once and reports the second attempt as a no-op")
    void testRemovingTwice(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        registry.publish(INDEX, chunk, own, published -> {
        });
        final AtomicInteger insideLock = new AtomicInteger();

        assertTrue(registry.remove(INDEX, chunk, removed -> insideLock.incrementAndGet()));
        assertFalse(registry.remove(INDEX, chunk, removed -> insideLock.incrementAndGet()));

        assertEquals(1, insideLock.get(), "the step handed in runs for the removal that happened and no other");
        assertTrue(registry.idle());
    }

    @Test
    @DisplayName("refuses to remove a chunk which is not the one at that position")
    void testRemovingAStrangerDoesNothing(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk resident = new FalcoChunk(instance, 0, 0);
        final FalcoChunk stranger = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        registry.publish(INDEX, resident, own, published -> {
        });

        assertFalse(registry.remove(INDEX, stranger, removed -> {
        }));
        assertSame(resident, registry.chunk(INDEX), "the chunk that is actually there has to survive");
    }

    @Test
    @DisplayName("hands out the loading positions so a shutdown can claim them")
    void testLoadingPositionsAreVisible(Env env) {
        registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        registry.acquire(CoordConversion.chunkIndex(1, 1), new CompletableFuture<>());
        registry.acquire(CoordConversion.chunkIndex(2, 2), new CompletableFuture<>());

        assertEquals(2, registry.loadingPositions().size());
        assertEquals(2, registry.loading());
        assertFalse(registry.idle());
    }
}
