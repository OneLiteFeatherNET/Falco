package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.instance.InstanceBlockUpdateEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the block writer of a Falco instance directly.
 * <p>
 * Two properties are asserted here that cannot be asserted through the instance: that a write into a
 * chunk which is handed in never consults the registry at all, and that the write lock of that chunk
 * is no longer held while the neighbour pass and the update event run. The second is what NFR-006 is
 * about and it used to be unobservable, because the only entry point took the lock, wrote, released
 * it and ran three more things, all inside one {@code private} method.
 * </p>
 * <p>
 * <b>The lock cases measure from inside the callbacks, not after the write returned.</b> A check
 * after {@code write} has returned proves nothing: the lock of a chunk is a
 * {@code ReentrantReadWriteLock}, so a writer which released it one line too late has still released
 * it by the time the caller looks, and the same thread could take it again either way. The two things
 * NFR-006 actually promises — that a rule reshaping a neighbour and a listener of
 * {@code InstanceBlockUpdateEvent} run with no chunk lock held — are only observable from within
 * those two, which is what {@code holdsWriteLock()} is read for here.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The block writer of a Falco instance")
class BlockWriterTest {

    /**
     * The height every case writes at.
     */
    private static final int Y = 64;

    /**
     * A handler which counts how often a block carrying it reached a chunk.
     * <p>
     * Counting the calls of {@code onPlace} rather than reading the block back is what makes the
     * recursion guard observable at all: the guard drops a second write of the same block to the same
     * position, and a dropped write leaves the chunk holding exactly what a performed write would have
     * left it holding.
     * </p>
     *
     * @param writes the counter raised once per write which reached the chunk
     */
    private record CountingHandler(AtomicInteger writes) implements BlockHandler {

        @Override
        public void onPlace(Placement placement) {
            this.writes.incrementAndGet();
        }

        @Override
        public Key getKey() {
            return Key.key("falco", "counting-writer");
        }
    }

    /**
     * Creates a registered instance in the environment of the test.
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
    @DisplayName("writes into the chunk it was handed, without asking where that chunk is")
    void testWriteIntoAChunkThatIsNotInTheRegistry(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk orphan = new FalcoChunk(instance, 9, 9);

        writer.write(orphan, 144, Y, 144, Block.STONE, null, null, false, 0);

        orphan.lockReadLock();
        try {
            assertEquals(Block.STONE, orphan.getBlock(144, Y, 144));
        } finally {
            orphan.unlockReadLock();
        }
        assertTrue(instance.getChunks().isEmpty(), "the writer must not have published anything");
    }

    @Test
    @DisplayName("lets a neighbour reshape itself with no write lock of the written chunk held")
    void testTheNeighbourPassRunsOutsideTheChunkLock(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = FalcoChunk.require(instance.loadChunk(0, 0).join());
        final AtomicInteger updates = new AtomicInteger();
        final AtomicBoolean lockHeld = new AtomicBoolean();
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new BlockPlacementRule(Block.GLASS) {

            @Override
            public Block blockUpdate(UpdateState state) {
                updates.incrementAndGet();
                lockHeld.set(chunk.holdsWriteLock());
                return Block.GLOWSTONE;
            }

            @Override
            public Block blockPlace(PlacementState state) {
                return state.block();
            }
        });
        instance.setBlock(2, Y, 1, Block.GLASS);

        writer.write(chunk, 1, Y, 1, Block.STONE, null, null, true, 0);

        assertTrue(updates.get() > 0, "the neighbour of the written block has to be asked to reshape itself");
        assertFalse(lockHeld.get(),
                "the neighbour pass may not run under the write lock of the chunk that was written; a "
                        + "neighbour in another chunk would take a second chunk lock while the first is held");
    }

    @Test
    @DisplayName("dispatches the block update event with no write lock of the written chunk held")
    void testTheUpdateEventRunsOutsideTheChunkLock(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = FalcoChunk.require(instance.loadChunk(0, 0).join());
        final AtomicInteger events = new AtomicInteger();
        final AtomicBoolean lockHeld = new AtomicBoolean();
        MinecraftServer.getGlobalEventHandler().addListener(InstanceBlockUpdateEvent.class, event -> {
            events.incrementAndGet();
            lockHeld.set(chunk.holdsWriteLock());
        });

        writer.write(chunk, 3, Y, 3, Block.STONE, null, null, false, 0);

        assertEquals(1, events.get(), "a write has to announce itself exactly once");
        assertFalse(lockHeld.get(),
                "a listener of the update event is arbitrary foreign code and may not run under a chunk lock");
    }

    @Test
    @DisplayName("refuses to write outside the world and says so instead of throwing")
    void testAWriteOutsideTheWorldIsRefused(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final long before = writer.lastChangeTime();

        assertDoesNotThrow(() -> writer.write(chunk, 0, 5000, 0, Block.STONE, null, null, false, 0),
                "a height outside the world is refused rather than thrown about");

        assertEquals(before, writer.lastChangeTime(),
                "a refused write may not reach the timestamp, which is inside the lock and past the check");
        chunk.lockReadLock();
        try {
            assertEquals(Block.AIR, chunk.getBlock(0, Y, 0), "nothing may have been written anywhere");
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    @DisplayName("moves its own timestamp when a block reaches a chunk")
    void testTheTimestampMoves(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final long before = writer.lastChangeTime();

        writer.write(chunk, 0, Y, 0, Block.STONE, null, null, false, 0);

        assertNotEquals(before, writer.lastChangeTime(),
                "a block write has to move the timestamp the batches read");
    }

    @Test
    @DisplayName("drops a second write of the same block to the same position")
    void testTheGuardDropsTheSecondWrite(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final AtomicInteger writes = new AtomicInteger();
        final Block counted = Block.STONE.withHandler(new CountingHandler(writes));

        writer.write(chunk, 0, Y, 0, counted, null, null, false, 0);
        writer.write(chunk, 0, Y, 0, counted, null, null, false, 0);

        assertEquals(1, writes.get(),
                "the same block at the same position reaches the chunk once between two end of ticks");
    }

    @Test
    @DisplayName("lets the same block be written again once its own tick ended")
    void testEndTickClearsTheGuard(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final AtomicInteger writes = new AtomicInteger();
        final Block counted = Block.STONE.withHandler(new CountingHandler(writes));

        writer.write(chunk, 0, Y, 0, counted, null, null, false, 0);
        writer.endTick();
        writer.write(chunk, 0, Y, 0, counted, null, null, false, 0);

        assertEquals(2, writes.get(),
                "the guard is scoped to one tick, so the same block can be written again afterwards");
    }
}
