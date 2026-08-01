package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.batch.AbsoluteBlockBatch;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the promise of the whole design: one line of setup and the light stays current.
 * <p>
 * The tests here deliberately go through the public entry points a consumer would use — a chunk
 * supplier on the instance, a block placed through the chunk, a tick — rather than calling the
 * scheduler directly. If any of the wiring between the three types comes loose, this is where it
 * shows.
 * </p>
 * <p>
 * Two of them exist because of specific traps in Minestom. A freshly constructed
 * {@code LightingChunk} reports {@code isLoaded() == false} until its {@code protected onLoad()}
 * runs, and both batch types begin by returning with a warning for an unloaded chunk; this chunk
 * must not inherit or rebuild that. And {@code AbsoluteBlockBatch#apply} resends light only for a
 * {@code LightingChunk}, so a batch has to be covered here rather than assumed to work.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoLightingChunkTest {

    /**
     * The time the batch is waited for before the test is considered stuck.
     */
    private static final long AWAIT_SECONDS = 30L;

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

    /**
     * The lifecycle hooks of the chunk are reachable from outside.
     * <p>
     * {@code Chunk#onLoad()} and {@code Chunk#unload()} are {@code protected}, so an instance
     * implementation in another module cannot drive them — which is what keeps a lighting chunk out
     * of {@code FalcoInstance} today. These two methods are the reachable form, word for word what
     * {@code FalcoChunk} already offers.
     * </p>
     */
    @Test
    void testTheLifecycleHooksAreReachableFromOutside(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), Runnable::run, 16);
        FalcoLightingChunk chunk = new FalcoLightingChunk(scheduler, instance, 0, 0);

        assertFalse(scheduler.isDirty(0, 0), "nothing has reported this chunk yet");

        chunk.markLoaded();

        assertTrue(scheduler.isDirty(0, 0),
                "markLoaded drives the protected onLoad hook, which reports the chunk to the scheduler");

        chunk.markUnloaded();

        assertFalse(chunk.isLoaded(),
                "markUnloaded clears the flag every isLoaded check in Minestom reads");
    }

    /**
     * A copy carries the light it had, and does not carry the scheduler it came from.
     * <p>
     * Minestom calls {@code Chunk#copy} when a chunk is copied into another instance. A copy that
     * kept the binding would report its changes to a scheduler that already serves the origin, and
     * a scheduler rejects a second instance — the copy would take the first block change placed in
     * it and turn it into an {@link IllegalStateException}. The inherited implementation is
     * therefore the correct one here, unlike in {@code FalcoChunk}, where the copy has to keep its
     * type so the instance can unload it.
     * </p>
     * <p>
     * What the copy does keep is the light data itself, because the sections are cloned with it.
     * It is a snapshot: nothing updates it afterwards.
     * </p>
     */
    @Test
    void testACopyKeepsItsLightButNotTheSchedulerItCameFrom(Env env) {
        Instance origin = env.createEmptyInstance();
        Instance other = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, Runnable::run, 16);
        origin.setChunkSupplier(scheduler.supplier());

        Chunk chunk = origin.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);
        chunk.tick(1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8), "the origin is lit before it is copied");

        chunk.lockReadLock();
        final Chunk copy;
        try {
            copy = chunk.copy(other, 0, 0);
        } finally {
            chunk.unlockReadLock();
        }

        assertEquals(15, service.blockLightAt(copy, 8, 40, 8), "the copy carries the light it was made from");

        // If the copy were bound to the scheduler of the origin, this would bind a second instance
        // and throw rather than place a block.
        place(copy, 1, 40, 1, Block.STONE);

        assertFalse(scheduler.isDirty(0, 0), "a change in the copy is not a change of the origin");
    }

    @Test
    void testPlacingALightSourceLightsTheChunkAfterATick(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, Runnable::run, 16);
        instance.setChunkSupplier(scheduler.supplier());

        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);
        chunk.tick(1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8));
    }

    @Test
    void testTheSupplierProducesFalcoLightingChunks(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.setChunkSupplier(new ChunkLightScheduler(new ChunkLightService(), Runnable::run, 16).supplier());

        assertInstanceOf(FalcoLightingChunk.class, instance.loadChunk(0, 0).join());
    }

    @Test
    void testAFreshlyLoadedChunkIsLitWithoutABlockChange(Env env) {
        // onLoad has to report the chunk dirty, otherwise a world that is only ever read stays
        // black no matter how many ticks pass.
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, Runnable::run, 16);
        instance.setChunkSupplier(scheduler.supplier());

        Chunk chunk = instance.loadChunk(0, 0).join();

        assertTrue(scheduler.isDirty(0, 0), "a freshly loaded chunk has to ask for its light");

        chunk.tick(1L);

        chunk.lockReadLock();
        try {
            assertEquals(15, chunk.getSectionAt(40).skyLight().getLevel(8, 40 & 15, 8),
                    "the sky light of an open column arrives without anyone asking for it");
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    void testAFreshChunkReportsItselfLoaded(Env env) {
        // LightingChunk answers false here until its protected onLoad has run, which makes both
        // batch types abort with a warning about an unloaded chunk. This chunk must not do that.
        Instance instance = env.createEmptyInstance();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), Runnable::run, 16);

        Chunk fresh = scheduler.supplier().createChunk(instance, 0, 0);

        assertTrue(fresh.isLoaded(), "a chunk that was never handed to onLoad still has to accept a batch");
    }

    @Test
    void testABatchLightsEveryChunkItTouched(Env env) throws InterruptedException {
        // AbsoluteBlockBatch resends light only for a LightingChunk, so this chunk has to close the
        // gap itself: the batch writes through setBlock, which marks the chunk dirty, and the next
        // tick computes and delivers the result.
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, Runnable::run, 16);
        instance.setChunkSupplier(scheduler.supplier());

        Chunk left = instance.loadChunk(0, 0).join();
        Chunk right = instance.loadChunk(1, 0).join();
        left.tick(1L);

        AbsoluteBlockBatch batch = new AbsoluteBlockBatch();
        // World position 15/40/8, on the border between the two chunks.
        batch.setBlock(15, 40, 8, Block.GLOWSTONE);
        batch.apply(instance, null);

        // The batch runs on its own pool and delivers its callback only on the next instance tick,
        // so the block itself is what this waits for. Its arrival is also the property under test:
        // a batch reaches this chunk through setBlock and therefore marks it dirty.
        assertTrue(await(() -> scheduler.isDirty(0, 0)), "the batch never marked the chunk dirty");

        left.tick(2L);

        assertEquals(15, service.blockLightAt(left, 15, 40, 8), "the batch has to have marked its chunk dirty");
        assertEquals(14, service.blockLightAt(right, 0, 40, 8), "and the neighbour has to be lit as part of the same area");
    }

    /**
     * Waits for the given condition, on a platform thread, and reports whether it came true.
     *
     * @param condition the condition to wait for
     * @return true if the condition came true in time, otherwise false
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(1L);
        }
        return condition.getAsBoolean();
    }

    @Test
    void testARemovedLightSourceGoesDarkAgain(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, Runnable::run, 16);
        instance.setChunkSupplier(scheduler.supplier());

        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);
        chunk.tick(1L);

        place(chunk, 8, 40, 8, Block.AIR);
        chunk.tick(2L);

        assertEquals(0, service.blockLightAt(chunk, 9, 40, 8), "the light of a removed source has to be retracted");
    }

    @Test
    void testTheChunkTellsTheSchedulerAboutEveryTickOnce(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, Runnable::run, 16);
        instance.setChunkSupplier(scheduler.supplier());

        Chunk chunk = instance.loadChunk(0, 0).join();
        chunk.tick(1L);
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        assertTrue(scheduler.isDirty(0, 0), "a block change has to report the chunk dirty");

        chunk.tick(2L);

        assertFalse(scheduler.isDirty(0, 0), "and the following tick has to clear it again");
    }
}
