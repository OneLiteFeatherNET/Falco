package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the window the live figures rest on. The claim being checked is the one the whole type
 * exists for: that an outlier survives into the summary instead of being averaged away, and that a
 * window which has been running for an hour still describes the last few seconds.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
class SampleWindowTest {

    private static final double TOLERANCE = 1.0e-9;

    @Test
    void testAnEmptyWindowSummarisesToNothing() {
        SampleWindow.Summary summary = new SampleWindow(8).summary();

        assertEquals(0, summary.count());
        assertEquals(0.0, summary.mean(), TOLERANCE);
        assertEquals(0.0, summary.median(), TOLERANCE);
        assertEquals(0.0, summary.percentile95(), TOLERANCE);
        assertEquals(0.0, summary.maximum(), TOLERANCE);
    }

    @Test
    void testACapacityBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SampleWindow(0));
    }

    @Test
    void testTheSummaryCoversEverySampleWhileTheWindowIsNotFull() {
        SampleWindow window = new SampleWindow(8);
        window.record(1.0);
        window.record(2.0);
        window.record(3.0);

        SampleWindow.Summary summary = window.summary();

        assertEquals(3, summary.count());
        assertEquals(2.0, summary.mean(), TOLERANCE);
        assertEquals(3.0, summary.maximum(), TOLERANCE);
    }

    @Test
    void testAFullWindowForgetsItsOldestSample() {
        SampleWindow window = new SampleWindow(3);
        window.record(100.0);
        window.record(1.0);
        window.record(2.0);
        window.record(3.0);

        SampleWindow.Summary summary = window.summary();

        assertEquals(3, summary.count());
        assertEquals(2.0, summary.mean(), TOLERANCE);
        assertEquals(3.0, summary.maximum(), TOLERANCE);
    }

    @Test
    void testTheMaximumKeepsASingleStutterVisible() {
        SampleWindow window = new SampleWindow(64);

        for (int index = 0; index < 39; index++) {
            window.record(1.0);
        }
        window.record(90.0);

        SampleWindow.Summary summary = window.summary();

        // The mean moves from 1 to 3.2 and the median does not move at all, which is exactly why
        // neither of them may be the only figure the demo prints.
        assertEquals(1.0, summary.median(), TOLERANCE);
        assertEquals(90.0, summary.maximum(), TOLERANCE);
        assertTrue(summary.mean() < 4.0, "the mean of one stutter in forty is " + summary.mean());
    }

    @Test
    void testTheMedianIsTheMiddleSample() {
        SampleWindow window = new SampleWindow(8);
        window.record(5.0);
        window.record(1.0);
        window.record(3.0);

        assertEquals(3.0, window.summary().median(), TOLERANCE);
    }

    @Test
    void testTheNinetyFifthPercentileIsTheWorstTwentiethOfTheSamples() {
        SampleWindow window = new SampleWindow(32);

        for (int index = 1; index <= 20; index++) {
            window.record(index);
        }

        // Nearest rank over twenty samples: ceil(0.95 * 20) = 19, so the nineteenth smallest.
        assertEquals(19.0, window.summary().percentile95(), TOLERANCE);
    }

    @Test
    void testASingleSampleIsItsOwnPercentile() {
        SampleWindow window = new SampleWindow(4);
        window.record(7.0);

        SampleWindow.Summary summary = window.summary();

        assertEquals(7.0, summary.median(), TOLERANCE);
        assertEquals(7.0, summary.percentile95(), TOLERANCE);
        assertEquals(7.0, summary.maximum(), TOLERANCE);
    }

    @Test
    void testAPercentileOutsideTheSamplesIsClamped() {
        double[] sorted = {1.0, 2.0, 3.0, 4.0};

        assertEquals(1.0, SampleWindow.percentile(sorted, 0.0), TOLERANCE);
        assertEquals(4.0, SampleWindow.percentile(sorted, 1.0), TOLERANCE);
        assertEquals(4.0, SampleWindow.percentile(sorted, 2.0), TOLERANCE);
    }
}
