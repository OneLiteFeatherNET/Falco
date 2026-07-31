package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the light propagation inside a single section.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class LightPropagatorTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int LAMP = 2;
    private static final int SLAB = 3;

    private static final BlockLightSource SOURCE = new BlockLightSource() {

        @Override
        public int emission(int stateId) {
            return stateId == LAMP ? 15 : 0;
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
     * Builds a section of air with the given blocks placed into it.
     *
     * @param placements triples of index and state id
     * @return the state ids of the section
     */
    private static int[] section(int... placements) {
        int[] states = new int[LightNibbles.BLOCK_COUNT];

        for (int i = 0; i < placements.length; i += 2) {
            states[placements[i]] = placements[i + 1];
        }
        return states;
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
    void testASectionWithoutAnySourceStaysDark() {
        SectionOpacity opacity = SectionOpacity.of(section(), SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertTrue(light.isUniform(), "a dark section must not allocate an array");
        assertEquals(0, light.get(0, 0, 0));
    }

    @Test
    void testALightSourceKeepsItsOwnLevel() {
        SectionOpacity opacity = SectionOpacity.of(section(index(8, 8, 8), LAMP), SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(15, light.get(8, 8, 8));
    }

    @Test
    void testTheLevelDropsByOnePerBlock() {
        SectionOpacity opacity = SectionOpacity.of(section(index(8, 8, 8), LAMP), SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(14, light.get(9, 8, 8));
        assertEquals(13, light.get(10, 8, 8));
        assertEquals(12, light.get(11, 8, 8));
    }

    @Test
    void testTheLevelSpreadsInEveryDirection() {
        SectionOpacity opacity = SectionOpacity.of(section(index(8, 8, 8), LAMP), SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(14, light.get(7, 8, 8));
        assertEquals(14, light.get(8, 7, 8));
        assertEquals(14, light.get(8, 9, 8));
        assertEquals(14, light.get(8, 8, 7));
        assertEquals(14, light.get(8, 8, 9));
    }

    @Test
    void testTheLevelUsesTheShortestPath() {
        SectionOpacity opacity = SectionOpacity.of(section(index(8, 8, 8), LAMP), SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        // Diagonal neighbours are reached over two steps, so they lose two levels.
        assertEquals(13, light.get(9, 9, 8));
        assertEquals(12, light.get(9, 9, 9));
    }

    @Test
    void testTheLevelNeverFallsBelowZero() {
        SectionOpacity opacity = SectionOpacity.of(section(index(0, 0, 0), LAMP), SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(0, light.get(15, 15, 15), "a corner too far away stays dark");
    }

    @Test
    void testAnOpaqueBlockStopsTheLight() {
        int[] states = section(index(8, 8, 8), LAMP);

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                states[index(9, y, z)] = STONE;
            }
        }
        SectionOpacity opacity = SectionOpacity.of(states, SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(0, light.get(9, 8, 8), "an opaque block carries no light");
        assertEquals(0, light.get(10, 8, 8), "the wall must block everything behind it");
        assertEquals(14, light.get(7, 8, 8), "the other side stays lit");
    }

    @Test
    void testADirectionalBlockStopsLightThroughItsBlockedFace() {
        // A closed layer of slabs. Each of them blocks its bottom face only, so light from below
        // cannot enter the layer at all. A single slab would not prove this, because light would
        // simply travel around it.
        int[] states = section(index(8, 6, 8), LAMP);

        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                states[index(x, 7, z)] = SLAB;
            }
        }
        SectionOpacity opacity = SectionOpacity.of(states, SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(0, light.get(8, 7, 8), "light from below must not pass the blocked face");
        assertEquals(0, light.get(8, 8, 8), "nothing above the layer may be lit");
        assertEquals(14, light.get(8, 6, 9), "the level below the layer stays lit");
    }

    @Test
    void testADirectionalBlockLetsLightPassItsOpenFaces() {
        // The same slab layer, but the source sits inside it. The sides are open, so light spreads
        // horizontally even though the bottom faces are blocked.
        int[] states = section();

        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                states[index(x, 7, z)] = SLAB;
            }
        }
        states[index(8, 7, 8)] = LAMP;
        SectionOpacity opacity = SectionOpacity.of(states, SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(14, light.get(9, 7, 8), "the open side faces must let light through");
        assertEquals(14, light.get(8, 8, 8), "the top face is open as well");
    }

    @Test
    void testTwoSourcesUseTheHigherLevel() {
        int[] states = section(index(4, 8, 8), LAMP, index(12, 8, 8), LAMP);
        SectionOpacity opacity = SectionOpacity.of(states, SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(14, light.get(5, 8, 8));
        assertEquals(14, light.get(11, 8, 8));
        assertEquals(11, light.get(8, 8, 8), "the midpoint takes the brighter of both");
    }

    @Test
    void testAFullyLitSectionCollapsesAgain() {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        Arrays.fill(states, LAMP);
        SectionOpacity opacity = SectionOpacity.of(states, SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertTrue(light.isUniform(), "a section that is lit everywhere must not keep an array");
        assertEquals(15, light.get(3, 3, 3));
    }

    @Test
    void testThePropagatorCanBeReusedWithoutBleedingResults() {
        LightPropagator propagator = new LightPropagator();
        SectionOpacity lit = SectionOpacity.of(section(index(8, 8, 8), LAMP), SOURCE);
        SectionOpacity dark = SectionOpacity.of(section(), SOURCE);

        LightNibbles first = propagator.propagate(lit);
        LightNibbles second = propagator.propagate(dark);

        assertEquals(15, first.get(8, 8, 8), "the first result must stay untouched");
        assertEquals(0, second.get(8, 8, 8), "the reused buffers must not carry the previous run");
    }

    @Test
    void testEachRunReturnsItsOwnResult() {
        LightPropagator propagator = new LightPropagator();
        SectionOpacity opacity = SectionOpacity.of(section(index(8, 8, 8), LAMP), SOURCE);

        LightNibbles first = propagator.propagate(opacity);
        LightNibbles second = propagator.propagate(opacity);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.get(8, 8, 8), second.get(8, 8, 8));
    }

    @Test
    void testASourceAtTheBorderLightsInwards() {
        SectionOpacity opacity = SectionOpacity.of(section(index(0, 0, 0), LAMP), SOURCE);

        LightNibbles light = new LightPropagator().propagate(opacity);

        assertEquals(15, light.get(0, 0, 0));
        assertEquals(14, light.get(1, 0, 0));
        assertEquals(14, light.get(0, 1, 0));
    }

    @Test
    void testSourcesOfDifferentBrightnessDoNotOverflowTheQueue() {
        // A position is queued again every time its level is raised. Dim sources are seeded first
        // and spread low levels, then a bright source raises the very same positions, so a queue
        // sized for "each position once" is too small.
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

        int[] states = new int[LightNibbles.BLOCK_COUNT];

        // Dim sources across the lower half, one bright source at the very top.
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    states[index(x, y, z)] = 1 + ((x + y + z) % 3);
                }
            }
        }
        states[index(8, 15, 8)] = 15;

        LightNibbles light = new LightPropagator().propagate(SectionOpacity.of(states, graded));

        assertEquals(15, light.get(8, 15, 8));
    }
}
