package net.onelitefeather.falco.light;

import net.kyori.adventure.key.Key;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    void testTheServiceWorksOnAChunkFromTheAnvilLoader(Env env, @TempDir Path worldRoot) throws IOException {
        Key dimension = Key.key("minecraft:overworld");

        try (var loader = new FalcoAnvilLoader(worldRoot, dimension)) {
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
    void testLightCrossesInFromTheNeighbouringChunk(Env env) {
        // Only the middle chunk is written, so the border is read from the side that keeps its
        // light: the lamp sits in the neighbour and its light has to arrive in the middle chunk.
        Instance instance = env.createEmptyInstance();
        Chunk middle = instance.loadChunk(0, 0).join();
        Chunk east = instance.loadChunk(1, 0).join();
        place(east, 0, 40, 8, Block.GLOWSTONE);

        this.service.calculateWithNeighbours(instance, 0, 0);

        assertEquals(14, this.service.blockLightAt(middle, 15, 40, 8),
                "the last block of the middle chunk has to be lit from across the border");
        assertEquals(13, this.service.blockLightAt(middle, 14, 40, 8));
    }

    @Test
    void testLightArrivesFromTheChunkBehindTheNeighbour(Env env) {
        // This is what the neighbourhood has over an area of a single chunk: the 3x3 covers the
        // diagonal chunks, which share no border with the middle chunk, so their light can only
        // arrive by travelling through one of the two chunks in between.
        Instance instance = env.createEmptyInstance();
        Chunk middle = instance.loadChunk(0, 0).join();
        Chunk diagonal = instance.loadChunk(1, 1).join();
        instance.loadChunk(1, 0).join();
        instance.loadChunk(0, 1).join();
        place(diagonal, 0, 40, 0, Block.GLOWSTONE);

        this.service.calculateWithNeighbours(instance, 0, 0);

        assertEquals(13, this.service.blockLightAt(middle, 15, 40, 15),
                "the light has to continue through a neighbour into the chunk behind it");
    }

    @Test
    void testARepeatedExchangeKeepsTheSameResult(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk middle = instance.loadChunk(0, 0).join();
        Chunk diagonal = instance.loadChunk(1, 1).join();
        instance.loadChunk(1, 0).join();
        instance.loadChunk(0, 1).join();
        place(diagonal, 0, 40, 0, Block.GLOWSTONE);

        this.service.calculateWithNeighbours(instance, 0, 0);
        int first = this.service.blockLightAt(middle, 15, 40, 15);
        this.service.calculateWithNeighbours(instance, 0, 0);

        assertTrue(first > 0, "the exchange has to deliver something before stability means anything");
        assertEquals(first, this.service.blockLightAt(middle, 15, 40, 15),
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

    @Test
    void testABorrowedNeighbourKeepsTheLightFromOutsideTheNeighbourhood(Env env) {
        // The neighbourhood around (0,0) reaches to (1,0) and no further, so the light that (1,0)
        // legitimately receives from (2,0) is not part of what the exchange computes for it.
        // Writing that result back would replace correct light with a darker one.
        Instance instance = env.createEmptyInstance();
        Chunk middle = instance.loadChunk(0, 0).join();
        Chunk borrowed = instance.loadChunk(1, 0).join();
        Chunk outside = instance.loadChunk(2, 0).join();
        place(middle, 8, 40, 8, Block.GLOWSTONE);
        // World x 32, one block east of the position the assertions read.
        place(outside, 0, 40, 8, Block.GLOWSTONE);

        // Light (1,0) from its own neighbourhood first, which is the only one that sees (2,0).
        this.service.calculateWithNeighbours(instance, 1, 0);
        assertEquals(14, this.service.blockLightAt(borrowed, 15, 40, 8),
                "the borrowed chunk starts out with the light of its own neighbourhood");

        this.service.calculateWithNeighbours(instance, 0, 0);

        assertEquals(14, this.service.blockLightAt(borrowed, 15, 40, 8),
                "a borrowed neighbour must keep the light it receives from outside the neighbourhood");
        assertEquals(15, this.service.blockLightAt(middle, 8, 40, 8),
                "the middle chunk is still lit");
    }

    @Test
    void testOpacityOfExposesOneEntryPerSection(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.STONE);

        List<SectionOpacity> opacity = this.service.opacityOf(chunk);

        assertEquals(chunk.getSections().size(), opacity.size());
    }

    @Test
    void testApplyLightWritesIntoTheSections(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        int sectionCount = chunk.getSections().size();
        List<LightNibbles> light = new ArrayList<>(sectionCount);

        for (int index = 0; index < sectionCount; index++) {
            light.add(LightNibbles.uniform(7));
        }

        ChunkLightService.applyLight(chunk, light, false);

        assertEquals(7, this.service.blockLightAt(chunk, 1, chunk.getMinSection() * 16 + 1, 1));
    }
}
