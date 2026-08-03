package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("A Falco chunk owned by a plain InstanceContainer")
class FalcoChunkInContainerTest {

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
    }

    @Test
    @DisplayName("is created by the container, survives a write and unloads cleanly")
    void testContainerOwnsTheChunk() {
        final InstanceContainer container = MinecraftServer.getInstanceManager().createInstanceContainer();

        container.setChunkSupplier(FalcoChunk::new);

        final Chunk chunk = container.loadChunk(0, 0).join();

        assertInstanceOf(FalcoChunk.class, chunk, "the container has to use the supplier it was given");

        container.setBlock(0, 0, 0, Block.STONE);
        assertEquals(Block.STONE, container.getBlock(0, 0, 0));

        container.unloadChunk(chunk);
        assertFalse(chunk.isLoaded(), "the container reaches the protected unload hook itself");

        MinecraftServer.getInstanceManager().unregisterInstance(container);
    }
}
