package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 * @author TheMeinerLP
 * @version 1.0.0
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
}
