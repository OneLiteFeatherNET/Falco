package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the propagation across the sections of a chunk. Light that stops at a section border is
 * the reason a per section propagation alone cannot be used for a real world.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class ChunkLightPropagatorTest {

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
     * Builds the opacity tables of a chunk made of the given amount of air sections.
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
    void testAChunkWithoutAnySourceStaysDark() {
        List<LightNibbles> light = new ChunkLightPropagator().propagate(tables(airChunk(3)));

        assertEquals(3, light.size());
        for (LightNibbles section : light) {
            assertTrue(section.isUniform());
            assertEquals(0, section.get(0, 0, 0));
        }
    }

    @Test
    void testLightCrossesTheBorderIntoTheSectionAbove() {
        List<int[]> sections = airChunk(2);
        // A lamp at the very top of the lower section.
        sections.get(0)[index(8, 15, 8)] = LAMP;

        List<LightNibbles> light = new ChunkLightPropagator().propagate(tables(sections));

        assertEquals(15, light.get(0).get(8, 15, 8));
        assertEquals(14, light.get(1).get(8, 0, 8), "the section above has to receive the light");
        assertEquals(13, light.get(1).get(8, 1, 8));
    }

    @Test
    void testLightCrossesTheBorderIntoTheSectionBelow() {
        List<int[]> sections = airChunk(2);
        // A lamp at the very bottom of the upper section.
        sections.get(1)[index(8, 0, 8)] = LAMP;

        List<LightNibbles> light = new ChunkLightPropagator().propagate(tables(sections));

        assertEquals(15, light.get(1).get(8, 0, 8));
        assertEquals(14, light.get(0).get(8, 15, 8), "the section below has to receive the light");
        assertEquals(13, light.get(0).get(8, 14, 8));
    }

    @Test
    void testLightReachesThroughSeveralSections() {
        List<int[]> sections = airChunk(3);
        sections.get(0)[index(8, 14, 8)] = LAMP;

        List<LightNibbles> light = new ChunkLightPropagator().propagate(tables(sections));

        // 15 at the source, one level lost per block: the second section starts at 14.
        assertEquals(14, light.get(0).get(8, 15, 8));
        assertEquals(13, light.get(1).get(8, 0, 8));
        assertEquals(0, light.get(2).get(8, 0, 8), "the third section is out of range");
    }

    @Test
    void testAnOpaqueLayerStopsTheLightAtTheBorder() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(8, 14, 8)] = LAMP;

        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                sections.get(0)[index(x, 15, z)] = STONE;
            }
        }

        List<LightNibbles> light = new ChunkLightPropagator().propagate(tables(sections));

        assertEquals(0, light.get(1).get(8, 0, 8), "the closed layer must stop the light");
    }

    @Test
    void testAnOpaqueLayerOnTheOtherSideOfTheBorderAlsoStops() {
        List<int[]> sections = airChunk(2);
        sections.get(0)[index(8, 15, 8)] = LAMP;

        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                sections.get(1)[index(x, 0, z)] = STONE;
            }
        }

        List<LightNibbles> light = new ChunkLightPropagator().propagate(tables(sections));

        assertEquals(0, light.get(1).get(8, 0, 8));
        assertEquals(0, light.get(1).get(8, 1, 8));
    }

    @Test
    void testASourceInEverySectionIsHandled() {
        List<int[]> sections = airChunk(3);
        sections.get(0)[index(1, 1, 1)] = LAMP;
        sections.get(2)[index(14, 14, 14)] = LAMP;

        List<LightNibbles> light = new ChunkLightPropagator().propagate(tables(sections));

        assertEquals(15, light.get(0).get(1, 1, 1));
        assertEquals(15, light.get(2).get(14, 14, 14));
    }

    @Test
    void testTheResultIsIndependentOfTheSectionOrderOfSources() {
        List<int[]> ascending = airChunk(2);
        ascending.get(0)[index(8, 15, 8)] = LAMP;

        List<int[]> descending = airChunk(2);
        descending.get(0)[index(8, 15, 8)] = LAMP;

        List<LightNibbles> first = new ChunkLightPropagator().propagate(tables(ascending));
        List<LightNibbles> second = new ChunkLightPropagator().propagate(tables(descending));

        for (int section = 0; section < first.size(); section++) {
            for (int y = 0; y < LightNibbles.DIMENSION; y++) {
                assertEquals(first.get(section).get(8, y, 8), second.get(section).get(8, y, 8));
            }
        }
    }

    @Test
    void testAnEmptyChunkIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkLightPropagator().propagate(List.of()));
    }

    @Test
    void testThePropagatorCanBeReused() {
        ChunkLightPropagator propagator = new ChunkLightPropagator();
        List<int[]> lit = airChunk(2);
        lit.get(0)[index(8, 15, 8)] = LAMP;

        List<LightNibbles> first = propagator.propagate(tables(lit));
        List<LightNibbles> second = propagator.propagate(tables(airChunk(2)));

        assertEquals(14, first.get(1).get(8, 0, 8), "the first result must stay untouched");
        assertEquals(0, second.get(1).get(8, 0, 8), "the reused buffers must not carry the previous run");
    }
}
