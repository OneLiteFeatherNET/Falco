package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the lines a player and the console read. The claim under test is the one the demo would be
 * worthless without: that a stutter is printed rather than averaged away, and that nothing is
 * claimed while nothing has been measured.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
class LiveStatusLineTest {

    /**
     * Builds a snapshot from ready made summaries.
     *
     * @param chunkLoads the summary of the chunk loads
     * @param ticks      the summary of the ticks
     * @param rate       the chunks per second
     * @return the snapshot
     */
    private LiveMetrics.Snapshot snapshot(SampleWindow.Summary chunkLoads, SampleWindow.Summary ticks, double rate) {
        return new LiveMetrics.Snapshot(1234L, rate, chunkLoads, ticks);
    }

    /**
     * Builds a summary with the given figures.
     *
     * @param median     the median sample
     * @param percentile the ninety-fifth percentile
     * @param maximum    the largest sample
     * @return the summary of sixty-four samples
     */
    private SampleWindow.Summary summary(double median, double percentile, double maximum) {
        return new SampleWindow.Summary(64, median, median, percentile, maximum);
    }

    @Test
    void testTheActionBarNamesTheStackItIsMeasuring() {
        String line = LiveStatusLine.actionBar(
                ServerStack.FALCO, snapshot(summary(0.4, 1.9, 6.1), summary(1.2, 3.8, 9.0), 34.0), 421
        );

        assertTrue(line.startsWith("Falco"), line);
    }

    @Test
    void testTheActionBarCarriesThePercentilesAndNotOnlyAMean() {
        String line = LiveStatusLine.actionBar(
                ServerStack.MINESTOM, snapshot(summary(0.4, 1.9, 6.1), summary(1.2, 3.8, 9.0), 34.0), 421
        );

        assertTrue(line.contains("0.40/1.90/6.10"), line);
        assertTrue(line.contains("1.20/3.80/9.00"), line);
    }

    @Test
    void testTheActionBarCarriesTheThroughputAndTheLoadedChunks() {
        String line = LiveStatusLine.actionBar(
                ServerStack.FALCO, snapshot(summary(0.4, 1.9, 6.1), summary(1.2, 3.8, 9.0), 34.0), 421
        );

        assertTrue(line.contains("34.0 chunks/s"), line);
        assertTrue(line.contains("421 loaded"), line);
    }

    @Test
    void testAStutterSurvivesIntoTheLine() {
        // A mean of forty one millisecond loads and one of ninety is 3.2, which looks healthy. The
        // maximum is the figure that has to reach the player.
        String line = LiveStatusLine.actionBar(
                ServerStack.FALCO, snapshot(new SampleWindow.Summary(41, 3.2, 1.0, 1.0, 90.0), summary(1.0, 1.0, 1.0), 5.0), 12
        );

        assertTrue(line.contains("90.0"), line);
    }

    @Test
    void testNothingIsClaimedBeforeTheFirstChunkIsLoaded() {
        String line = LiveStatusLine.actionBar(
                ServerStack.FALCO, snapshot(SampleWindow.Summary.EMPTY, SampleWindow.Summary.EMPTY, 0.0), 0
        );

        assertTrue(line.contains("no sample yet"), line);
        assertFalse(line.contains("0.00"), line);
    }

    @Test
    void testTheLogLineNamesTheStackTheSampleCountAndThePlayers() {
        String line = LiveStatusLine.logLine(
                ServerStack.MINESTOM, snapshot(summary(0.4, 1.9, 6.1), summary(1.2, 3.8, 9.0), 34.0), 421, 1
        );

        assertTrue(line.startsWith("minestom"), line);
        assertTrue(line.contains("over 64 loads"), line);
        assertTrue(line.contains("1234 chunks read"), line);
        assertTrue(line.contains("421 in memory"), line);
        assertTrue(line.contains("1 player"), line);
    }

    @Test
    void testTheLogLinePluralisesThePlayers() {
        String line = LiveStatusLine.logLine(
                ServerStack.FALCO, snapshot(summary(0.4, 1.9, 6.1), summary(1.2, 3.8, 9.0), 34.0), 421, 2
        );

        assertTrue(line.contains("2 players"), line);
    }

    @Test
    void testTheDetailsNameTheComponentsTheViewDistanceAndTheLimits() {
        List<String> lines = LiveStatusLine.details(
                ServerStack.FALCO, snapshot(summary(0.4, 1.9, 6.1), summary(1.2, 3.8, 9.0), 34.0), 421, 1, 12
        );
        String joined = String.join("\n", lines);

        assertTrue(joined.contains("net.onelitefeather.falco.anvil.FalcoAnvilLoader"), joined);
        assertTrue(joined.contains("net.onelitefeather.falco.light.FalcoLightingChunk"), joined);
        assertTrue(joined.contains("12 chunks"), joined);
        assertTrue(joined.contains("not a benchmark"), joined);
        assertTrue(joined.contains("mean would hide"), joined);
    }

    @Test
    void testASmallDurationKeepsItsDigitsAndALargeOneDoesNot() {
        assertEquals("0.42", LiveStatusLine.millis(0.4238));
        assertEquals("12.3", LiveStatusLine.millis(12.34));
        assertEquals("123", LiveStatusLine.millis(123.4));
    }

    @Test
    void testARateIsPrintedWithOneDecimal() {
        assertEquals("34.0", LiveStatusLine.rate(34.0));
        assertEquals("0.5", LiveStatusLine.rate(0.45));
    }
}
