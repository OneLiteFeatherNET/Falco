package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the exchange of light across a chunk border. Without it a torch placed next to the edge of
 * a chunk lights its own chunk and leaves a hard dark line at the border.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class ChunkBorderLightTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int LAMP = 2;

    private static final BlockLightSource SOURCE = new BlockLightSource() {

        @Override
        public int emission(int stateId) {
            return stateId == LAMP ? 15 : 0;
        }

        @Override
        public boolean blocksFace(int stateId, BlockFace face) {
            return stateId == STONE;
        }
    };

    /**
     * Builds the state ids of a chunk made of the given amount of air sections.
     *
     * @param sectionCount the amount of sections the chunk holds
     * @return the state ids of every section
     */
    private static List<int[]> airChunk(int sectionCount) {
        List<int[]> sections = new ArrayList<>(sectionCount);

        for (int i = 0; i < sectionCount; i++) {
            sections.add(new int[LightNibbles.BLOCK_COUNT]);
        }
        return sections;
    }

    /**
     * Converts the state ids of a chunk into opacity tables.
     *
     * @param sections the state ids of every section
     * @return the opacity table of every section
     */
    private static List<SectionOpacity> tables(List<int[]> sections) {
        return sections.stream().map(states -> SectionOpacity.of(states, SOURCE)).toList();
    }

    /**
     * Calculates the index of a block inside a section.
     *
     * @param x the x coordinate inside the section
     * @param y the y coordinate inside the section
     * @param z the z coordinate inside the section
     * @return the index of the block
     */
    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    @Test
    void testTheBorderOfAChunkCanBeRead() {
        List<int[]> sections = airChunk(1);
        sections.get(0)[index(15, 8, 4)] = LAMP;
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        byte[] border = state.border(BlockFace.EAST);

        assertEquals(LightNibbles.DIMENSION * LightNibbles.DIMENSION, border.length);
        assertEquals(15, border[8 * LightNibbles.DIMENSION + 4], "the lamp sits on the east border");
    }

    @Test
    void testLightEntersFromTheNeighbourAcrossTheBorder() {
        // The source sits at the east edge of the western chunk.
        List<int[]> west = airChunk(1);
        west.get(0)[index(15, 8, 8)] = LAMP;
        ChunkLightState westState = ChunkLightState.blockLight(tables(west));

        // The eastern chunk is empty and receives the light through its west border.
        List<int[]> east = airChunk(1);
        List<SectionOpacity> eastTables = tables(east);
        ChunkLightState eastState = ChunkLightState.blockLight(eastTables);

        eastState.injectBorder(eastTables, BlockFace.WEST, westState.border(BlockFace.EAST));

        assertEquals(14, eastState.get(0, 8, 8), "the first block behind the border loses one level");
        assertEquals(13, eastState.get(1, 8, 8));
        assertEquals(12, eastState.get(2, 8, 8));
    }

    @Test
    void testLightEntersFromEveryHorizontalDirection() {
        List<int[]> neighbour = airChunk(1);
        neighbour.get(0)[index(0, 8, 8)] = LAMP;
        ChunkLightState neighbourState = ChunkLightState.blockLight(tables(neighbour));

        List<int[]> own = airChunk(1);
        List<SectionOpacity> ownTables = tables(own);
        ChunkLightState ownState = ChunkLightState.blockLight(ownTables);

        ownState.injectBorder(ownTables, BlockFace.EAST, neighbourState.border(BlockFace.WEST));

        assertEquals(14, ownState.get(15, 8, 8), "the light enters through the east border");
    }

    @Test
    void testAWallAtTheBorderKeepsTheLightOut() {
        List<int[]> west = airChunk(1);
        west.get(0)[index(15, 8, 8)] = LAMP;
        ChunkLightState westState = ChunkLightState.blockLight(tables(west));

        List<int[]> east = airChunk(1);

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                east.get(0)[index(0, y, z)] = STONE;
            }
        }
        List<SectionOpacity> eastTables = tables(east);
        ChunkLightState eastState = ChunkLightState.blockLight(eastTables);

        eastState.injectBorder(eastTables, BlockFace.WEST, westState.border(BlockFace.EAST));

        assertEquals(0, eastState.get(0, 8, 8), "the wall blocks the incoming light");
        assertEquals(0, eastState.get(1, 8, 8));
    }

    @Test
    void testADarkNeighbourChangesNothing() {
        List<int[]> east = airChunk(1);
        east.get(0)[index(8, 8, 8)] = LAMP;
        List<SectionOpacity> eastTables = tables(east);
        ChunkLightState eastState = ChunkLightState.blockLight(eastTables);

        int beforeAtSource = eastState.get(8, 8, 8);
        int beforeAtBorder = eastState.get(0, 8, 8);

        eastState.injectBorder(eastTables, BlockFace.WEST, new byte[LightNibbles.BLOCK_COUNT / LightNibbles.DIMENSION]);

        // The chunk lights itself, so the border block is not dark. What matters is that a dark
        // neighbour neither adds nor removes anything.
        assertEquals(beforeAtSource, eastState.get(8, 8, 8), "the own source must stay untouched");
        assertEquals(beforeAtBorder, eastState.get(0, 8, 8), "a dark neighbour must not change the border");
    }

    @Test
    void testAVerticalFaceIsRejected() {
        List<int[]> sections = airChunk(1);
        List<SectionOpacity> tables = tables(sections);
        ChunkLightState state = ChunkLightState.blockLight(tables);

        assertThrows(IllegalArgumentException.class, () -> state.border(BlockFace.TOP));
    }

    @Test
    void testABorderOfTheWrongSizeIsRejected() {
        List<int[]> sections = airChunk(1);
        List<SectionOpacity> tables = tables(sections);
        ChunkLightState state = ChunkLightState.blockLight(tables);

        assertThrows(IllegalArgumentException.class, () -> state.injectBorder(tables, BlockFace.WEST, new byte[3]));
    }

    @Test
    void testTheInjectedLightMatchesOneLargeCalculation() {
        // Two chunks side by side with one source near the shared border. Injecting the border has
        // to give the eastern chunk the same levels a single calculation over both would.
        List<int[]> west = airChunk(1);
        west.get(0)[index(15, 8, 8)] = LAMP;
        ChunkLightState westState = ChunkLightState.blockLight(tables(west));

        List<int[]> east = airChunk(1);
        List<SectionOpacity> eastTables = tables(east);
        ChunkLightState eastState = ChunkLightState.blockLight(eastTables);
        eastState.injectBorder(eastTables, BlockFace.WEST, westState.border(BlockFace.EAST));

        // Expected levels: distance from the source, which sits one block west of x = 0.
        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            int expected = Math.max(0, 14 - x);
            assertEquals(expected, eastState.get(x, 8, 8), "mismatch at x " + x);
        }
    }

    @Test
    void testAnInjectionReportsWhetherItRaisedALevel() {
        List<int[]> west = airChunk(1);
        west.get(0)[index(15, 8, 8)] = LAMP;
        ChunkLightState westState = ChunkLightState.blockLight(tables(west));

        List<int[]> east = airChunk(1);
        List<SectionOpacity> eastTables = tables(east);
        ChunkLightState eastState = ChunkLightState.blockLight(eastTables);

        assertTrue(eastState.injectBorder(eastTables, BlockFace.WEST, westState.border(BlockFace.EAST)),
                "the first injection raises the levels behind the border");
        assertFalse(eastState.injectBorder(eastTables, BlockFace.WEST, westState.border(BlockFace.EAST)),
                "repeating the very same injection changes nothing");
    }

    @Test
    void testTheBorderIsExchangedAcrossSectionsAsWell() {
        List<int[]> west = airChunk(2);
        west.get(1)[index(15, 4, 8)] = LAMP;
        ChunkLightState westState = ChunkLightState.blockLight(tables(west));

        List<int[]> east = airChunk(2);
        List<SectionOpacity> eastTables = tables(east);
        ChunkLightState eastState = ChunkLightState.blockLight(eastTables);

        byte[] border = westState.border(BlockFace.EAST);

        assertEquals(2 * LightNibbles.DIMENSION * LightNibbles.DIMENSION, border.length,
                "the border spans the full height of the column");

        eastState.injectBorder(eastTables, BlockFace.WEST, border);

        assertTrue(eastState.get(0, 20, 8) > 0, "the light of the upper section has to cross too");
    }
}
