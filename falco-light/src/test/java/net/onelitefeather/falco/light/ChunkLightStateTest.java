package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the incremental update of an already calculated chunk light.
 * <p>
 * Adding a light source only spreads brightness, which a plain search handles. Removing one is the
 * hard case: the light it had spread has to be retracted first, otherwise it stays behind as a glow
 * without a source.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class ChunkLightStateTest {

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
    void testAFreshStateHoldsTheCalculatedLight() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(8, 8, 8)] = LAMP;

        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        assertEquals(15, state.get(8, 8, 8));
        assertEquals(14, state.get(9, 8, 8));
    }

    @Test
    void testAddingASourceLightsItsSurroundings() {
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        sections.get(0)[index(8, 8, 8)] = LAMP;
        state.update(tables(sections), 8, 8, 8);

        assertEquals(15, state.get(8, 8, 8));
        assertEquals(14, state.get(9, 8, 8));
        assertEquals(13, state.get(10, 8, 8));
    }

    @Test
    void testRemovingASourceRetractsItsLight() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(8, 8, 8)] = LAMP;
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        sections.get(0)[index(8, 8, 8)] = AIR;
        state.update(tables(sections), 8, 8, 8);

        assertEquals(0, state.get(8, 8, 8), "the source itself has to go dark");
        assertEquals(0, state.get(9, 8, 8), "the light it had spread has to be retracted");
        assertEquals(0, state.get(12, 8, 8));
    }

    @Test
    void testRemovingOneOfTwoSourcesKeepsTheOther() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(4, 8, 8)] = LAMP;
        sections.get(0)[index(12, 8, 8)] = LAMP;
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        sections.get(0)[index(4, 8, 8)] = AIR;
        state.update(tables(sections), 4, 8, 8);

        assertEquals(15, state.get(12, 8, 8), "the remaining source keeps its level");
        assertEquals(14, state.get(11, 8, 8));
        assertEquals(7, state.get(4, 8, 8), "the removed position is refilled from the other source");
    }

    @Test
    void testTheIncrementalResultMatchesAFullRecalculation() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(4, 8, 8)] = LAMP;
        sections.get(0)[index(12, 8, 8)] = LAMP;
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        sections.get(0)[index(4, 8, 8)] = AIR;
        state.update(tables(sections), 4, 8, 8);

        ChunkLightState fresh = ChunkLightState.blockLight(tables(sections));

        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(fresh.get(x, y, z), state.get(x, y, z),
                            "mismatch at " + x + "/" + y + "/" + z);
                }
            }
        }
    }

    @Test
    void testPlacingABlockingBlockRemovesTheLightBehindIt() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(8, 8, 8)] = LAMP;
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        sections.get(0)[index(9, 8, 8)] = STONE;
        state.update(tables(sections), 9, 8, 8);

        assertEquals(0, state.get(9, 8, 8), "the new block itself carries no light");
        ChunkLightState fresh = ChunkLightState.blockLight(tables(sections));
        assertEquals(fresh.get(10, 8, 8), state.get(10, 8, 8));
    }

    @Test
    void testAnUpdateCrossesTheSectionBorder() {
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        sections.get(0)[index(8, 15, 8)] = LAMP;
        state.update(tables(sections), 8, 15, 8);

        assertEquals(14, state.get(8, 16, 8), "16 is the first block of the next section");
    }

    @Test
    void testTheStateCanBeReadAsSections() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(8, 8, 8)] = LAMP;
        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        List<LightNibbles> light = state.toSections();

        assertEquals(2, light.size());
        assertEquals(15, light.get(0).get(8, 8, 8));
        assertEquals(14, light.get(0).get(9, 8, 8));
    }

    @Test
    void testASkyLightStateFallsStraightDown() {
        ChunkLightState state = ChunkLightState.skyLight(tables(airChunk(2)));

        assertEquals(15, state.get(8, 0, 8));
        assertEquals(15, state.get(8, 31, 8));
    }

    @Test
    void testAnUpdateWithManyGradedSourcesDoesNotOverflowTheQueue() {
        // Same hazard as in the propagators: a position enters the addition queue again whenever a
        // brighter source raises it, so a queue sized for one entry per position is too small.
        BlockLightSource graded = new BlockLightSource() {

            @Override
            public int emission(int stateId) {
                return stateId == 0 ? 0 : stateId;
            }

            @Override
            public boolean blocksFace(int stateId, BlockFace face) {
                return false;
            }
        };

        List<int[]> sections = airChunk(2);

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    sections.get(0)[index(x, y, z)] = 1 + ((x + y + z) % 3);
                }
            }
        }

        List<SectionOpacity> tables = sections.stream().map(states -> SectionOpacity.of(states, graded)).toList();
        ChunkLightState state = ChunkLightState.blockLight(tables);

        sections.get(1)[index(8, 8, 8)] = 15;
        List<SectionOpacity> updated = sections.stream().map(states -> SectionOpacity.of(states, graded)).toList();
        state.update(updated, 8, 24, 8);

        assertEquals(15, state.get(8, 24, 8));
    }
}
