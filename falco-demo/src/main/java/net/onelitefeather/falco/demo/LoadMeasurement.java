package net.onelitefeather.falco.demo;

import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.onelitefeather.falco.demo.ChunkInventory.ChunkPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@link LoadMeasurement} class runs the rounds and holds the stopwatch.
 * <p>
 * Three decisions make the difference between this and a number that means nothing.
 * </p>
 * <p>
 * <b>The warm-up is separate and reported.</b> The first round of a fresh jvm pays for class
 * loading, for the interpreter running the whole codec path before it is compiled, and for the
 * first read of every region file from a cold page cache. On a real world that round can be several
 * times the cost of a settled one. It is run, printed, and thrown away, so the reader can see the
 * jvm settling instead of having to trust that it did.
 * </p>
 * <p>
 * <b>The concurrency is exact.</b> The rounds run on a fixed pool of platform threads rather than
 * on the virtual threads a Minestom instance would start per chunk. Minestom's own scheduling would
 * make the actual number of chunks in flight a property of the runtime rather than of the run, and
 * the thread count is the one condition under which the reported figure is valid.
 * </p>
 * <p>
 * <b>Every round is a sample.</b> The measurement keeps the duration of each round instead of
 * dividing one total, which is what allows the report to state a spread. The chunks are also
 * counted per round: two loaders which return a different number of chunks are not doing the same
 * work, and that has to be visible next to the time.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class LoadMeasurement {

    /**
     * This class only provides the static run and is never instantiated.
     */
    private LoadMeasurement() {
    }

    /**
     * The outcome of a single round.
     *
     * @param durationNanos the wall clock time the whole round took
     * @param loadedChunks  the number of chunks the loader returned instead of {@code null}
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    public record Round(long durationNanos, int loadedChunks) {
    }

    /**
     * The outcome of a whole run, with the two phases kept apart.
     *
     * @param warmup      the rounds which were run before the measurement, in order
     * @param measurement the rounds the summary is computed from, in order
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    public record Result(List<Round> warmup, List<Round> measurement) {

        /**
         * Summarises the durations of the measured rounds.
         *
         * @return the summary in nanoseconds per round
         * @throws IllegalArgumentException if fewer than two rounds were measured
         */
        public Statistics durations() {
            return Statistics.of(this.measurement.stream().map(Round::durationNanos).toList());
        }

        /**
         * Returns the smallest number of chunks any measured round loaded.
         *
         * @return the smallest chunk count of a measured round, or zero without any round
         */
        public int leastLoadedChunks() {
            return this.measurement.stream().mapToInt(Round::loadedChunks).min().orElse(0);
        }

        /**
         * Returns the largest number of chunks any measured round loaded.
         * A run where this differs from {@link #leastLoadedChunks()} did not do the same work in
         * every round, which the report has to say out loud.
         *
         * @return the largest chunk count of a measured round, or zero without any round
         */
        public int mostLoadedChunks() {
            return this.measurement.stream().mapToInt(Round::loadedChunks).max().orElse(0);
        }
    }

    /**
     * Runs the warm-up and the measurement over the given chunks.
     *
     * @param loader   the loader under measurement
     * @param instance the instance the loader creates its chunks for
     * @param chunks   the chunks every round loads, in a fixed order
     * @param options  the options of the run, for the thread and round counts
     * @return the durations of both phases
     * @throws InterruptedException if the calling thread is interrupted while a round runs
     * @throws IllegalStateException if a load fails, because a loader which throws has not been
     *                               measured and a mean over the surviving rounds would hide it
     */
    public static Result run(ChunkLoader loader, Instance instance, List<ChunkPosition> chunks, DemoOptions options) throws InterruptedException {
        List<Callable<Integer>> tasks = new ArrayList<>(chunks.size());

        for (ChunkPosition position : chunks) {
            tasks.add(() -> loader.loadChunk(instance, position.x(), position.z()) == null ? 0 : 1);
        }

        ExecutorService pool = Executors.newFixedThreadPool(options.threads(), threadFactory());

        try {
            List<Round> warmup = rounds(pool, tasks, options.warmupRounds());
            List<Round> measurement = rounds(pool, tasks, options.measurementRounds());
            return new Result(warmup, measurement);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Runs a phase and returns one sample per round.
     *
     * @param pool   the pool the chunks are loaded on
     * @param tasks  the load tasks of a single round
     * @param rounds the number of rounds to run
     * @return the outcome of every round, in order
     * @throws InterruptedException if the calling thread is interrupted while a round runs
     */
    private static List<Round> rounds(ExecutorService pool, List<Callable<Integer>> tasks, int rounds) throws InterruptedException {
        List<Round> results = new ArrayList<>(rounds);

        for (int round = 0; round < rounds; round++) {
            // invokeAll returns once every task has finished, so the elapsed time covers the whole
            // round including the slowest thread. Collecting the results happens afterwards, on
            // futures which are already done, and therefore stays outside the measured window.
            long start = System.nanoTime();
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            long elapsed = System.nanoTime() - start;

            results.add(new Round(elapsed, loaded(futures)));
        }

        return List.copyOf(results);
    }

    /**
     * Counts the chunks a round returned.
     *
     * @param futures the finished futures of one round
     * @return the number of chunks which were loaded
     * @throws IllegalStateException if one of the loads failed
     */
    private static int loaded(List<Future<Integer>> futures) {
        int loaded = 0;

        for (Future<Integer> future : futures) {
            try {
                loaded += future.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException("A chunk could not be loaded, so the run measured nothing", exception.getCause());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The run was interrupted while collecting its results", exception);
            }
        }

        return loaded;
    }

    /**
     * Builds the factory for the loading threads.
     * The threads are named so a thread dump of a run that hangs points at the demo rather than at
     * an anonymous pool.
     *
     * @return the thread factory of the pool
     */
    private static ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "falco-demo-loader-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
