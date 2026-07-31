package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down what one area computation writes and, more importantly, what it does not write.
 * <p>
 * The interesting property of an area is not that the light inside it is right — a single chunk
 * would manage that. It is that the chunks around it are read so the edge of the area is correct,
 * and that those chunks are then left alone. A ring chunk is missing the light from beyond it, so
 * writing its result back would replace correct light with a darker one, which is the defect
 * {@code calculateWithNeighbours} still has and which this type exists to avoid.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class ChunkLightAreaTest {

    /**
     * Places a block in the given chunk while holding its write lock.
     *
     * @param chunk the chunk which receives the block
     * @param x     the x coordinate inside the chunk
     * @param y     the y coordinate of the block
     * @param z     the z coordinate inside the chunk
     * @param block the block to place
     */
    private static void place(Chunk chunk, int x, int y, int z, Block block) {
        chunk.lockWriteLock();
        try {
            chunk.setBlock(x, y, z, block);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    @Test
    void testLightCrossesTheBorderBetweenTwoChunksOfOneArea(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk left = instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();
        place(left, 15, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        new ChunkLightArea(service).compute(instance, List.of(new ChunkArea(0, 0), new ChunkArea(1, 0)), false);

        Chunk right = instance.getChunk(1, 0);
        assertEquals(15, service.blockLightAt(left, 15, 40, 8));
        assertEquals(14, service.blockLightAt(right, 0, 40, 8), "light has to reach across the border");
    }

    @Test
    void testChunksOutsideTheAreaKeepTheirLight(Env env) {
        // The ring is read so the area's edge is correct, but it must never be written —
        // a ring chunk is missing the light from beyond it and would end up too dark.
        Instance instance = env.createEmptyInstance();
        Chunk inside = instance.loadChunk(0, 0).join();
        Chunk ring = instance.loadChunk(1, 0).join();
        place(ring, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        service.calculate(ring);
        int before = service.blockLightAt(ring, 8, 40, 8);

        new ChunkLightArea(service).compute(instance, List.of(new ChunkArea(0, 0)), false);

        assertEquals(before, service.blockLightAt(ring, 8, 40, 8), "a ring chunk must not be rewritten");
        // The source sits at world x 24, the read position at world x 15, so nine steps of decay
        // separate them and the level has to arrive as 6.
        assertEquals(6, service.blockLightAt(inside, 15, 40, 8), "the area saw the ring's light");
    }

    @Test
    void testAnUnloadedChunkInTheAreaIsSkipped(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk loaded = instance.loadChunk(0, 0).join();
        place(loaded, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        List<ChunkArea> written = new ChunkLightArea(service)
                .compute(instance, List.of(new ChunkArea(0, 0), new ChunkArea(50, 50)), false);

        assertEquals(List.of(new ChunkArea(0, 0)), written, "only the loaded chunk can be written");
        assertEquals(15, service.blockLightAt(loaded, 8, 40, 8), "the loaded chunk was still computed");
    }

    @Test
    void testAnAreaWithoutALoadedChunkWritesNothing(Env env) {
        Instance instance = env.createEmptyInstance();

        List<ChunkArea> written = new ChunkLightArea(new ChunkLightService())
                .compute(instance, List.of(new ChunkArea(80, 80)), false);

        assertTrue(written.isEmpty());
    }

    @Test
    void testAChunkTheCallerNoLongerWantsIsDiscarded(Env env) {
        // A chunk which changed while the area was being computed carries a result built from block
        // states that are already gone. Writing it would clear the update flag of its sections on
        // the basis of stale data, so the whole result for that chunk is thrown away instead.
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        List<ChunkArea> written = new ChunkLightArea(service)
                .compute(instance, List.of(new ChunkArea(0, 0)), false, area -> false);

        assertTrue(written.isEmpty());
        assertEquals(0, service.blockLightAt(chunk, 8, 40, 8), "a discarded result must not reach the sections");
    }

    @Test
    void testTheAreaComputesSkyLight(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();

        new ChunkLightArea(new ChunkLightService()).compute(instance, List.of(new ChunkArea(0, 0)), true);

        chunk.lockReadLock();
        try {
            assertEquals(15, chunk.getSectionAt(40).skyLight().getLevel(8, 40 & 15, 8),
                    "an open column is lit down to the bottom");
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    void testLightTravelsThroughTheRingAroundAWall(Env env) {
        // The border between the two chunks of the area is walled off over the full height the
        // light could climb, so the only route from the source to the read position leaves the area
        // into the ring chunks to the east and comes back from there. An area which exchanged
        // borders only between its own chunks would leave that position dark.
        Instance instance = env.createEmptyInstance();
        Chunk source = instance.loadChunk(0, 0).join();
        Chunk behind = instance.loadChunk(0, 1).join();
        instance.loadChunk(1, 0).join();
        instance.loadChunk(1, 1).join();

        // World position 15/40/15, the south east corner of the source chunk.
        place(source, 15, 40, 15, Block.GLOWSTONE);

        // The whole shared border, from fifteen blocks below the source to fifteen above it, so no
        // level which still carries anything can climb over the wall instead of going around it.
        for (int x = 0; x < 16; x++) {
            for (int y = 25; y <= 55; y++) {
                place(behind, x, y, 0, Block.STONE);
            }
        }

        ChunkLightService service = new ChunkLightService();
        new ChunkLightArea(service).compute(instance, List.of(new ChunkArea(0, 0), new ChunkArea(0, 1)), false);

        // 15/40/15 to 16/40/15 to 16/40/16 to 16/40/17 to 15/40/17: four steps of decay through
        // two ring chunks, so the level has to arrive as 11.
        assertEquals(11, service.blockLightAt(behind, 15, 40, 1),
                "the light has to reach the second chunk of the area through the ring");
    }

    @Test
    void testTheWalledOffChunkStaysDarkWithoutTheRing(Env env) {
        // The control for the test above: the same wall, the same source, the same read position,
        // only the two eastern chunks are missing. If this were lit anyway, the previous test would
        // prove nothing about the ring.
        Instance instance = env.createEmptyInstance();
        Chunk source = instance.loadChunk(0, 0).join();
        Chunk behind = instance.loadChunk(0, 1).join();

        place(source, 15, 40, 15, Block.GLOWSTONE);

        for (int x = 0; x < 16; x++) {
            for (int y = 25; y <= 55; y++) {
                place(behind, x, y, 0, Block.STONE);
            }
        }

        ChunkLightService service = new ChunkLightService();
        new ChunkLightArea(service).compute(instance, List.of(new ChunkArea(0, 0), new ChunkArea(0, 1)), false);

        assertEquals(0, service.blockLightAt(behind, 15, 40, 1),
                "without the ring there is no route around the wall");
    }
}
