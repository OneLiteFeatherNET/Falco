package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the summary the report rests on. A mean without a spread is the failure mode this whole
 * type exists to prevent, so the deviation is pinned down against hand computed values.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class StatisticsTest {

    private static final double TOLERANCE = 1.0e-9;

    @Test
    void testTheMeanIsTheArithmeticMean() {
        Statistics statistics = Statistics.of(List.of(10L, 20L, 30L, 40L));

        assertEquals(25.0, statistics.mean(), TOLERANCE);
    }

    @Test
    void testTheDeviationUsesTheSampleDivisor() {
        // Deviations from the mean of 25 are -15, -5, 5, 15, so the squared error is 500 and the
        // sample variance is 500 / 3. A population divisor would give 12.5 instead.
        Statistics statistics = Statistics.of(List.of(10L, 20L, 30L, 40L));

        assertEquals(Math.sqrt(500.0 / 3.0), statistics.standardDeviation(), TOLERANCE);
    }

    @Test
    void testTheExtremesAreTheSmallestAndLargestSample() {
        Statistics statistics = Statistics.of(List.of(70L, 10L, 40L));

        assertEquals(10.0, statistics.minimum(), TOLERANCE);
        assertEquals(70.0, statistics.maximum(), TOLERANCE);
    }

    @Test
    void testIdenticalSamplesHaveNoDeviation() {
        Statistics statistics = Statistics.of(List.of(5L, 5L, 5L));

        assertEquals(0.0, statistics.standardDeviation(), TOLERANCE);
        assertEquals(0.0, statistics.relativeStandardDeviation(), TOLERANCE);
    }

    @Test
    void testTheRelativeDeviationIsAPercentageOfTheMean() {
        Statistics statistics = new Statistics(4, 200.0, 20.0, 180.0, 220.0);

        assertEquals(10.0, statistics.relativeStandardDeviation(), TOLERANCE);
    }

    @Test
    void testTheRelativeDeviationOfAZeroMeanIsZero() {
        Statistics statistics = new Statistics(2, 0.0, 0.0, 0.0, 0.0);

        assertEquals(0.0, statistics.relativeStandardDeviation(), TOLERANCE);
    }

    @Test
    void testScalingMovesEveryValueByTheSameFactor() {
        Statistics scaled = new Statistics(3, 640.0, 32.0, 600.0, 700.0).scaled(1.0 / 64.0);

        assertEquals(3, scaled.sampleCount());
        assertEquals(10.0, scaled.mean(), TOLERANCE);
        assertEquals(0.5, scaled.standardDeviation(), TOLERANCE);
        assertEquals(600.0 / 64.0, scaled.minimum(), TOLERANCE);
        assertEquals(700.0 / 64.0, scaled.maximum(), TOLERANCE);
    }

    @Test
    void testASingleSampleIsRefused() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> Statistics.of(List.of(1L)));

        assertEquals("A spread needs at least two samples but got 1", exception.getMessage());
    }

    @Test
    void testNoSampleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Statistics.of(List.of()));
    }
}
