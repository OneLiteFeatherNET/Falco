package net.onelitefeather.falco.demo;

import org.jetbrains.annotations.Contract;

import java.util.List;

/**
 * The {@link Statistics} record summarises a series of samples as a mean with a spread.
 * <p>
 * A mean on its own is not a measurement in this project. Two loaders whose means differ by five
 * percent are indistinguishable when each of them scatters by twenty, and a report which prints
 * only the mean invites exactly that mistake. Every number the demo prints therefore travels with
 * the standard deviation, the smallest and the largest sample it came from.
 * </p>
 * <p>
 * The deviation is the sample standard deviation over the measurement rounds. It describes how much
 * the rounds of this one run differed from each other and is not a confidence interval — with ten
 * rounds on a machine the demo does not control, it could not honestly be one.
 * </p>
 *
 * @param sampleCount       the number of samples the summary rests on
 * @param mean              the arithmetic mean of the samples
 * @param standardDeviation the sample standard deviation, using the divisor {@code n - 1}
 * @param minimum           the smallest sample
 * @param maximum           the largest sample
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public record Statistics(int sampleCount, double mean, double standardDeviation, double minimum, double maximum) {

    /**
     * Summarises the given samples.
     *
     * @param samples the samples to summarise
     * @return the summary of the samples
     * @throws IllegalArgumentException if fewer than two samples are given, because a spread over a
     *                                  single sample does not exist and a zero would claim a
     *                                  precision the run never had
     */
    public static Statistics of(List<Long> samples) {
        if (samples.size() < 2) {
            throw new IllegalArgumentException("A spread needs at least two samples but got " + samples.size());
        }

        double sum = 0.0;
        double minimum = Double.MAX_VALUE;
        double maximum = -Double.MAX_VALUE;

        for (long sample : samples) {
            sum += sample;
            minimum = Math.min(minimum, sample);
            maximum = Math.max(maximum, sample);
        }

        double mean = sum / samples.size();
        double squaredError = 0.0;

        for (long sample : samples) {
            double error = sample - mean;
            squaredError += error * error;
        }

        return new Statistics(samples.size(), mean, Math.sqrt(squaredError / (samples.size() - 1)), minimum, maximum);
    }

    /**
     * Returns the standard deviation as a percentage of the mean.
     * <p>
     * This is the number which decides whether a difference between two runs may be read as a
     * difference at all. A run whose rounds scatter by more than the gap between the two loaders has
     * not measured the gap.
     * </p>
     *
     * @return the relative standard deviation in percent, or zero for a mean of zero
     */
    @Contract(pure = true)
    public double relativeStandardDeviation() {
        return this.mean == 0.0 ? 0.0 : this.standardDeviation / this.mean * 100.0;
    }

    /**
     * Scales the whole summary by a factor.
     * <p>
     * Used to express the same samples per chunk instead of per round. Scaling the summary rather
     * than recomputing it from divided samples keeps the deviation consistent with the mean, which
     * is what a reader comparing the two lines expects.
     * </p>
     *
     * @param factor the factor to multiply every value with
     * @return the scaled summary
     */
    @Contract(pure = true)
    public Statistics scaled(double factor) {
        return new Statistics(this.sampleCount, this.mean * factor, this.standardDeviation * factor, this.minimum * factor, this.maximum * factor);
    }
}
