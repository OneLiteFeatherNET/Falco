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
 * <p>
 * Every case here starts from a freshly registered instance whose cache is empty, which makes a
 * single-position case blind in one direction: a {@code release} which wipes the whole map passes it,
 * because wiping a map that holds one entry and removing that one entry are the same observation.
 * {@code testReleasingOnePositionLeavesTheOthers} is the case that separates them and is the only
 * reason the word "the entry of this position" in the class under test is a claim rather than a hope.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
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

    /**
     * Establishes that a release takes the entry of the position it was given and no other.
     * <p>
     * The other three cases hold at most one entry at a time, so each of them is satisfied by a
     * {@code release} which empties the whole map — the mutation which replaces the body with
     * {@code viewers.clear()} keeps all three green. This case holds two entries and releases one, so
     * it fails on that mutation twice over: the size after the release is the size of the map minus
     * one entry, and the surviving position still has an entry to give back.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    @DisplayName("takes the entry of the position it was given and no other")
    void testReleasingOnePositionLeavesTheOthers(Env env) {
        final FalcoInstance instance = registered(env);
        final int before = ChunkViewerCache.size(instance);

        new net.onelitefeather.falco.instance.FalcoChunk(instance, 4, 4);
        new net.onelitefeather.falco.instance.FalcoChunk(instance, 9, 9);
        assertEquals(before + 2, ChunkViewerCache.size(instance),
                "two chunks at two positions have to leave two entries, or this case cannot tell a "
                        + "targeted removal from a wipe either");

        assertTrue(ChunkViewerCache.release(instance, 4, 4));
        assertEquals(before + 1, ChunkViewerCache.size(instance),
                "releasing one position has to cost exactly one entry, not the whole map");
        assertTrue(ChunkViewerCache.release(instance, 9, 9),
                "the entry of the position that was not released has to still be there");
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
