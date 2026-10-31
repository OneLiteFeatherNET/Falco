package net.onelitefeather.falco.benchmark.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that the light sources the benchmarks place land where the benchmarks expect them.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 3.0.2
 */
class SectionStatesTest {

    /**
     * Counts the light emitting blocks of a section.
     *
     * @param values the section to count in
     * @return the amount of light emitting blocks
     */
    private static long sources(int[] values) {
        return Arrays.stream(values).filter(value -> value == FakeBlockLightSource.GLOWSTONE).count();
    }

    @Test
    void testTheRequestedAmountOfSourcesIsPlaced() {
        for (int lightSources : new int[]{0, 1, 4, 64}) {
            int[] values = SectionStates.distinct(BenchmarkConstants.BLOCK_ENTRIES, 8, FakeBlockLightSource.FILLER_BASE);

            assertEquals(lightSources, sources(SectionStates.withLightSources(values, lightSources)),
                    "the sources of a section asked for " + lightSources);
        }
    }

    @Test
    void testPlacingSourcesKeepsEveryDistinctState() {
        int[] values = SectionStates.withLightSources(
                SectionStates.distinct(BenchmarkConstants.BLOCK_ENTRIES, 200, FakeBlockLightSource.FILLER_BASE), 4
        );

        assertEquals(201, Arrays.stream(values).distinct().count(), "the sources add exactly one state");
    }

    @Test
    void testALitSectionHoldsItsSources() {
        assertEquals(8, sources(SectionStates.lit(8, 25)));
    }

    @Test
    void testTooManySourcesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SectionStates.withLightSources(new int[16], 17));
    }
}
