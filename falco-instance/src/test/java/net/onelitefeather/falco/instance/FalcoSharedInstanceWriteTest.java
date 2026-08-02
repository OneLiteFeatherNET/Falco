package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the wall of stage four: a Falco shared instance does not own its writes.
 * <p>
 * The block owner is the container, its {@code UNSAFE_setBlock} is private and synchronised on the
 * instance, and it is reached from four places of which {@code setBlock} is only one. Overriding
 * {@code setBlock} here would leave the other three on the private path and create two write paths
 * over one chunk, one of them unsynchronised. This class asserts the consequences of not doing that,
 * so that the documentation which states them cannot quietly stop being true.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("Writing through a Falco shared instance")
class FalcoSharedInstanceWriteTest {

    private static FalcoSharedInstance registered(Env env, InstanceContainer container) {
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        return shared;
    }

    @Test
    @DisplayName("shows the same chunk object as the container and as its siblings")
    void testTheChunkIsTheContainersChunk(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);

        final Chunk chunk = container.loadChunk(0, 0).join();

        assertSame(chunk, first.getChunk(0, 0));
        assertSame(chunk, second.getChunk(0, 0));
    }

    @Test
    @DisplayName("lands in the container, where every other view reads it")
    void testAWriteReachesEveryView(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        container.setChunkSupplier(FalcoChunk::new);
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);
        container.loadChunk(0, 0).join();

        first.setBlock(1, 40, 2, Block.DIAMOND_BLOCK);

        assertEquals(Block.DIAMOND_BLOCK, first.getBlock(1, 40, 2));
        assertEquals(Block.DIAMOND_BLOCK, second.getBlock(1, 40, 2));
        assertEquals(Block.DIAMOND_BLOCK, container.getBlock(1, 40, 2));
    }

    @Test
    @DisplayName("still auto-loads on write when the container does, whatever the view was told")
    void testTheViewFlagDoesNotReachTheWritePath(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        container.setChunkSupplier(FalcoChunk::new);
        final FalcoSharedInstance shared = registered(env, container);

        shared.enableAutoChunkLoad(false);
        shared.setBlock(20, 40, 20, Block.STONE);

        assertNotNull(container.getChunk(1, 1),
                "setBlock belongs to the container and asks the container's flag; this is the wall, not a defect");
        assertEquals(Block.STONE, container.getBlock(20, 40, 20));
    }
}
