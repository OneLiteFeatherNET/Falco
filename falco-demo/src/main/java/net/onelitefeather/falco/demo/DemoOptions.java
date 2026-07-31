package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Contract;

/**
 * The {@link DemoOptions} record holds everything a run may vary, and refuses a combination which
 * would produce a number nobody may quote.
 * <p>
 * The thread count is the option that matters most and the reason this type exists at all. The
 * advantage of the Falco loader is its lock granularity, which by definition cannot show itself
 * without contention; single threaded it is measurably the slower of the two. A demo with a fixed
 * thread count would therefore either flatter one loader or the other, depending on which number
 * was baked in. Making it an option — and printing it in the report — is what keeps the result
 * readable as a statement about a condition rather than about a loader.
 * </p>
 * <p>
 * The measurement round count has a floor of two because a spread over one sample does not exist.
 * Every other bound is there for the same reason: an option which silently produced a meaningless
 * figure would be worse than one which refuses.
 * </p>
 *
 * @param loader            the loader to measure
 * @param threads           the number of threads which load chunks at the same time
 * @param chunks            the number of chunks to load in every round
 * @param warmupRounds      the number of rounds which are run and reported but not measured
 * @param measurementRounds the number of rounds the summary is computed from
 * @param dimension         the key of the dimension to read
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public record DemoOptions(LoaderKind loader, int threads, int chunks, int warmupRounds, int measurementRounds, Key dimension) {

    /**
     * The number of chunks a round loads unless the run says otherwise.
     * Enough to be well above the cost of starting the round, small enough that a modest world can
     * supply them and a round stays in the range of a second.
     */
    public static final int DEFAULT_CHUNKS = 64;

    /**
     * The number of rounds which are run before the measurement starts.
     * Three rounds are not a substitute for a JMH warm-up. They are enough for the class loading,
     * the first compilation of the codec path and the first read of every region file to be behind
     * the stopwatch, which is what the first round otherwise measures.
     */
    public static final int DEFAULT_WARMUP_ROUNDS = 3;

    /**
     * The number of rounds the reported summary rests on.
     */
    public static final int DEFAULT_MEASUREMENT_ROUNDS = 10;

    /**
     * The greatest thread count the demo picks on its own.
     * A default which grew with the machine would put the most important condition of the
     * measurement out of sight, and four threads is enough contention for the difference between
     * the two loaders to be visible at all.
     */
    public static final int DEFAULT_THREAD_LIMIT = 4;

    /**
     * The dimension a run reads unless the run says otherwise.
     */
    public static final Key DEFAULT_DIMENSION = Key.key("minecraft:overworld");

    /**
     * Checks the bounds every field has to satisfy.
     *
     * @throws IllegalArgumentException if a value would make the result meaningless
     */
    public DemoOptions {
        if (threads < 1) {
            throw new IllegalArgumentException("--threads must be at least 1 but was " + threads);
        }
        if (chunks < 1) {
            throw new IllegalArgumentException("--chunks must be at least 1 but was " + chunks);
        }
        if (warmupRounds < 0) {
            throw new IllegalArgumentException("--warmup must not be negative but was " + warmupRounds);
        }
        if (measurementRounds < 2) {
            throw new IllegalArgumentException("--rounds must be at least 2, otherwise there is no spread to report, but was " + measurementRounds);
        }
    }

    /**
     * Builds the options a run uses when it passes nothing but the loader.
     *
     * @param loader              the loader to measure
     * @param availableProcessors the number of processors the runtime reports
     * @return the default options for that machine
     */
    @Contract(pure = true)
    public static DemoOptions defaults(LoaderKind loader, int availableProcessors) {
        int threads = Math.max(1, Math.min(DEFAULT_THREAD_LIMIT, availableProcessors));
        return new DemoOptions(loader, threads, DEFAULT_CHUNKS, DEFAULT_WARMUP_ROUNDS, DEFAULT_MEASUREMENT_ROUNDS, DEFAULT_DIMENSION);
    }

    /**
     * Parses the command line of a run.
     * <p>
     * Every argument has the form {@code --name=value}. An unknown name is refused rather than
     * ignored: a mistyped {@code --thread=8} which quietly ran with the default would be reported
     * as a measurement at a thread count the run never used.
     * </p>
     *
     * @param arguments           the command line arguments
     * @param availableProcessors the number of processors the runtime reports
     * @return the parsed options
     * @throws IllegalArgumentException if an argument is malformed, unknown, or out of bounds
     */
    public static DemoOptions parse(String[] arguments, int availableProcessors) {
        LoaderKind loader = null;
        Integer threads = null;
        int chunks = DEFAULT_CHUNKS;
        int warmupRounds = DEFAULT_WARMUP_ROUNDS;
        int measurementRounds = DEFAULT_MEASUREMENT_ROUNDS;
        Key dimension = DEFAULT_DIMENSION;

        for (String argument : arguments) {
            if (!argument.startsWith("--") || argument.indexOf('=') < 0) {
                throw new IllegalArgumentException("Expected an argument of the form --name=value but got '" + argument + "'");
            }

            int separator = argument.indexOf('=');
            String name = argument.substring(2, separator);
            String value = argument.substring(separator + 1);

            switch (name) {
                case "loader" -> loader = LoaderKind.parse(value);
                case "threads" -> threads = number(name, value);
                case "chunks" -> chunks = number(name, value);
                case "warmup" -> warmupRounds = number(name, value);
                case "rounds" -> measurementRounds = number(name, value);
                case "dimension" -> dimension = key(value);
                default -> throw new IllegalArgumentException(
                        "Unknown option '--" + name + "'. Known options are --loader, --threads, --chunks, --warmup, --rounds, --dimension"
                );
            }
        }

        if (loader == null) {
            throw new IllegalArgumentException("--loader is required and has to be one of falco, minestom");
        }

        DemoOptions defaults = defaults(loader, availableProcessors);
        return new DemoOptions(loader, threads == null ? defaults.threads() : threads, chunks, warmupRounds, measurementRounds, dimension);
    }

    /**
     * Parses a numeric option value.
     *
     * @param name  the name of the option, for the message
     * @param value the raw value
     * @return the parsed number
     * @throws IllegalArgumentException if the value is not a number
     */
    private static int number(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " expects a whole number but got '" + value + "'");
        }
    }

    /**
     * Parses a dimension key, accepting a plain value without a namespace.
     *
     * @param value the raw value
     * @return the parsed key
     * @throws IllegalArgumentException if the value is not a valid key
     */
    private static Key key(String value) {
        try {
            return value.indexOf(':') < 0 ? Key.key("minecraft", value) : Key.key(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("--dimension expects a key such as minecraft:overworld but got '" + value + "'");
        }
    }
}
