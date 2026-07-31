package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the light engine can be applied to a chunk of a running server, independent of the
 * chunk loader that produced the chunk.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class ChunkLightServiceIntegrationTest {

    private final ChunkLightService service = new ChunkLightService();

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
    void testALampLightsItsSurroundings(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        this.service.calculate(chunk);

        assertEquals(15, this.service.blockLightAt(chunk, 8, 40, 8));
        assertEquals(14, this.service.blockLightAt(chunk, 9, 40, 8));
        assertEquals(13, this.service.blockLightAt(chunk, 10, 40, 8));
    }

    @Test
    void testAChunkWithoutASourceStaysDark(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();

        this.service.calculate(chunk);

        assertEquals(0, this.service.blockLightAt(chunk, 8, 40, 8));
    }

    @Test
    void testTheLightIsWrittenIntoTheSectionsOfTheChunk(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        this.service.calculate(chunk);

        // The engine has to hand its result to Minestom, not keep it on the side.
        chunk.lockReadLock();
        try {
            int sectionIndex = (40 >> 4) - chunk.getMinSection();
            byte[] stored = chunk.getSections().get(sectionIndex).blockLight().array();

            assertTrue(stored.length > 0, "the section has to carry the calculated light");
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    void testLightCrossesASectionBorderOfARealChunk(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        // Placed at the very top of its section so the light has to reach the one above.
        place(chunk, 8, 47, 8, Block.GLOWSTONE);

        this.service.calculate(chunk);

        assertEquals(15, this.service.blockLightAt(chunk, 8, 47, 8));
        assertEquals(14, this.service.blockLightAt(chunk, 8, 48, 8), "48 is the first block of the next section");
        assertEquals(13, this.service.blockLightAt(chunk, 8, 49, 8));
    }

    @Test
    void testAWallStopsTheLight(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        // The wall has to span further than the light reaches in every direction, otherwise the
        // light simply travels around it and the test proves nothing.
        for (int y = 25; y <= 56; y++) {
            for (int z = 0; z < 16; z++) {
                place(chunk, 9, y, z, Block.STONE);
            }
        }

        this.service.calculate(chunk);

        assertEquals(0, this.service.blockLightAt(chunk, 10, 40, 8), "the wall has to stop the light");
        assertEquals(14, this.service.blockLightAt(chunk, 7, 40, 8), "the open side stays lit");
    }

    @Test
    void testCalculatingTwiceIsStable(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        this.service.calculate(chunk);
        this.service.calculate(chunk);

        assertEquals(14, this.service.blockLightAt(chunk, 9, 40, 8));
    }

    @Test
    void testRemovingTheSourceClearsTheLight(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);
        this.service.calculate(chunk);

        place(chunk, 8, 40, 8, Block.AIR);
        this.service.calculate(chunk);

        assertEquals(0, this.service.blockLightAt(chunk, 9, 40, 8), "a full recalculation has to retract the light");
    }

    @Test
    void testTheServiceWorksOnAChunkFromTheAnvilLoader(Env env, @org.junit.jupiter.api.io.TempDir java.nio.file.Path worldRoot) throws java.io.IOException {
        net.kyori.adventure.key.Key dimension = net.kyori.adventure.key.Key.key("minecraft:overworld");

        try (var loader = new net.onelitefeather.falco.anvil.FalcoAnvilLoader(worldRoot, dimension)) {
            Instance instance = env.createEmptyInstance(loader);
            Chunk chunk = instance.loadChunk(0, 0).join();
            place(chunk, 8, 40, 8, Block.GLOWSTONE);
            loader.saveChunk(chunk);

            Chunk reloaded = loader.loadChunk(instance, 0, 0);
            assertTrue(reloaded != null);

            this.service.calculate(reloaded);

            assertEquals(15, this.service.blockLightAt(reloaded, 8, 40, 8));
            assertEquals(14, this.service.blockLightAt(reloaded, 9, 40, 8));
        }
    }

    @Test
    void testSkyLightReachesTheGroundOfAnOpenChunk(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();

        this.service.calculateSky(chunk);

        chunk.lockReadLock();
        try {
            assertEquals(15, chunk.getSectionAt(40).skyLight().getLevel(8, 40 & 15, 8),
                    "an open column is lit down to the bottom");
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    void testACeilingKeepsTheSkyLightOut(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                place(chunk, x, 60, z, Block.STONE);
            }
        }

        this.service.calculateSky(chunk);

        chunk.lockReadLock();
        try {
            assertEquals(0, chunk.getSectionAt(40).skyLight().getLevel(8, 40 & 15, 8),
                    "everything below the ceiling stays dark");
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    void testLightCrossesIntoTheNeighbouringChunk(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk west = instance.loadChunk(0, 0).join();
        Chunk east = instance.loadChunk(1, 0).join();
        // The lamp sits on the eastern edge of the western chunk.
        place(west, 15, 40, 8, Block.GLOWSTONE);

        this.service.calculateWithNeighbours(instance, 0, 0);

        assertEquals(15, this.service.blockLightAt(west, 15, 40, 8));
        assertEquals(14, this.service.blockLightAt(east, 0, 40, 8),
                "the first block of the neighbouring chunk has to be lit");
        assertEquals(13, this.service.blockLightAt(east, 1, 40, 8));
    }

    @Test
    void testLightReachesTheChunkBehindTheNeighbour(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk origin = instance.loadChunk(0, 0).join();
        Chunk east = instance.loadChunk(1, 0).join();
        Chunk south = instance.loadChunk(0, 1).join();
        Chunk diagonal = instance.loadChunk(1, 1).join();
        // The lamp sits in the corner of its chunk, so its light leaves through two borders and
        // has to travel through one of the neighbours to arrive in the chunk behind them.
        place(origin, 15, 40, 15, Block.GLOWSTONE);

        this.service.calculateWithNeighbours(instance, 0, 0);

        assertEquals(14, this.service.blockLightAt(east, 0, 40, 15));
        assertEquals(14, this.service.blockLightAt(south, 15, 40, 0));
        assertEquals(13, this.service.blockLightAt(diagonal, 0, 40, 0),
                "the light has to continue through a neighbour into the chunk behind it");
    }

    @Test
    void testARepeatedExchangeKeepsTheSameResult(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk origin = instance.loadChunk(0, 0).join();
        Chunk diagonal = instance.loadChunk(1, 1).join();
        instance.loadChunk(1, 0).join();
        instance.loadChunk(0, 1).join();
        place(origin, 15, 40, 15, Block.GLOWSTONE);

        this.service.calculateWithNeighbours(instance, 0, 0);
        int first = this.service.blockLightAt(diagonal, 0, 40, 0);
        this.service.calculateWithNeighbours(instance, 0, 0);

        assertEquals(first, this.service.blockLightAt(diagonal, 0, 40, 0),
                "a settled exchange must not drift when it is repeated");
    }

    @Test
    void testTheExchangeNeverLoadsAMissingNeighbour(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk origin = instance.loadChunk(0, 0).join();
        place(origin, 15, 40, 15, Block.GLOWSTONE);

        this.service.calculateWithNeighbours(instance, 0, 0);

        assertNull(instance.getChunk(1, 0), "a missing neighbour must not be loaded");
        assertNull(instance.getChunk(1, 1), "a missing diagonal neighbour must not be loaded either");
    }

    @Test
    void testAMissingNeighbourIsSkipped(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 15, 40, 8, Block.GLOWSTONE);

        // Only this chunk is loaded, the neighbours are absent.
        this.service.calculateWithNeighbours(instance, 0, 0);

        assertEquals(15, this.service.blockLightAt(chunk, 15, 40, 8));
    }
}
