package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the sky propagation against an independent implementation over terrain whose columns stop the
 * sky at different heights.
 * <p>
 * The propagator lights every position that sees the sky but queues only the ones that can pass the
 * light on: a position whose four horizontal neighbours are open at the same height can raise
 * nobody. Which positions those are is derived from the heightmap, as the range between a column's
 * own sky bottom and the deepest bottom among its neighbours.
 * </p>
 * <p>
 * <b>That range is what the rest of the suite did not cover.</b> Shortening it by one position — the
 * plainest way to get it wrong — left all 212 tests green. Every sky test before this one uses
 * terrain that is flat, fully open or fully closed, and on flat terrain the range is empty and the
 * whole derivation is a no-op. A test that cannot fail is worse than no test, so this one builds
 * terrain with a different height in every column and compares position by position.
 * </p>
 * <p>
 * The reference below is the algorithm the propagator used before the heightmap: seed every open
 * position, then spread. It is written out here rather than called, because the point is to compare
 * against something that does not share the code under test.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class SkyHeightmapSeedTest {

    private static final int AIR = 0;
    private static final int STONE = 1;

    /**
     * A block which stops the sky from above and lets light through from every other side.
     * <p>
     * This is the state that makes the difference between a correct queued range and a range one
     * short of it. The position a column's sky stops at is the blocker itself, and a blocker which
     * occludes every face takes no light from the side either — so getting the top of the range
     * wrong is invisible against solid stone. A slab stops the sky and still accepts light
     * sideways, which is exactly the case the range has to reach.
     * </p>
     */
    private static final int SLAB = 2;

    /**
     * The seed of the terrain, so a failure can be reproduced.
     */
    private static final long SEED = 20260802L;

    private static final BlockFace[] FACES = BlockFace.values();

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
     * Builds a chunk of the given amount of air sections.
     *
     * @param sectionCount the amount of sections
     * @return the state ids of every section
     */
    private static List<int[]> airChunk(int sectionCount) {
        List<int[]> sections = new ArrayList<>(sectionCount);

        for (int index = 0; index < sectionCount; index++) {
            sections.add(new int[LightNibbles.BLOCK_COUNT]);
        }
        return sections;
    }

    /**
     * Puts a one block ceiling into every column at its own height, leaving air above and below.
     * <p>
     * A ceiling rather than a ground is what this test needs. Under solid ground the dark positions
     * are the stone itself, which takes no light from any side, so the positions the propagator
     * decides not to queue could never have lit anything and an error in that decision stays
     * invisible. Under a ceiling the dark positions are air, they are lit sideways from the open
     * column beside them, and a missing seed shows up as a level that stays at zero.
     * </p>
     *
     * @param sections the state ids of every section
     * @param ceiling  the y of the ceiling of every column, or a negative value for an open column,
     *                 indexed as {@code (z << 4) | x}
     * @param stateId  the block the ceiling is made of
     */
    private static void roof(List<int[]> sections, int[] ceiling, int stateId) {
        for (int z = 0; z < LightNibbles.DIMENSION; z++) {
            for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                int y = ceiling[(z << 4) | x];

                if (y >= 0) {
                    sections.get(y >> 4)[(y & 15) << 8 | (z << 4) | x] = stateId;
                }
            }
        }
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
     * Calculates the sky light the way the propagator did before the heightmap: every open position
     * is a seed.
     *
     * @param tables the opacity table of every section
     * @return the level of every position of the column
     */
    private static byte[] reference(List<SectionOpacity> tables) {
        int height = tables.size() * LightNibbles.DIMENSION;
        byte[] levels = new byte[height * LightNibbles.BLOCK_COUNT / LightNibbles.DIMENSION];
        Deque<Integer> queue = new ArrayDeque<>();

        for (int z = 0; z < LightNibbles.DIMENSION; z++) {
            for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                for (int y = height - 1; y >= 0; y--) {
                    if (tables.get(y >> 4).blocksFace(x, y & 15, z, BlockFace.TOP)) {
                        break;
                    }
                    int index = (y << 8) | (z << 4) | x;
                    levels[index] = LightNibbles.MAX_LEVEL;
                    queue.add(index);
                }
            }
        }

        while (!queue.isEmpty()) {
            int index = queue.poll();
            int level = levels[index];

            if (level <= 1) {
                continue;
            }
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = index >> 8;

            for (BlockFace face : FACES) {
                int nx = x + face.offsetX();
                int ny = y + face.offsetY();
                int nz = z + face.offsetZ();

                if ((nx | ny | nz) < 0 || nx > 15 || nz > 15 || ny >= height) {
                    continue;
                }
                if (tables.get(ny >> 4).blocksFace(nx, ny & 15, nz, face.opposite())) {
                    continue;
                }
                int neighbour = (ny << 8) | (nz << 4) | nx;

                if (levels[neighbour] >= level - 1) {
                    continue;
                }
                levels[neighbour] = (byte) (level - 1);
                queue.add(neighbour);
            }
        }
        return levels;
    }

    /**
     * Compares the propagator against the reference over the given terrain.
     *
     * @param sections the state ids of every section
     * @param what     what the terrain is, for the failure message
     */
    private static void assertMatchesReference(List<int[]> sections, String what) {
        List<SectionOpacity> tables = tables(sections);
        byte[] expected = reference(tables);
        List<LightNibbles> actual = new ChunkLightPropagator().propagateSky(tables);
        int height = tables.size() * LightNibbles.DIMENSION;

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    assertEquals(
                            expected[(y << 8) | (z << 4) | x],
                            actual.get(y >> 4).get(x, y & 15, z),
                            () -> what + ": the level differs somewhere in the column"
                    );
                }
            }
        }
    }

    @Test
    void testACeilingAtADifferentHeightInEveryColumnMatchesTheReference() {
        int sectionCount = 4;
        Random random = new Random(SEED);
        int[] ceiling = new int[LightNibbles.DIMENSION * LightNibbles.DIMENSION];

        for (int column = 0; column < ceiling.length; column++) {
            // Every fourth column is left open, so the light has somewhere to come from.
            ceiling[column] = random.nextInt(4) == 0
                    ? -1
                    : random.nextInt(sectionCount * LightNibbles.DIMENSION);
        }
        List<int[]> sections = airChunk(sectionCount);
        roof(sections, ceiling, STONE);

        assertMatchesReference(sections, "a ceiling at a random height per column");
    }

    @Test
    void testAStaircaseCeilingMatchesTheReference() {
        int sectionCount = 3;
        int[] ceiling = new int[LightNibbles.DIMENSION * LightNibbles.DIMENSION];

        for (int z = 0; z < LightNibbles.DIMENSION; z++) {
            for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                ceiling[(z << 4) | x] = x == 0 ? -1 : x * 3;
            }
        }
        List<int[]> sections = airChunk(sectionCount);
        roof(sections, ceiling, STONE);

        assertMatchesReference(sections, "a ceiling rising along x");
    }

    @Test
    void testACeilingWithASingleHoleMatchesTheReference() {
        int sectionCount = 3;
        int[] ceiling = new int[LightNibbles.DIMENSION * LightNibbles.DIMENSION];

        // One closed ceiling over the whole chunk with a single column left open. Everything under
        // it is air and dark, and every level below comes from that one column travelling sideways.
        for (int column = 0; column < ceiling.length; column++) {
            ceiling[column] = sectionCount * LightNibbles.DIMENSION - 1;
        }
        ceiling[(8 << 4) | 8] = -1;

        List<int[]> sections = airChunk(sectionCount);
        roof(sections, ceiling, STONE);

        assertMatchesReference(sections, "a ceiling with a single hole");
    }

    @Test
    void testASlabCeilingAtADifferentHeightInEveryColumnMatchesTheReference() {
        int sectionCount = 4;
        Random random = new Random(SEED);
        int[] ceiling = new int[LightNibbles.DIMENSION * LightNibbles.DIMENSION];

        for (int column = 0; column < ceiling.length; column++) {
            ceiling[column] = random.nextInt(4) == 0
                    ? -1
                    : random.nextInt(sectionCount * LightNibbles.DIMENSION);
        }
        List<int[]> sections = airChunk(sectionCount);
        roof(sections, ceiling, SLAB);

        assertMatchesReference(sections, "a slab ceiling at a random height per column");
    }

    @Test
    void testASingleSlabBesideAnOpenColumnMatchesTheReference() {
        int sectionCount = 2;
        int[] ceiling = new int[LightNibbles.DIMENSION * LightNibbles.DIMENSION];

        // The smallest terrain that separates a correct range from one position short of it. Every
        // column is open except one, which carries a slab: the slab stops the sky above itself and
        // still takes light from its four neighbours, so the topmost position of the queued range
        // has somewhere to give its light. Against stone it would have nowhere and the error would
        // not show.
        for (int column = 0; column < ceiling.length; column++) {
            ceiling[column] = -1;
        }
        ceiling[(8 << 4) | 8] = 20;

        List<int[]> sections = airChunk(sectionCount);
        roof(sections, ceiling, SLAB);

        assertMatchesReference(sections, "a single slab among open columns");
    }

    @Test
    void testTwoNeighbouringColumnsThatStopOneApartMatchTheReference() {
        int sectionCount = 2;
        int[] ceiling = new int[LightNibbles.DIMENSION * LightNibbles.DIMENSION];

        // The smallest terrain whose queued range is not empty: one column carries a ceiling and
        // every other column is open. Its four neighbours then have to be queued for exactly the
        // heights at and below that ceiling, which is what an off-by-one in the range drops.
        for (int column = 0; column < ceiling.length; column++) {
            ceiling[column] = -1;
        }
        ceiling[(8 << 4) | 8] = 20;

        List<int[]> sections = airChunk(sectionCount);
        roof(sections, ceiling, STONE);

        assertMatchesReference(sections, "a single column with a ceiling");
    }
}
