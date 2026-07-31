package net.onelitefeather.falco.demo;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link LiveMetrics} class collects what a running demo server is judged by while somebody is
 * flying through the world.
 * <p>
 * The headless tasks of this module measure a fixed list of chunks in a fixed order and can afford
 * to report a mean over ten rounds. A server cannot: which chunks are loaded depends on where the
 * player flies, so there are no rounds and no repetition. What is left is the stream of individual
 * loads, and the only honest summary of a stream is one that keeps its tail. That is why every
 * figure here travels as a median, a ninety-fifth percentile and a maximum rather than as a mean.
 * </p>
 * <p>
 * Three quantities are collected, because those are the three questions a player asks while flying.
 * How long does one chunk take — that is the loader. How many chunks arrive per second — that is
 * whether the world keeps up with the flight. How long does the server tick take — that is whether
 * the rest of the server is paying for the loader, which is where a light engine running on the tick
 * thread would show up.
 * </p>
 * <p>
 * The chunk durations arrive from the virtual threads Minestom starts per chunk and the tick times
 * from the tick thread, so the windows are synchronised. The rate is the only stateful part: it is
 * a difference between two snapshots, which means snapshots have to be taken by one caller at a
 * steady interval. The demo server has exactly one such caller.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
public final class LiveMetrics {

    /**
     * The number of recent chunk loads the figures are computed from.
     */
    private static final int CHUNK_WINDOW = SampleWindow.DEFAULT_CAPACITY;

    /**
     * The number of recent ticks the figures are computed from.
     * <p>
     * A Minestom server ticks twenty times a second, so two hundred ticks are the last ten seconds —
     * long enough to catch a stutter the player just felt and short enough that it drops out again
     * once the server has settled.
     * </p>
     */
    private static final int TICK_WINDOW = 200;

    /**
     * The number of nanoseconds in a millisecond.
     */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /**
     * The number of nanoseconds in a second.
     */
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final SampleWindow chunkLoadMillis = new SampleWindow(CHUNK_WINDOW);

    private final SampleWindow tickMillis = new SampleWindow(TICK_WINDOW);

    private final AtomicLong totalChunkLoads = new AtomicLong();

    private long lastSnapshotNanos;

    private long lastSnapshotChunkLoads;

    /**
     * Creates the metrics of a server which starts now.
     *
     * @param startNanos the reading of the monotonic clock the first rate is measured against
     */
    public LiveMetrics(long startNanos) {
        this.lastSnapshotNanos = startNanos;
    }

    /**
     * Records that one chunk finished loading.
     *
     * @param durationNanos the wall clock time the load of that chunk took
     */
    public void chunkLoaded(long durationNanos) {
        this.chunkLoadMillis.record(durationNanos / NANOS_PER_MILLI);
        this.totalChunkLoads.incrementAndGet();
    }

    /**
     * Records that one server tick finished.
     *
     * @param millis the time the tick took, as Minestom reports it
     */
    public void tickCompleted(double millis) {
        this.tickMillis.record(millis);
    }

    /**
     * Takes the figures of the interval which ends now and starts the next one.
     * <p>
     * The clock reading is a parameter rather than something this class fetches, so the rate can be
     * asserted in a test instead of being slept for.
     * </p>
     *
     * @param nowNanos the current reading of the same monotonic clock the constructor was given
     * @return the figures of the server at this moment
     */
    public synchronized Snapshot snapshot(long nowNanos) {
        long total = this.totalChunkLoads.get();
        long elapsedNanos = nowNanos - this.lastSnapshotNanos;
        double rate = elapsedNanos <= 0L ? 0.0 : (total - this.lastSnapshotChunkLoads) * NANOS_PER_SECOND / elapsedNanos;

        this.lastSnapshotNanos = nowNanos;
        this.lastSnapshotChunkLoads = total;

        return new Snapshot(total, rate, this.chunkLoadMillis.summary(), this.tickMillis.summary());
    }

    /**
     * The figures of a running server at one moment.
     *
     * @param totalChunkLoads  the number of chunks the loader has returned since the server started
     * @param chunksPerSecond  the number of chunks which arrived during the interval that just ended
     * @param chunkLoadMillis  the durations of the recent chunk loads, in milliseconds
     * @param tickMillis       the durations of the recent server ticks, in milliseconds
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.3.0
     */
    public record Snapshot(
            long totalChunkLoads,
            double chunksPerSecond,
            SampleWindow.Summary chunkLoadMillis,
            SampleWindow.Summary tickMillis
    ) {
    }
}
