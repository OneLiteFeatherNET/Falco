package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the chunk of this module.
 * <p>
 * The lifecycle hooks of a Minestom chunk are {@code protected} and therefore unreachable from any
 * package but Minestom's own. That is the whole reason a chunk implementation lives here at all, so
 * the cases below check that the hooks can be driven from outside and that the type survives the
 * operations Minestom performs on a chunk.
 * </p>
 *
 * <p>
 * The heightmap cases are here for a different reason: they are the only place where the claim that
 * a chunk builds a heightmap when it is asked and not before can be observed at all.
 * </p>
 *
 * <p>
 * The ticking cases are here for a third reason: they characterise what a tick reaches, so that the
 * bookkeeping which decides that can be replaced without the replacement being free to change the
 * answer.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.3.1
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoChunkTest {

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
    void testAFreshChunkIsLoaded(Env env) {
        final FalcoChunk chunk = new FalcoChunk(registered(env), 0, 0);

        assertTrue(chunk.isLoaded());
    }

    @Test
    void testMarkUnloadedReachesTheProtectedHook(Env env) {
        final FalcoChunk chunk = new FalcoChunk(registered(env), 0, 0);

        chunk.markUnloaded();

        assertFalse(chunk.isLoaded());
    }

    @Test
    void testMarkLoadedIsCallableFromOutsideTheMinestomPackage(Env env) {
        final FalcoChunk chunk = new FalcoChunk(registered(env), 0, 0);

        chunk.markLoaded();

        assertTrue(chunk.isLoaded());
    }

    @Test
    void testACopyIsAFalcoChunkAgain(Env env) {
        final FalcoInstance instance = registered(env);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        chunk.lockWriteLock();
        try {
            chunk.setBlock(1, 40, 2, Block.DIAMOND_BLOCK);
        } finally {
            chunk.unlockWriteLock();
        }

        final Chunk copy;
        chunk.lockReadLock();
        try {
            copy = chunk.copy(instance, 5, 6);
        } finally {
            chunk.unlockReadLock();
        }

        assertInstanceOf(FalcoChunk.class, copy);
        assertNotSame(chunk, copy);
        assertEquals(5, copy.getChunkX());
        assertEquals(6, copy.getChunkZ());
        copy.lockReadLock();
        try {
            assertEquals(Block.DIAMOND_BLOCK, copy.getBlock(1, 40, 2));
        } finally {
            copy.unlockReadLock();
        }
    }

    /**
     * States the property the on-demand heightmaps exist for, in the only way it can be stated.
     * <p>
     * A heightmap is a {@code short[256]} plus its carrier, and the two of them together weigh
     * {@code 1 120} bytes — more than the {@code 840} a fresh {@link FalcoChunk} retains in total
     * now that it builds neither until it is asked. In a fresh {@code DynamicChunk}, whose sections
     * are real, the same {@code 1 120} bytes are the second largest post after those sections. The
     * conditions those figures were measured under are on {@link FalcoChunk#motionBlockingHeightmap()},
     * and the measurement itself is {@code ChunkFootprintTest}, not this case: nothing here weighs
     * anything. The claim this case makes is not that heightmaps are cheaper but that a chunk
     * which is only constructed and read has none, so the case walks exactly that path: construct,
     * read one block, and only then ask. The read is in the middle rather than at the end because a
     * block read is what a chunk loader and a light computation do, and it is the path on which the
     * saving is supposed to survive.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("builds no heightmap until something asks for one")
    void testHeightmapsAreBuiltOnDemand(Env env) {
        final FalcoChunk chunk = new FalcoChunk(registered(env), 0, 0);

        assertFalse(chunk.hasHeightmaps(), "a chunk that was only constructed needs no heightmap");

        chunk.lockReadLock();
        try {
            chunk.getBlock(0, 0, 0);
        } finally {
            chunk.unlockReadLock();
        }
        assertFalse(chunk.hasHeightmaps(), "a block read does not need a heightmap either");

        assertNotNull(chunk.motionBlockingHeightmap());
        assertTrue(chunk.hasHeightmaps());
    }

    /**
     * Holds the double-checked lock to its second half.
     * <p>
     * The first half — that nothing is built too early — is the case above. This one is the other
     * direction: an accessor which built a heightmap and forgot to store it would satisfy every
     * caller and still be wrong, because the heights a chunk accumulated through
     * {@code Heightmap#refresh(int, int, int, Block)} would be thrown away on the next call and the
     * chunk would answer from a map that was never refreshed.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("hands out the same heightmap on every call")
    void testTheHeightmapIsBuiltOnce(Env env) {
        final FalcoChunk chunk = new FalcoChunk(registered(env), 0, 0);

        assertSame(chunk.motionBlockingHeightmap(), chunk.motionBlockingHeightmap());
        assertSame(chunk.worldSurfaceHeightmap(), chunk.worldSurfaceHeightmap());
        assertNotSame(chunk.motionBlockingHeightmap(), chunk.worldSurfaceHeightmap());
    }

    /**
     * Builds a handler which counts the ticks it receives without ever asking for one.
     * <p>
     * The counterpart of {@link #tickingHandler(AtomicInteger)}, and the only way to observe a tick
     * that should not have happened. It counts in {@code tick} precisely because it must never be
     * called, so a chunk which ticks it leaves the evidence itself.
     * </p>
     *
     * @param ticks the counter which is incremented once per tick, and which must stay at zero
     * @return a handler which is not tickable
     */
    private static BlockHandler quietHandler(AtomicInteger ticks) {
        return new BlockHandler() {

            @Override
            public Key getKey() {
                return Key.key("falco", "quiet");
            }

            @Override
            public void tick(Tick tick) {
                ticks.incrementAndGet();
            }
        };
    }

    /**
     * Builds a handler which asks to be ticked and counts the ticks it receives.
     * <p>
     * A counting handler is the only way the cases below can observe ticking at all: whether a chunk
     * ticks a block is not readable from the chunk, it is only visible in whether the handler of that
     * block ran.
     * </p>
     *
     * @param ticks the counter which is incremented once per tick
     * @return a tickable handler
     */
    private static BlockHandler tickingHandler(AtomicInteger ticks) {
        return new BlockHandler() {

            @Override
            public Key getKey() {
                return Key.key("falco", "tickable");
            }

            @Override
            public boolean isTickable() {
                return true;
            }

            @Override
            public void tick(Tick tick) {
                ticks.incrementAndGet();
            }
        };
    }

    /**
     * Pins which blocks a tick reaches, in both directions.
     * <p>
     * This is a characterisation case rather than a red one: the behaviour already exists, and it is
     * written down so that the bookkeeping behind it can be replaced without the replacement being
     * able to change what a tick does. The second half is the half that can actually break — a chunk
     * which only ever learns that a block became tickable, and never that one stopped being tickable,
     * passes the first assertion and keeps ticking a block that is no longer there.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("ticks a tickable handler and stops ticking it when it is replaced")
    void testTickReachesOnlyTickableBlocks(Env env) {
        final FalcoInstance instance = registered(env);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final AtomicInteger ticks = new AtomicInteger();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE.withHandler(tickingHandler(ticks)));
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0L);
        assertEquals(1, ticks.get());

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0L);
        assertEquals(1, ticks.get(), "a block that was replaced must stop being ticked");
    }

    /**
     * Pins that a copy of a chunk keeps ticking what the original ticked.
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("carries the tickable blocks of a chunk into its copy")
    void testCopyKeepsTicking(Env env) {
        final FalcoInstance instance = registered(env);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final AtomicInteger ticks = new AtomicInteger();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE.withHandler(tickingHandler(ticks)));
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.lockReadLock();
        final Chunk copy;
        try {
            copy = chunk.copy(instance, 1, 1);
        } finally {
            chunk.unlockReadLock();
        }
        copy.tick(0L);
        assertEquals(1, ticks.get(),
                "DynamicChunk#copy carries only the entries, which stops a copied chunk from ticking; "
                        + "that omission was corrected before the storage moved and stays corrected");
    }

    /**
     * Holds the tickable counter to being a count and not a flag.
     * <p>
     * The two cases above cannot do it. Since the tick walks the entries and skips what is not
     * tickable, a counter that is merely too high changes nothing a caller can observe — it only
     * costs a walk. The direction that is observable is a counter that is too low: it makes the tick
     * take its early exit while a tickable block is still there, and that block silently stops
     * ticking. That is the failure this case exists for, and it is the failure the map this counter
     * replaced could not have.
     * </p>
     * <p>
     * Two blocks, because one is not enough to tell a count from a flag: a chunk which stores whether
     * it has any tickable block rather than how many gets the first block right and drops the second
     * the moment the first one leaves.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("keeps ticking the tickable blocks that are left when one of them leaves")
    void testOneBlockLeavingDoesNotSilenceTheOthers(Env env) {
        final FalcoInstance instance = registered(env);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger second = new AtomicInteger();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE.withHandler(tickingHandler(first)));
            chunk.setBlock(1, 0, 0, Block.STONE.withHandler(tickingHandler(second)));
            chunk.setBlock(0, 0, 0, Block.STONE);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0L);

        assertEquals(0, first.get(), "the block that was replaced must not tick");
        assertEquals(1, second.get(), "the block that stayed must still tick");
    }

    /**
     * Holds the counter to never going below the truth on the way up.
     * <p>
     * A write which was never tickable and is still not tickable must leave the counter alone. One
     * that pays a decrement for every such write drives the counter negative on an ordinary chunk
     * full of ordinary blocks, and the tickable block placed afterwards is then paid for out of that
     * debt instead of lifting the counter off zero — the chunk takes its early exit and the block
     * never ticks.
     * </p>
     * <p>
     * Exactly one plain write, and not two, because the early exit tests for zero rather than for a
     * non-positive count: after two plain writes such a counter would sit at {@code -1} and the tick
     * would still walk, so the defect would pass unnoticed. One plain write followed by one tickable
     * write is the arrangement that lands the counter back on zero with a tickable block in the
     * chunk, and it is the only arrangement in which this defect is visible from the outside at all.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("ticks a tickable block placed after a plain one")
    void testPlainWritesDoNotOwePastTheirTurn(Env env) {
        final FalcoInstance instance = registered(env);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final AtomicInteger ticks = new AtomicInteger();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE);
            chunk.setBlock(1, 0, 0, Block.STONE.withHandler(tickingHandler(ticks)));
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0L);

        assertEquals(1, ticks.get(), "a tickable block must tick regardless of what was written before it");
    }

    /**
     * Holds the tick to the blocks which asked for it.
     * <p>
     * This is the case the second map used to make impossible. That map held only tickable blocks, so
     * walking it could not reach anything else; the walk now goes over the entries, which hold every
     * block worth keeping as an object, and the only thing between a block entity that never asked to
     * be ticked and a tick is the filter inside the loop. A filter is easier to lose than a map is,
     * so what it does is written down here rather than left to the shape of the data.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("does not tick a handler which did not ask to be ticked")
    void testTickSkipsHandlersThatAreNotTickable(Env env) {
        final FalcoInstance instance = registered(env);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final AtomicInteger tickable = new AtomicInteger();
        final AtomicInteger quiet = new AtomicInteger();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE.withHandler(tickingHandler(tickable)));
            chunk.setBlock(1, 0, 0, Block.STONE.withHandler(quietHandler(quiet)));
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0L);

        assertEquals(1, tickable.get(), "the handler which asked to be ticked must be ticked");
        assertEquals(0, quiet.get(), "a handler which is not tickable must not be ticked");
    }
}
