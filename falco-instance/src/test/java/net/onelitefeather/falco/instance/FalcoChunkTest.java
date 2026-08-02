package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

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
 * @author TheMeinerLP
 * @version 1.1.1
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
     * A heightmap is a {@code short[256]} plus its carrier, and the two of them together were the
     * largest post a fresh {@link FalcoChunk} had while it built them eagerly — {@code 1 120} of
     * {@code 2 088} bytes; in a fresh {@code DynamicChunk}, whose sections are real, the same
     * {@code 1 120} bytes are the second largest post after those sections. The conditions those
     * figures were measured under are on {@link FalcoChunk#motionBlockingHeightmap()}, and the
     * measurement itself is {@code ChunkFootprintTest}, not this case: nothing here weighs anything.
     * The claim this case makes is not that heightmaps are cheaper but that a chunk
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
}
