package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that an exchange which skips unchanged neighbours settles on exactly the light an exchange
 * of every border in every round settles on, and that it really skips them.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 3.0.2
 */
class BorderExchangeTest {

    private static final int STONE = 1;
    private static final int LAMP = 2;
    private static final int SIZE = 4;
    private static final int SECTIONS = 2;

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
     * Builds the opacity tables of a grid of chunks with lamps near their edges and stone walls
     * which force the light to take a detour through other chunks.
     *
     * @return the opacity tables of every chunk, row by row
     */
    private static List<List<SectionOpacity>> grid() {
        List<List<SectionOpacity>> grid = new ArrayList<>(SIZE * SIZE);

        for (int chunk = 0; chunk < SIZE * SIZE; chunk++) {
            List<SectionOpacity> sections = new ArrayList<>(SECTIONS);

            for (int section = 0; section < SECTIONS; section++) {
                int[] states = new int[LightNibbles.BLOCK_COUNT];

                // A wall across the middle of some chunks, so light has to go around it.
                if (chunk % 3 == 0) {
                    for (int y = 0; y < LightNibbles.DIMENSION; y++) {
                        for (int x = 0; x < LightNibbles.DIMENSION - 1; x++) {
                            states[(y << 8) | (8 << 4) | x] = STONE;
                        }
                    }
                }
                if (chunk % 5 == 0 && section == 1) {
                    states[(4 << 8) | ((chunk % 16) << 4) | 15] = LAMP;
                }
                if (chunk % 7 == 1 && section == 0) {
                    states[(9 << 8) | (2 << 4)] = LAMP;
                }
                sections.add(SectionOpacity.of(states, SOURCE));
            }
            grid.add(sections);
        }
        return grid;
    }

    /**
     * Finds the neighbours of every chunk of the grid, in the order {@link BorderExchange#FACES}
     * names the faces.
     *
     * @return the index of the neighbour per chunk and face, or a negative value if there is none
     */
    private static int[] neighbours() {
        int[] neighbours = new int[SIZE * SIZE * BorderExchange.FACES.length];

        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                for (int face = 0; face < BorderExchange.FACES.length; face++) {
                    int neighbourX = x + BorderExchange.FACES[face].offsetX();
                    int neighbourZ = z + BorderExchange.FACES[face].offsetZ();
                    boolean outside = neighbourX < 0 || neighbourZ < 0 || neighbourX >= SIZE || neighbourZ >= SIZE;
                    neighbours[(z * SIZE + x) * BorderExchange.FACES.length + face] =
                            outside ? -1 : neighbourZ * SIZE + neighbourX;
                }
            }
        }
        return neighbours;
    }

    /**
     * Calculates the light of every chunk of the grid on its own.
     *
     * @param grid the opacity tables of every chunk
     * @return the light of every chunk before any exchange
     */
    private static List<ChunkLightState> states(List<List<SectionOpacity>> grid) {
        return grid.stream().map(ChunkLightState::blockLight).toList();
    }

    @Test
    void testTheExchangeSettlesOnTheLightOfAFullExchange() {
        List<List<SectionOpacity>> grid = grid();
        int[] neighbours = neighbours();

        List<ChunkLightState> expected = states(grid);
        boolean changed = true;

        // Every border in every round, as the exchange did before it learned to skip.
        while (changed) {
            changed = false;

            for (int chunk = 0; chunk < expected.size(); chunk++) {
                for (int face = 0; face < BorderExchange.FACES.length; face++) {
                    int neighbour = neighbours[chunk * BorderExchange.FACES.length + face];

                    if (neighbour >= 0) {
                        changed |= expected.get(chunk).injectBorder(
                                grid.get(chunk), BorderExchange.FACES[face], expected.get(neighbour));
                    }
                }
            }
        }

        List<ChunkLightState> actual = states(grid);
        assertTrue(new BorderExchange(actual, grid, neighbours).settle(16), "the grid settles");

        for (int chunk = 0; chunk < actual.size(); chunk++) {
            for (int y = 0; y < SECTIONS * LightNibbles.DIMENSION; y++) {
                for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                    for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                        assertEquals(expected.get(chunk).get(x, y, z), actual.get(chunk).get(x, y, z),
                                "mismatch in chunk " + chunk + " at " + x + " " + y + " " + z);
                    }
                }
            }
        }
    }

    @Test
    void testASettledExchangeInjectsNothingAgain() {
        List<List<SectionOpacity>> grid = grid();
        BorderExchange exchange = new BorderExchange(states(grid), grid, neighbours());

        assertTrue(exchange.settle(16), "the grid settles");
        long injections = exchange.injections();

        assertTrue(exchange.settle(16), "a settled grid stays settled");
        assertEquals(injections, exchange.injections(), "no neighbour changed, so no border is injected again");
    }

    @Test
    void testAnUnchangedNeighbourIsNotInjectedTwice() {
        List<List<SectionOpacity>> grid = grid();
        int[] neighbours = neighbours();
        int borders = 0;

        for (int neighbour : neighbours) {
            if (neighbour >= 0) {
                borders++;
            }
        }
        BorderExchange exchange = new BorderExchange(states(grid), grid, neighbours);

        assertTrue(exchange.settle(16), "the grid settles");
        assertTrue(exchange.rounds() > 1, "the grid needs more than one round, or the test proves nothing");
        assertTrue(exchange.injections() < (long) exchange.rounds() * borders,
                "a round after the first injects only the borders of neighbours which changed");
    }

    @Test
    void testAnAreaWhichCannotSettleIsReported() {
        List<List<SectionOpacity>> grid = grid();
        BorderExchange exchange = new BorderExchange(states(grid), grid, neighbours());

        assertFalse(exchange.settle(1), "one round is not enough for light which has to cross several chunks");
    }
}
