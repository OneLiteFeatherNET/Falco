package net.minestom.server.instance;

import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Establishes that a chunk which is unloaded takes its viewer cache entry with it, which is US-3.01.
 * <p>
 * The entry is created by the constructor of {@code Chunk} ({@code Chunk.java:74-76}), which asks the
 * entity tracker of the instance for a viewable and gets one out of a
 * {@code computeIfAbsent}. Nothing in Minestom ever removes it: not unloading the chunk, not dropping
 * the last reference to it, not unregistering the instance. A world which streams chunks in and out
 * therefore accumulates one entry per position ever visited, for the life of the process.
 * </p>
 * <p>
 * This test lives in {@code net.minestom.server.instance} for the same reason the class it tests
 * does: the map is package-private and reading it from anywhere else would need reflection.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The viewer cache entry of a chunk")
class ChunkViewerCacheTest {

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
    @DisplayName("is created by the chunk constructor and removed by the release")
    void testTheEntryCanBeReleased(Env env) {
        final FalcoInstance instance = registered(env);
        final int before = ChunkViewerCache.size(instance);

        new net.onelitefeather.falco.instance.FalcoChunk(instance, 4, 4);
        assertEquals(before + 1, ChunkViewerCache.size(instance),
                "constructing a chunk has to leave exactly one entry behind, or this test is measuring "
                        + "something other than the leak it is named after");

        assertTrue(ChunkViewerCache.release(instance, 4, 4));
        assertEquals(before, ChunkViewerCache.size(instance));
    }

    @Test
    @DisplayName("reports that there was nothing to release when there was not")
    void testReleasingNothing(Env env) {
        final FalcoInstance instance = registered(env);

        assertFalse(ChunkViewerCache.release(instance, 77, 77),
                "no chunk was ever built at that position, so no entry can be removed");
    }

    @Test
    @DisplayName("leaves the cache where it found it across a load and an unload")
    void testALoadAndUnloadCycleIsNeutral(Env env) {
        final FalcoInstance instance = registered(env);
        final int before = ChunkViewerCache.size(instance);

        for (int round = 0; round < 32; round++) {
            instance.unloadChunk(instance.loadChunk(round, 0).join());
        }

        assertEquals(before, ChunkViewerCache.size(instance),
                "thirty-two load and unload cycles have to leave the cache exactly as they found it");
    }
}
