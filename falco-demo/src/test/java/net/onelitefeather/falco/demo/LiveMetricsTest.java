package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the figures the running server reports. The clock is passed in rather than read, because a
 * rate computed against {@code System.nanoTime} could only be asserted with a sleep and a tolerance.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
class LiveMetricsTest {

    private static final double TOLERANCE = 1.0e-6;

    private static final long SECOND = 1_000_000_000L;

    private static final long MILLI = 1_000_000L;

    @Test
    void testAFreshServerReportsNothing() {
        LiveMetrics metrics = new LiveMetrics(0L);

        LiveMetrics.Snapshot snapshot = metrics.snapshot(SECOND);

        assertEquals(0L, snapshot.totalChunkLoads());
        assertEquals(0.0, snapshot.chunksPerSecond(), TOLERANCE);
        assertEquals(0, snapshot.chunkLoadMillis().count());
        assertEquals(0, snapshot.tickMillis().count());
    }

    @Test
    void testALoadedChunkIsReportedInMilliseconds() {
        LiveMetrics metrics = new LiveMetrics(0L);
        metrics.chunkLoaded(3 * MILLI);

        LiveMetrics.Snapshot snapshot = metrics.snapshot(SECOND);

        assertEquals(1, snapshot.chunkLoadMillis().count());
        assertEquals(3.0, snapshot.chunkLoadMillis().median(), TOLERANCE);
    }

    @Test
    void testTheRateCountsTheChunksSinceTheLastSnapshot() {
        LiveMetrics metrics = new LiveMetrics(0L);

        for (int index = 0; index < 40; index++) {
            metrics.chunkLoaded(MILLI);
        }

        assertEquals(20.0, metrics.snapshot(2 * SECOND).chunksPerSecond(), TOLERANCE);
    }

    @Test
    void testTheRateOfTheNextIntervalIgnoresTheChunksOfThePreviousOne() {
        LiveMetrics metrics = new LiveMetrics(0L);

        for (int index = 0; index < 40; index++) {
            metrics.chunkLoaded(MILLI);
        }
        metrics.snapshot(SECOND);

        for (int index = 0; index < 5; index++) {
            metrics.chunkLoaded(MILLI);
        }

        assertEquals(5.0, metrics.snapshot(2 * SECOND).chunksPerSecond(), TOLERANCE);
    }

    @Test
    void testTheTotalKeepsCountingAcrossSnapshots() {
        LiveMetrics metrics = new LiveMetrics(0L);
        metrics.chunkLoaded(MILLI);
        metrics.snapshot(SECOND);
        metrics.chunkLoaded(MILLI);

        assertEquals(2L, metrics.snapshot(2 * SECOND).totalChunkLoads());
    }

    @Test
    void testASnapshotTakenWithoutTimePassingReportsNoRate() {
        LiveMetrics metrics = new LiveMetrics(0L);
        metrics.chunkLoaded(MILLI);

        assertEquals(0.0, metrics.snapshot(0L).chunksPerSecond(), TOLERANCE);
    }

    @Test
    void testTickTimesAreKeptApartFromChunkTimes() {
        LiveMetrics metrics = new LiveMetrics(0L);
        metrics.chunkLoaded(50 * MILLI);
        metrics.tickCompleted(1.5);
        metrics.tickCompleted(2.5);

        LiveMetrics.Snapshot snapshot = metrics.snapshot(SECOND);

        assertEquals(1, snapshot.chunkLoadMillis().count());
        assertEquals(2, snapshot.tickMillis().count());
        assertEquals(2.5, snapshot.tickMillis().maximum(), TOLERANCE);
    }

    @Test
    void testASlowTickSurvivesIntoTheMaximum() {
        LiveMetrics metrics = new LiveMetrics(0L);

        for (int index = 0; index < 100; index++) {
            metrics.tickCompleted(1.0);
        }
        metrics.tickCompleted(120.0);

        LiveMetrics.Snapshot snapshot = metrics.snapshot(SECOND);

        assertEquals(120.0, snapshot.tickMillis().maximum(), TOLERANCE);
        assertTrue(snapshot.tickMillis().mean() < 5.0, "mean was " + snapshot.tickMillis().mean());
    }
}
