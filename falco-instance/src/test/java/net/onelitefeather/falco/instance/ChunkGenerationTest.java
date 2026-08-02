package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Drives the generator side of a Falco instance without the instance.
 * <p>
 * The fork bookkeeping used to be a {@code private} map of {@code FalcoInstance} and could only be
 * observed through the world it eventually produced, which made a test of it a test of the whole load
 * path. Here the map has a size that can be read, so the case that mattered — a fork for a chunk
 * nobody ever asks for — is assertable instead of inferable.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The generator side of a Falco instance")
class ChunkGenerationTest {

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
    @DisplayName("has no generator until it is given one")
    void testTheGeneratorIsHandedBack(Env env) {
        registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final Generator generator = unit -> unit.modifier().fillHeight(0, 16, Block.STONE);

        assertNull(generation.generator());
        generation.generator(generator);
        assertSame(generator, generation.generator());
    }

    @Test
    @DisplayName("writes what the generator produced into the chunk it was asked about")
    void testAGeneratedChunkCarriesItsBlocks(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        generation.apply(chunk, unit -> unit.modifier().fillHeight(0, 16, Block.STONE));

        chunk.lockReadLock();
        try {
            assertEquals(Block.STONE, chunk.getBlock(0, 0, 0));
            assertEquals(Block.AIR, chunk.getBlock(0, 32, 0));
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    @DisplayName("keeps a fork for a chunk which does not exist and delivers it when it does")
    void testAPendingForkIsKeptAndDelivered(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        generation.apply(chunk, unit -> unit.fork(setter ->
                setter.setBlock(new Vec(20, 0, 0), Block.STONE)));

        assertEquals(1, generation.pendingForks(),
                "the fork landed in the chunk at 1:0, which does not exist, so it has to be remembered");

        final FalcoChunk neighbour = new FalcoChunk(instance, 1, 0);
        generation.applyPending(neighbour);

        assertEquals(0, generation.pendingForks(), "delivering a fork has to take it off the list");
        neighbour.lockReadLock();
        try {
            assertEquals(Block.STONE, neighbour.getBlock(20, 0, 0));
        } finally {
            neighbour.unlockReadLock();
        }
    }

    @Test
    @DisplayName("drops every pending fork when it is told to")
    void testPendingForksCanBeDropped(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        generation.apply(chunk, unit -> unit.fork(setter ->
                setter.setBlock(new Vec(20, 0, 0), Block.STONE)));

        generation.clearPending();

        assertEquals(0, generation.pendingForks(),
                "a fork whose target chunk is never requested waits forever, so a shutdown has to drop it");
    }
}
