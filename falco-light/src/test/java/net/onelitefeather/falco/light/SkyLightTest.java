package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the sky light propagation. Sky light enters a chunk from above, falls straight down without
 * losing a level until something stops it, and only then spreads like any other light.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class SkyLightTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int SLAB = 2;

    private static final BlockLightSource SOURCE = new BlockLightSource() {

        @Override
        public int emission(int stateId) {
            return 0;
        }

        @Override
        public boolean blocksFace(int stateId, BlockFace face) {
            return switch (stateId) {
                case STONE -> true;
                case SLAB -> face == BlockFace.TOP;
                default -> false;
            };
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

    /**
     * Fills a whole layer of the given section with a block.
     *
     * @param section the state ids of the section
     * @param y       the y coordinate inside the section
     * @param stateId the state id to place
     */
    private static void fillLayer(int[] section, int y, int stateId) {
        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                section[index(x, y, z)] = stateId;
            }
        }
    }

    @Test
    void testAnOpenColumnIsFullyLit() {
        List<LightNibbles> light = new ChunkLightPropagator().propagateSky(tables(airChunk(2)));

        assertEquals(15, light.get(1).get(8, 15, 8), "the top of the chunk sees the sky");
        assertEquals(15, light.get(1).get(8, 0, 8));
        assertEquals(15, light.get(0).get(8, 15, 8), "sky light does not weaken while falling");
        assertEquals(15, light.get(0).get(8, 0, 8), "it reaches the bottom of the chunk");
    }

    @Test
    void testAnOpenChunkCollapsesToAUniformSection() {
        List<LightNibbles> light = new ChunkLightPropagator().propagateSky(tables(airChunk(2)));

        for (LightNibbles section : light) {
            assertTrue(section.isUniform(), "a fully lit section must not allocate an array");
        }
    }

    @Test
    void testAClosedCeilingStopsTheSkyLight() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections.get(1), 0, STONE);

        List<LightNibbles> light = new ChunkLightPropagator().propagateSky(tables(sections));

        assertEquals(15, light.get(1).get(8, 1, 8), "above the ceiling stays lit");
        assertEquals(0, light.get(1).get(8, 0, 8), "the ceiling itself receives nothing");
        assertEquals(0, light.get(0).get(8, 15, 8), "everything below stays dark");
    }

    @Test
    void testTheLightSpreadsSidewaysUnderAnOverhang() {
        List<int[]> sections = airChunk(2);

        // A ceiling covering everything but one column, so the sky light enters through the hole
        // and then spreads horizontally, losing one level per block.
        fillLayer(sections.get(1), 0, STONE);
        sections.get(1)[index(8, 0, 8)] = AIR;

        List<LightNibbles> light = new ChunkLightPropagator().propagateSky(tables(sections));

        assertEquals(15, light.get(1).get(8, 0, 8), "the open column keeps the full level");
        assertEquals(14, light.get(0).get(9, 15, 8), "the neighbour below the ceiling loses one");
        assertEquals(13, light.get(0).get(10, 15, 8));
    }

    @Test
    void testADirectionalCeilingBlocksFromAbove() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections.get(1), 0, SLAB);

        List<LightNibbles> light = new ChunkLightPropagator().propagateSky(tables(sections));

        assertEquals(0, light.get(1).get(8, 0, 8), "the slab blocks its top face");
        assertEquals(0, light.get(0).get(8, 15, 8));
    }

    @Test
    void testEachColumnIsEvaluatedOnItsOwn() {
        List<int[]> sections = airChunk(1);
        // A single pillar block in one column only.
        sections.get(0)[index(4, 8, 4)] = STONE;

        List<LightNibbles> light = new ChunkLightPropagator().propagateSky(tables(sections));

        assertEquals(15, light.get(0).get(4, 9, 4), "above the pillar stays lit");
        assertEquals(0, light.get(0).get(4, 8, 4), "the pillar itself is dark");
        assertEquals(15, light.get(0).get(5, 8, 4), "the neighbouring column is untouched");
    }

    @Test
    void testTheLightBelowAPillarIsRestoredFromTheSide() {
        List<int[]> sections = airChunk(1);
        sections.get(0)[index(4, 8, 4)] = STONE;

        List<LightNibbles> light = new ChunkLightPropagator().propagateSky(tables(sections));

        // The column below the pillar is shadowed, but its neighbours are fully lit and feed it.
        assertEquals(14, light.get(0).get(4, 7, 4));
    }

    @Test
    void testSkyAndBlockLightAreCalculatedIndependently() {
        List<int[]> sections = airChunk(1);
        fillLayer(sections.get(0), 15, STONE);
        List<SectionOpacity> tables = tables(sections);
        ChunkLightPropagator propagator = new ChunkLightPropagator();

        List<LightNibbles> sky = propagator.propagateSky(tables);
        List<LightNibbles> block = propagator.propagate(tables);

        assertEquals(0, sky.get(0).get(8, 8, 8), "the closed ceiling keeps the sky light out");
        assertEquals(0, block.get(0).get(8, 8, 8), "there is no emitting block either");
    }
}
