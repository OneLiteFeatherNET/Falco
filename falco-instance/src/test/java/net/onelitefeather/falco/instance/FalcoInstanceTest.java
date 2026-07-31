package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a {@link FalcoInstance} behaves like an instance of a running server: it registers,
 * loads chunks, carries blocks and is ticked by the server.
 * <p>
 * Every case here runs against a real server process rather than a mock, because the point of the
 * type is that Minestom accepts it as an instance. A mock would prove nothing about that.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoInstanceTest {

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
    void testAnInstanceRegistersWithTheInstanceManager(Env env) {
        final InstanceManager manager = env.process().instance();
        final FalcoInstance instance = registered(env);

        assertTrue(instance.isRegistered());
        assertTrue(manager.getInstances().contains(instance));
        assertSame(instance, manager.getInstance(instance.getUuid()));
    }

    @Test
    void testLoadChunkProducesAFalcoChunk(Env env) {
        final FalcoInstance instance = registered(env);

        final Chunk chunk = instance.loadChunk(0, 0).join();

        assertInstanceOf(FalcoChunk.class, chunk);
        assertTrue(chunk.isLoaded());
        assertSame(instance, chunk.getInstance());
    }

    @Test
    void testLoadChunkTwiceHandsBackTheSameChunk(Env env) {
        final FalcoInstance instance = registered(env);

        final Chunk first = instance.loadChunk(3, -4).join();
        final Chunk second = instance.loadChunk(3, -4).join();

        assertSame(first, second);
        assertEquals(1, instance.getChunks().size());
    }

    @Test
    void testGetChunkIsNullBeforeTheChunkIsLoaded(Env env) {
        final FalcoInstance instance = registered(env);

        assertNull(instance.getChunk(0, 0));

        instance.loadChunk(0, 0).join();

        assertNotNull(instance.getChunk(0, 0));
    }

    @Test
    void testSetBlockIsReadBackFromTheInstance(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();

        instance.setBlock(1, 40, 2, Block.DIAMOND_BLOCK);

        assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(1, 40, 2));
    }

    @Test
    void testSetBlockLoadsTheChunkWhenAutoLoadIsEnabled(Env env) {
        final FalcoInstance instance = registered(env);

        instance.setBlock(20, 40, 20, Block.STONE);

        assertNotNull(instance.getChunk(1, 1));
        assertEquals(Block.STONE, instance.getBlock(20, 40, 20));
    }

    @Test
    void testSetBlockFailsOnAnUnloadedChunkWithoutAutoLoad(Env env) {
        final FalcoInstance instance = registered(env);
        instance.enableAutoChunkLoad(false);

        assertFalse(instance.hasEnabledAutoChunkLoad());
        assertThrows(IllegalStateException.class, () -> instance.setBlock(0, 40, 0, Block.STONE));
    }

    @Test
    void testLoadOptionalChunkStaysEmptyWithoutAutoLoad(Env env) {
        final FalcoInstance instance = registered(env);
        instance.enableAutoChunkLoad(false);

        assertNull(instance.loadOptionalChunk(0, 0).join());
    }

    @Test
    void testTheServerTicksTheInstance(Env env) {
        final FalcoInstance instance = registered(env);
        final long before = instance.getWorldAge();

        env.tick();

        assertEquals(before + 1, instance.getWorldAge());
    }

    @Test
    void testAGeneratorIsRejectedInsteadOfBeingIgnored(Env env) {
        final FalcoInstance instance = registered(env);

        assertThrows(FalcoInstanceException.class, () -> instance.setGenerator(unit -> {
        }));
        assertThrows(FalcoInstanceException.class, () -> instance.generateChunk(0, 0, unit -> {
        }));
        assertNull(instance.generator());
    }

    @Test
    void testAForeignChunkSupplierIsRejected(Env env) {
        final FalcoInstance instance = registered(env);
        instance.setChunkSupplier(DynamicChunk::new);

        final CompletionException failure = assertThrows(CompletionException.class,
                () -> instance.loadChunk(0, 0).join());

        assertInstanceOf(FalcoInstanceException.class, failure.getCause());
        assertNull(instance.getChunk(0, 0));
    }

    @Test
    void testAPlayerBecomesAViewerOfTheChunksAroundIt(Env env) {
        final FalcoInstance instance = registered(env);
        final Chunk chunk = instance.loadChunk(0, 0).join();

        final Player player = env.createPlayer(instance, new Pos(0, 40, 0));

        // The Chunk constructor asks `instance instanceof InstanceContainer` for its shared
        // instances and gets an empty list here. That only drops SharedInstance viewers, which this
        // instance has none of, so the players of the instance itself still see their chunks.
        assertSame(instance, player.getInstance());
        assertTrue(chunk.getViewers().contains(player));
    }

    @Test
    void testTheVoidStartsBelowTheDimension(Env env) {
        final FalcoInstance instance = registered(env);
        final int minY = instance.getCachedDimensionType().minY();

        assertFalse(instance.isInVoid(new Pos(0, minY, 0)));
        assertTrue(instance.isInVoid(new Pos(0, minY - 65, 0)));
    }
}
