package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the incremental sky light update of an already calculated chunk.
 * <p>
 * Sky light has an origin no block holds: it falls in from above. An update therefore has to know
 * how far down the sky reaches in every column, otherwise it cannot tell which positions lost their
 * origin and which gained one. Both directions are covered here, together with the case in which
 * the changed block is not the one that decides how far the sky reaches.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class SkyLightUpdateTest {

    private static final int AIR = 0;
    private static final int STONE = 1;

    private static final int HEIGHT = 2 * LightNibbles.DIMENSION;

    private static final BlockLightSource SOURCE = new BlockLightSource() {

        @Override
        public int emission(int stateId) {
            return 0;
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

    /**
     * Places a block at a position of the column, addressing the section it belongs to.
     *
     * @param sections the state ids of every section
     * @param x        the x coordinate inside the chunk
     * @param y        the y coordinate inside the column
     * @param z        the z coordinate inside the chunk
     * @param stateId  the state id to place
     */
    private static void place(List<int[]> sections, int x, int y, int z, int stateId) {
        sections.get(y >> 4)[index(x, y & 15, z)] = stateId;
    }

    /**
     * Fills a whole layer of the column with a block.
     *
     * @param sections the state ids of every section
     * @param y        the y coordinate inside the column
     * @param stateId  the state id to place
     */
    private static void fillLayer(List<int[]> sections, int y, int stateId) {
        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                place(sections, x, y, z, stateId);
            }
        }
    }

    /**
     * Asserts that the given state holds exactly what a calculation from scratch would produce.
     *
     * @param state    the incrementally updated state
     * @param sections the state ids of every section after the change
     */
    private static void assertMatchesFullRecalculation(ChunkLightState state, List<int[]> sections) {
        ChunkLightState fresh = ChunkLightState.skyLight(tables(sections));

        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    assertEquals(fresh.get(x, y, z), state.get(x, y, z),
                            "mismatch at " + x + "/" + y + "/" + z);
                }
            }
        }
    }

    @Test
    void testPlacingABlockLowersTheSkyLightBelowIt() {
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, 20, 8, STONE);
        state.update(tables(sections), 8, 20, 8);

        assertEquals(0, state.get(8, 20, 8), "the new block itself carries no light");
        assertEquals(15, state.get(8, 21, 8), "above the block the sky is untouched");
        assertEquals(14, state.get(8, 19, 8), "below it the column only receives light from the side");
    }

    @Test
    void testRemovingABlockingBlockLetsTheSkyFallThroughAgain() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, 20, STONE);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, 20, 8, AIR);
        state.update(tables(sections), 8, 20, 8);

        assertEquals(15, state.get(8, 20, 8), "the reopened position sees the sky again");
        assertEquals(15, state.get(8, 0, 8), "the sky falls down to the bottom of the column");
        assertEquals(14, state.get(9, 19, 8), "the neighbours below the ceiling are lit from the shaft");
    }

    @Test
    void testTheIncrementalResultMatchesAFullRecalculationAfterAPlacement() {
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, 20, 8, STONE);
        state.update(tables(sections), 8, 20, 8);

        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testTheIncrementalResultMatchesAFullRecalculationAfterARemoval() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, 20, STONE);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, 20, 8, AIR);
        state.update(tables(sections), 8, 20, 8);

        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testAChangeBelowTheHighestBlockingBlockStaysCorrect() {
        // A ceiling with a single shaft in it, so the space below is lit through that shaft. The
        // change happens ten blocks below the ceiling and therefore leaves the reach of the sky in
        // its column untouched, while the light around it still changes.
        List<int[]> sections = airChunk(2);
        fillLayer(sections, 20, STONE);
        place(sections, 7, 20, 8, AIR);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, 10, 8, STONE);
        state.update(tables(sections), 8, 10, 8);

        assertEquals(0, state.get(8, 10, 8), "the new block itself carries no light");
        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testRemovingTheHighestBlockUncoversTheOneBelowIt() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, 20, STONE);
        fillLayer(sections, 10, STONE);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, 20, 8, AIR);
        state.update(tables(sections), 8, 20, 8);

        assertEquals(15, state.get(8, 11, 8), "the sky now reaches down to the next block");
        assertEquals(0, state.get(8, 10, 8), "the block below still stops it");
        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testASequenceOfChangesStillMatchesAFullRecalculation() {
        // A fixed seed keeps the sequence reproducible while still covering combinations no
        // handwritten case would reach: changes above, below and at the height which stops the sky.
        Random random = new Random(20_260_731L);
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        for (int change = 0; change < 40; change++) {
            int x = random.nextInt(LightNibbles.DIMENSION);
            int y = random.nextInt(HEIGHT);
            int z = random.nextInt(LightNibbles.DIMENSION);

            place(sections, x, y, z, random.nextBoolean() ? STONE : AIR);
            state.update(tables(sections), x, y, z);
            assertMatchesFullRecalculation(state, sections);
        }
    }

    @Test
    void testRemovingABlockBelowTheHighestBlockingBlockStaysCorrect() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, 20, STONE);
        place(sections, 7, 20, 8, AIR);
        place(sections, 8, 10, 8, STONE);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, 10, 8, AIR);
        state.update(tables(sections), 8, 10, 8);

        assertMatchesFullRecalculation(state, sections);
    }
}
