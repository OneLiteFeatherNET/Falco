package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the precomputed opacity table of a section. Resolving the properties of a block for every
 * visited neighbour is the dominant cost of a light propagation, so they are looked up once per
 * block and read from a table afterwards.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class SectionOpacityTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int GLOWSTONE = 2;
    private static final int SLAB = 3;

    /**
     * The first state id which no named block of this test uses.
     * States built from it behave like air and only serve to fill a section with distinct ids.
     */
    private static final int FILLER_BASE = 100;

    /**
     * The amount of bytes a single table build may allocate.
     * <p>
     * The two arrays a table keeps account for a little over eight kilobytes and the cache behind
     * the build for a few more. The budget leaves room for both and still catches an allocation
     * which happens per block rather than per section, because 4096 objects alone are far above it.
     * </p>
     */
    private static final long ALLOCATION_BUDGET = 24L * 1024L;

    /**
     * A source which describes four blocks without touching any registry.
     * The slab blocks the downwards face only, which is what makes a single occlusion flag wrong.
     */
    private static final BlockLightSource SOURCE = new BlockLightSource() {

        @Override
        public int emission(int stateId) {
            return stateId == GLOWSTONE ? 15 : 0;
        }

        @Override
        public boolean blocksFace(int stateId, BlockFace face) {
            return switch (stateId) {
                case STONE -> true;
                case SLAB -> face == BlockFace.BOTTOM;
                default -> false;
            };
        }
    };

    /**
     * Builds a section in which every block holds the given state id.
     *
     * @param stateId the state id of every block
     * @return the created table
     */
    private static SectionOpacity uniformSection(int stateId) {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        java.util.Arrays.fill(states, stateId);
        return SectionOpacity.of(states, SOURCE);
    }

    @Test
    void testATransparentBlockBlocksNoFace() {
        SectionOpacity opacity = uniformSection(AIR);

        for (BlockFace face : BlockFace.values()) {
            assertFalse(opacity.blocksFace(0, 0, 0, face), "air must not block " + face);
        }
    }

    @Test
    void testAnOpaqueBlockBlocksEveryFace() {
        SectionOpacity opacity = uniformSection(STONE);

        for (BlockFace face : BlockFace.values()) {
            assertTrue(opacity.blocksFace(5, 5, 5, face), "stone must block " + face);
        }
    }

    @Test
    void testADirectionalBlockOnlyBlocksItsOwnFaces() {
        // Slabs, stairs, snow and farmland occlude some faces and not others. A table which stores
        // a single flag per block would answer this wrongly for roughly one in seven block types.
        SectionOpacity opacity = uniformSection(SLAB);

        assertTrue(opacity.blocksFace(1, 1, 1, BlockFace.BOTTOM));
        assertFalse(opacity.blocksFace(1, 1, 1, BlockFace.TOP));
        assertFalse(opacity.blocksFace(1, 1, 1, BlockFace.NORTH));
    }

    @Test
    void testTheEmissionOfABlockIsKept() {
        SectionOpacity opacity = uniformSection(GLOWSTONE);

        assertEquals(15, opacity.emission(3, 4, 5));
    }

    @Test
    void testABlockWithoutEmissionReportsZero() {
        assertEquals(0, uniformSection(STONE).emission(0, 0, 0));
    }

    @Test
    void testASectionWithoutAnyEmissionIsReported() {
        assertFalse(uniformSection(STONE).hasEmission());
    }

    @Test
    void testASectionWithAnEmittingBlockIsReported() {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        states[42] = GLOWSTONE;

        assertTrue(SectionOpacity.of(states, SOURCE).hasEmission());
    }

    @Test
    void testAFullyTransparentSectionIsReported() {
        assertTrue(uniformSection(AIR).isFullyTransparent());
        assertFalse(uniformSection(STONE).isFullyTransparent());
    }

    @Test
    void testMixedBlocksAreResolvedPerPosition() {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        states[index(2, 3, 4)] = STONE;
        states[index(2, 3, 5)] = GLOWSTONE;
        SectionOpacity opacity = SectionOpacity.of(states, SOURCE);

        assertTrue(opacity.blocksFace(2, 3, 4, BlockFace.TOP));
        assertFalse(opacity.blocksFace(2, 3, 5, BlockFace.TOP));
        assertEquals(15, opacity.emission(2, 3, 5));
        assertEquals(0, opacity.emission(2, 3, 4));
    }

    @Test
    void testEveryDistinctStateIsResolvedOnlyOnce() {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        java.util.Arrays.fill(states, STONE);
        CountingSource counting = new CountingSource();

        SectionOpacity.of(states, counting);

        assertEquals(1, counting.resolved, "4096 blocks of one state must cost one lookup");
    }

    @Test
    void testTheStateArrayMustCoverTheWholeSection() {
        assertThrows(IllegalArgumentException.class, () -> SectionOpacity.of(new int[10], SOURCE));
    }

    @Test
    void testEveryDistinctStateOfAMixedSectionIsResolvedOnlyOnce() {
        // The uniform shortcut never touches the cache, so a uniform section cannot show whether
        // the table really resolves once per distinct state. Only a mixed section can.
        int[] states = new int[LightNibbles.BLOCK_COUNT];

        for (int index = 0; index < states.length; index++) {
            states[index] = FILLER_BASE + (index % 200);
        }
        CountingSource counting = new CountingSource();

        SectionOpacity.of(states, counting);

        assertEquals(200, counting.resolved, "200 distinct states must cost 200 lookups");
    }

    @Test
    void testASectionOfNothingButDistinctStatesIsResolvedOnlyOnce() {
        // A section may hold as many distinct states as it holds blocks. The cache behind the table
        // has to reach that size without ever resolving a state twice.
        int[] states = new int[LightNibbles.BLOCK_COUNT];

        for (int index = 0; index < states.length; index++) {
            states[index] = FILLER_BASE + index;
        }
        CountingSource counting = new CountingSource();

        SectionOpacity.of(states, counting);

        assertEquals(LightNibbles.BLOCK_COUNT, counting.resolved);
    }

    @Test
    void testStatesWhichShareACacheSlotAreKeptApart() {
        // State ids which are a multiple of the cache size apart land on the same slot of any cache
        // that is indexed by a power of two. Confusing them would give a block the properties of an
        // entirely different one.
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        int[] colliding = {STONE, STONE + 1024, STONE + 2048, GLOWSTONE, GLOWSTONE + 1024};

        for (int index = 0; index < states.length; index++) {
            states[index] = colliding[index % colliding.length];
        }
        SectionOpacity opacity = SectionOpacity.of(states, SOURCE);

        for (int index = 0; index < states.length; index++) {
            int x = index & 15;
            int z = (index >> 4) & 15;
            int y = index >> 8;
            int stateId = states[index];

            assertEquals(SOURCE.blocksFace(stateId, BlockFace.TOP), opacity.blocksFace(x, y, z, BlockFace.TOP));
            assertEquals(SOURCE.emission(stateId), opacity.emission(x, y, z));
        }
    }

    @Test
    void testTheTableIsBuiltWithoutGarbageBesidesItself() {
        // The table used to resolve its states through a lambda that was created inside the loop
        // over the blocks, which left one throwaway object per block behind. That is 4096 objects
        // for a table that keeps two arrays of 4096 bytes, and it showed up as a light engine which
        // scattered far more than the one of the server. The table must not allocate per block.
        com.sun.management.ThreadMXBean threads = allocationCounter();
        int[] states = new int[LightNibbles.BLOCK_COUNT];

        for (int index = 0; index < states.length; index++) {
            states[index] = FILLER_BASE + (index % 200);
        }

        // The very first build links the call sites of everything it touches, which allocates once
        // and would be counted as if it belonged to the table.
        SectionOpacity.of(states, SOURCE);

        long before = threads.getCurrentThreadAllocatedBytes();
        SectionOpacity built = SectionOpacity.of(states, SOURCE);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertFalse(built.isUniform());
        assertTrue(
                allocated <= ALLOCATION_BUDGET,
                "building a table allocated " + allocated + " bytes, at most " + ALLOCATION_BUDGET + " are allowed"
        );
    }

    /**
     * Returns the bean which reports how many bytes the current thread has allocated.
     *
     * @return the bean of the running virtual machine
     */
    private static com.sun.management.ThreadMXBean allocationCounter() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();

        org.junit.jupiter.api.Assumptions.assumeTrue(
                bean instanceof com.sun.management.ThreadMXBean,
                "the running virtual machine does not report the allocation of a thread"
        );
        return (com.sun.management.ThreadMXBean) bean;
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
     * A source which counts how often a distinct state was resolved.
     */
    private static final class CountingSource implements BlockLightSource {

        private int resolved;

        @Override
        public int emission(int stateId) {
            this.resolved++;
            return 0;
        }

        @Override
        public boolean blocksFace(int stateId, BlockFace face) {
            return true;
        }
    }

    @Test
    void testAUniformSectionIsRecognised() {
        // Whole sections of a world hold one repeated state. Such a section needs no per position
        // table at all, which saves both the lookups and the two arrays.
        assertTrue(uniformSection(STONE).isUniform());
        assertTrue(uniformSection(AIR).isUniform());
    }

    @Test
    void testASectionWithOneDifferingBlockIsNotUniform() {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        states[LightNibbles.BLOCK_COUNT - 1] = STONE;

        assertFalse(SectionOpacity.of(states, SOURCE).isUniform());
    }

    @Test
    void testAUniformSectionAnswersLikeAFullTable() {
        SectionOpacity uniform = uniformSection(SLAB);

        // The shortcut must give the same answer at every position and for every face.
        for (BlockFace face : BlockFace.values()) {
            boolean expected = face == BlockFace.BOTTOM;
            assertEquals(expected, uniform.blocksFace(0, 0, 0, face));
            assertEquals(expected, uniform.blocksFace(15, 15, 15, face));
            assertEquals(expected, uniform.blocksFace(7, 3, 11, face));
        }
        assertEquals(0, uniform.emission(9, 9, 9));
    }

    @Test
    void testAUniformSectionOfLampsStillReportsEmission() {
        SectionOpacity uniform = uniformSection(GLOWSTONE);

        assertTrue(uniform.hasEmission());
        assertEquals(15, uniform.emission(4, 4, 4));
    }

    @Test
    void testAUniformSectionResolvesExactlyOneState() {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        java.util.Arrays.fill(states, STONE);
        CountingSource counting = new CountingSource();

        SectionOpacity.of(states, counting);

        assertEquals(1, counting.resolved);
    }
}
