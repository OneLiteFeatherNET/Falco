package net.onelitefeather.falco.demo;

import org.jetbrains.annotations.Contract;

import java.util.Arrays;

/**
 * The {@link SampleWindow} class keeps the most recent samples of one live figure and summarises
 * them without losing the outlier.
 * <p>
 * A demo server is judged by its stutters. The thing a player notices while flying is the one chunk
 * that took eighty milliseconds, not the four hundred that took one, and a mean over a whole session
 * is precisely the statistic that makes such a chunk disappear. This window therefore reports a
 * median, a ninety-fifth percentile and a maximum next to the mean: the median says what the normal
 * case is, the percentile says how bad the bad cases are, and the maximum is the stutter itself.
 * </p>
 * <p>
 * The window is bounded and overwrites its oldest sample, which is what keeps the figures about the
 * last few seconds rather than about the whole run. A server that has been up for an hour would
 * otherwise print numbers no change could ever move again.
 * </p>
 * <p>
 * Every method is synchronised because chunks are loaded on virtual threads Minestom starts per
 * chunk, while the reporting task reads on the tick thread. The critical sections are an array write
 * and a copy of at most a few hundred doubles, which is far cheaper than the load being measured.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
public final class SampleWindow {

    /**
     * The number of samples a window keeps unless the caller asks for another size.
     * <p>
     * Large enough that a percentile means something, small enough that the figures still follow
     * what the player is doing right now rather than what they did a minute ago.
     * </p>
     */
    public static final int DEFAULT_CAPACITY = 512;

    /**
     * The fraction the reported percentile is taken at.
     */
    private static final double PERCENTILE_FRACTION = 0.95;

    private final double[] samples;

    private int size;

    private int next;

    /**
     * Creates an empty window of the given size.
     *
     * @param capacity the number of samples the window keeps
     * @throws IllegalArgumentException if the capacity is smaller than one
     */
    public SampleWindow(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A window has to keep at least one sample but the capacity was " + capacity);
        }
        this.samples = new double[capacity];
    }

    /**
     * Creates an empty window of the default size.
     */
    public SampleWindow() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Adds one sample, overwriting the oldest one once the window is full.
     *
     * @param sample the sample to record
     */
    public synchronized void record(double sample) {
        this.samples[this.next] = sample;
        this.next = (this.next + 1) % this.samples.length;

        if (this.size < this.samples.length) {
            this.size++;
        }
    }

    /**
     * Summarises the samples the window currently holds.
     *
     * @return the summary, which is {@link Summary#EMPTY} while nothing has been recorded
     */
    public synchronized Summary summary() {
        if (this.size == 0) {
            return Summary.EMPTY;
        }

        double[] sorted = Arrays.copyOf(this.samples, this.size);
        double sum = 0.0;

        for (double sample : sorted) {
            sum += sample;
        }

        Arrays.sort(sorted);

        return new Summary(
                this.size,
                sum / this.size,
                percentile(sorted, 0.5),
                percentile(sorted, PERCENTILE_FRACTION),
                sorted[sorted.length - 1]
        );
    }

    /**
     * Picks a percentile out of an ascending array by nearest rank.
     * <p>
     * Nearest rank rather than an interpolating definition, because an interpolated percentile
     * reports a duration no chunk ever took. Every figure this demo prints is a duration something
     * really had, which is what makes it possible to go looking for the chunk it came from.
     * </p>
     *
     * @param sorted   the samples in ascending order, at least one of them
     * @param fraction the fraction to take, from zero to one
     * @return the sample at that rank
     */
    @Contract(pure = true)
    static double percentile(double[] sorted, double fraction) {
        int rank = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank))];
    }

    /**
     * The summary of a window, with the outlier kept next to the typical case.
     *
     * @param count         the number of samples the summary rests on
     * @param mean          the arithmetic mean of the samples
     * @param median        the sample in the middle, which is the case a player sees most of the time
     * @param percentile95  the sample at the ninety-fifth percentile, which is how bad it gets regularly
     * @param maximum       the largest sample, which is the stutter itself
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.3.0
     */
    public record Summary(int count, double mean, double median, double percentile95, double maximum) {

        /**
         * The summary of a window nothing has been recorded into yet.
         */
        public static final Summary EMPTY = new Summary(0, 0.0, 0.0, 0.0, 0.0);
    }
}
