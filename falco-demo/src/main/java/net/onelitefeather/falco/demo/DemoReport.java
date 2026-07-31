package net.onelitefeather.falco.demo;

import net.minestom.server.MinecraftServer;
import org.jetbrains.annotations.Contract;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@link DemoReport} class turns a finished run into the text the user reads.
 * <p>
 * The formatting rules here are the honest part of the demo. A figure is printed to three
 * significant digits, because that is roughly what a handful of rounds on a machine the demo does
 * not control supports, and printing five would invent precision the run never had. Every figure is
 * accompanied by the spread it came from. And the conditions — thread count, chunk count, cores,
 * jvm, world — are printed above the result rather than below it, because a chunk loading figure
 * without its thread count is not a measurement of anything.
 * </p>
 * <p>
 * The report also says what it is not. The comparison this demo supports is two runs of the same
 * options in two processes on one machine, which is a far weaker instrument than the JMH benchmarks
 * in {@code falco-benchmarks}, and a reader has to be told that in the output rather than in a
 * document they may never open.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class DemoReport {

    /**
     * The width of the rules which frame the heading.
     */
    private static final int RULE_WIDTH = 78;

    /**
     * The number of nanoseconds in a millisecond, as a divisor.
     */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /**
     * The relative standard deviation above which a run is called too noisy to compare.
     * Ten percent is not a rule from anywhere; it is the point at which a difference between the
     * two loaders of the size this demo can resolve disappears into the scatter of one of them.
     */
    private static final double NOISE_THRESHOLD_PERCENT = 10.0;

    /**
     * This class only formats and is never instantiated.
     */
    private DemoReport() {
    }

    /**
     * The properties of the machine and the runtime a measurement was taken on.
     * <p>
     * Captured into a record instead of read from {@link System} inside the formatter, so the
     * report can be rendered from fixed values in a test. A formatter which reaches for the current
     * machine cannot be checked against an expected string on any other one.
     * </p>
     *
     * @param availableProcessors the number of processors the runtime reports
     * @param jvm                 the name and version of the running jvm
     * @param operatingSystem     the name, version and architecture of the operating system
     * @param maxHeapBytes        the greatest heap size the jvm will grow to
     * @param minecraftVersion    the Minecraft version the chunks are decoded as
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    public record Environment(int availableProcessors, String jvm, String operatingSystem, long maxHeapBytes, String minecraftVersion) {

        /**
         * Captures the environment of the running process.
         *
         * @return the environment this jvm runs in
         */
        public static Environment current() {
            return new Environment(
                    Runtime.getRuntime().availableProcessors(),
                    System.getProperty("java.vm.name", "unknown") + " " + System.getProperty("java.runtime.version", "unknown")
                            + " (" + System.getProperty("java.vm.vendor", "unknown") + ")",
                    System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "unknown")
                            + " (" + System.getProperty("os.arch", "unknown") + ")",
                    Runtime.getRuntime().maxMemory(),
                    MinecraftServer.VERSION_NAME
            );
        }
    }

    /**
     * Renders the report of a finished run.
     *
     * @param options          the options the run was started with
     * @param world            the world which was measured
     * @param loadedChunkCount the number of chunks a round asked for
     * @param result           the outcome of the warm-up and the measurement
     * @param environment      the machine and runtime the run happened on
     * @return the report, ready to be printed
     */
    public static String render(
            DemoOptions options,
            WorldSearchResult.Located world,
            int loadedChunkCount,
            LoadMeasurement.Result result,
            Environment environment
    ) {
        Statistics perRound = result.durations();
        Statistics perChunk = perRound.scaled(1.0 / loadedChunkCount);
        StringBuilder report = new StringBuilder();

        heading(report, "Falco chunk loading demo — " + options.loader().name() + " loader");
        report.append('\n');
        report.append("""
                A rough measurement on your machine. It has no forks, no statistical model and no
                isolation from whatever else the machine is doing, so it is an order of magnitude and
                not a benchmark. Anything you intend to quote belongs in the JMH benchmarks of the
                falco-benchmarks module: ./gradlew :falco-benchmarks:jmh
                """);
        report.append('\n');

        report.append("Conditions\n");
        field(report, "Loader", options.loader().implementationName());
        field(report, "World root", world.worldRoot().toString());
        field(report, "Region directory", world.regionDirectory() + "  (" + (world.legacyLayout() ? "legacy layout" : "dimension layout") + ")");
        field(report, "Dimension", world.dimension().asString());
        field(report, "Chunks per round", loadedChunkCount + (loadedChunkCount < options.chunks()
                ? "  (asked for " + options.chunks() + ", the world holds no more)"
                : ""));
        field(report, "Threads", Integer.toString(options.threads()));
        field(report, "Rounds", options.warmupRounds() + " warm-up, " + options.measurementRounds() + " measured");
        field(report, "Processors", Integer.toString(environment.availableProcessors()));
        field(report, "Max heap", environment.maxHeapBytes() / (1024 * 1024) + " MiB");
        field(report, "JVM", environment.jvm());
        field(report, "Operating system", environment.operatingSystem());
        field(report, "Minecraft", environment.minecraftVersion());
        report.append('\n');

        report.append("Warm-up — run, reported, and then thrown away\n");

        if (result.warmup().isEmpty()) {
            field(report, "none", "the run was started with --warmup=0, so the first measured round still carries the class loading");
        } else {
            phase(report, result.warmup());
        }

        report.append('\n');

        report.append("Measurement — ").append(perRound.sampleCount()).append(" rounds\n");
        phase(report, result.measurement());
        report.append('\n');
        field(report, "Per round", millis(perRound.mean()) + " ms  ± " + millis(perRound.standardDeviation()) + " ms"
                + "  (" + significant(perRound.relativeStandardDeviation(), 2) + " %)");
        field(report, "", "smallest " + millis(perRound.minimum()) + " ms, largest " + millis(perRound.maximum()) + " ms");
        field(report, "Per chunk", millis(perChunk.mean()) + " ms  ± " + millis(perChunk.standardDeviation()) + " ms");
        field(report, "Throughput", significant(loadedChunkCount / (perRound.mean() / 1_000_000_000.0), 3) + " chunks/s");
        field(report, "Chunks returned", chunkCounts(result, loadedChunkCount));
        report.append('\n');

        report.append("How to read this\n");
        bullet(report, "The ± is one sample standard deviation over the " + perRound.sampleCount()
                + " measured rounds. It says how much the rounds of this run differed from each other. "
                + "It is not a confidence interval and not an error bar on the difference between two loaders.");
        bullet(report, "Every figure holds for " + options.threads() + " thread" + (options.threads() == 1 ? "" : "s")
                + " and for nothing else. The advantage of the Falco loader is its lock granularity, which cannot "
                + "appear without contention: single threaded it is if anything the slower of the two — the JMH "
                + "comparison in falco-benchmarks puts its region file about a tenth behind Minestom's there — and "
                + "reading only inverts from about two threads upwards. Run this with several values of -Pthreads "
                + "before concluding anything.");
        bullet(report, "Compare only against a run of the other task with the identical options, started right "
                + "after this one on an otherwise idle machine. The two tasks are separate processes and share "
                + "nothing, so anything else running on the machine lands in one of them and not in the other.");
        bullet(report, "The page cache is warm after the warm-up, so this is not a measurement of your disk. "
                + "It is a measurement of the code path above it, which is the part the two loaders differ in.");
        bullet(report, "If the two runs return a different number of chunks they did not do the same work. The "
                + "Falco loader skips a chunk which is not fully generated, the Minestom loader loads it.");
        bullet(report, "Figures are printed to three significant digits, which is about what this instrument "
                + "supports. More digits would be decoration.");

        if (perRound.relativeStandardDeviation() > NOISE_THRESHOLD_PERCENT) {
            report.append('\n');
            bullet(report, "This run scattered by " + significant(perRound.relativeStandardDeviation(), 2)
                    + " percent, which is more than the difference this demo can resolve. Close what else is running, "
                    + "raise -Prounds, and take the result as indicative at best.");
        }

        report.append('\n');
        rule(report);
        return report.toString();
    }

    /**
     * Renders the message for a run which found no world.
     * <p>
     * Printed instead of a stack trace, and instead of an empty measurement. It names the directory
     * that was searched, what was wrong with it, and what the loaders expect to find there — the
     * layout question in particular, since handing over the {@code region} directory rather than the
     * world root is the mistake this text exists for.
     * </p>
     *
     * @param worldsDirectory the directory which was searched
     * @param missing         the reason no world was found there
     * @return the message, ready to be printed
     */
    public static String missingWorld(Path worldsDirectory, WorldSearchResult.Missing missing) {
        StringBuilder report = new StringBuilder();

        heading(report, "Falco demo — there is no world to read");
        report.append('\n');
        report.append("Nothing was loaded, because ").append(missing.reason()).append(".\n");
        report.append('\n');
        report.append("Where the world goes\n");
        bullet(report, "Copy your world folder into " + worldsDirectory + " so that the result looks like "
                + worldsDirectory.resolve("<your-world>") + ".");
        bullet(report, "Copy the world ROOT — the folder which contains level.dat and either a 'region' "
                + "directory or a 'dimensions' directory. Not the 'region' directory itself: both loaders "
                + "resolve <world>/dimensions/<namespace>/<value>/region and fall back to <world>/region, so "
                + "handing them the region directory leaves them looking for a 'region' inside it.");
        bullet(report, "Keep exactly one world in there. The demo will not guess between two.");
        bullet(report, "Nothing in that directory is ever committed; it is excluded by its own .gitignore.");
        report.append('\n');
        report.append("Then run one of the measurements\n");
        bullet(report, "./gradlew :falco-demo:runFalcoLoader");
        bullet(report, "./gradlew :falco-demo:runMinestomLoader");
        report.append('\n');
        report.append("Both accept -Pthreads=<n>, -Pchunks=<n>, -Pwarmup=<n>, -Prounds=<n> and\n");
        report.append("-Pdimension=<key>.\n");
        report.append('\n');
        report.append("Or start a server and look at the world yourself\n");
        bullet(report, "./gradlew :falco-demo:runFalcoServer");
        bullet(report, "./gradlew :falco-demo:runMinestomServer");
        report.append('\n');
        report.append("Both accept -Pport=<n>, -PviewDistance=<n>, -Pdimension=<key> and\n");
        report.append("-Preport=<seconds>. See falco-demo/README.md.\n");
        report.append('\n');
        rule(report);
        return report.toString();
    }

    /**
     * Renders the message for a server run whose command line could not be used.
     * <p>
     * Kept apart from {@link #invalidOptions(String)} because the two runs accept different options,
     * and a list which mixed them would send the reader looking for a {@code -Pthreads} the server
     * does not have.
     * </p>
     *
     * @param message the reason the command line was refused
     * @return the message, ready to be printed
     */
    public static String invalidServerOptions(String message) {
        StringBuilder report = new StringBuilder();

        heading(report, "Falco demo server — the server was not started");
        report.append('\n');
        report.append(message).append(".\n");
        report.append('\n');
        report.append("Accepted options, all of them optional except the stack\n");
        bullet(report, "--stack=falco|minestom    which stack to run");
        bullet(report, "--port=<n>                the port to listen on, default " + ServerOptions.DEFAULT_PORT);
        bullet(report, "--dimension=<key>         default " + ServerOptions.DEFAULT_DIMENSION.asString());
        bullet(report, "--report=<seconds>        seconds between two log lines, default "
                + ServerOptions.DEFAULT_REPORT_INTERVAL_SECONDS);
        report.append('\n');
        report.append("Through gradle the same options are -Pport, -Pdimension and -Preport; the\n");
        report.append("stack is chosen by the task. The view distance is -PviewDistance, which the\n");
        report.append("task turns into the system property minestom.chunk-view-distance because\n");
        report.append("Minestom reads it before any command line of ours could be applied.\n");
        report.append('\n');
        rule(report);
        return report.toString();
    }

    /**
     * Renders the message for a run whose command line could not be used.
     *
     * @param message the reason the command line was refused
     * @return the message, ready to be printed
     */
    public static String invalidOptions(String message) {
        StringBuilder report = new StringBuilder();

        heading(report, "Falco chunk loading demo — the run was not started");
        report.append('\n');
        report.append(message).append(".\n");
        report.append('\n');
        report.append("Accepted options, all of them optional except the loader\n");
        bullet(report, "--loader=falco|minestom   which loader to measure");
        bullet(report, "--threads=<n>             how many chunks are loaded at the same time, default "
                + "min(" + DemoOptions.DEFAULT_THREAD_LIMIT + ", processors)");
        bullet(report, "--chunks=<n>              chunks per round, default " + DemoOptions.DEFAULT_CHUNKS);
        bullet(report, "--warmup=<n>              rounds before the measurement, default " + DemoOptions.DEFAULT_WARMUP_ROUNDS);
        bullet(report, "--rounds=<n>              measured rounds, at least 2, default " + DemoOptions.DEFAULT_MEASUREMENT_ROUNDS);
        bullet(report, "--dimension=<key>         default " + DemoOptions.DEFAULT_DIMENSION.asString());
        report.append('\n');
        report.append("Through gradle the same options are -Pthreads, -Pchunks, -Pwarmup, -Prounds\n");
        report.append("and -Pdimension; the loader is chosen by the task.\n");
        report.append('\n');
        rule(report);
        return report.toString();
    }

    /**
     * Appends one round per line.
     *
     * @param report the report under construction
     * @param rounds the rounds to list
     */
    private static void phase(StringBuilder report, List<LoadMeasurement.Round> rounds) {
        for (int index = 0; index < rounds.size(); index++) {
            LoadMeasurement.Round round = rounds.get(index);
            field(report, "round " + (index + 1), millis(round.durationNanos()) + " ms  (" + round.loadedChunks() + " chunks)");
        }
    }

    /**
     * Describes how many chunks the measured rounds returned.
     *
     * @param result           the outcome of the run
     * @param loadedChunkCount the number of chunks a round asked for
     * @return the description of the returned chunk counts
     */
    private static String chunkCounts(LoadMeasurement.Result result, int loadedChunkCount) {
        int least = result.leastLoadedChunks();
        int most = result.mostLoadedChunks();

        if (least == most) {
            return least + " of " + loadedChunkCount + " every round";
        }

        return least + " to " + most + " of " + loadedChunkCount + ", which differs between rounds and should not";
    }

    /**
     * Appends a label and its value in the two column layout the report uses.
     *
     * @param report the report under construction
     * @param label  the label of the field
     * @param value  the value of the field
     */
    private static void field(StringBuilder report, String label, String value) {
        report.append("  ").append(pad(label)).append(value).append('\n');
    }

    /**
     * Appends a wrapped bullet point.
     *
     * @param report the report under construction
     * @param text   the text of the bullet point
     */
    private static void bullet(StringBuilder report, String text) {
        report.append("  - ").append(wrap(text)).append('\n');
    }

    /**
     * Appends a heading between two rules.
     *
     * @param report the report under construction
     * @param title  the title of the heading
     */
    private static void heading(StringBuilder report, String title) {
        rule(report);
        report.append(' ').append(title).append('\n');
        rule(report);
    }

    /**
     * Appends a horizontal rule.
     *
     * @param report the report under construction
     */
    private static void rule(StringBuilder report) {
        report.append("=".repeat(RULE_WIDTH)).append('\n');
    }

    /**
     * Pads a label to the column width of the report.
     *
     * @param label the label to pad
     * @return the padded label
     */
    @Contract(pure = true)
    private static String pad(String label) {
        return label.isEmpty() ? " ".repeat(20) : (label + " ".repeat(Math.max(1, 20 - label.length())));
    }

    /**
     * Breaks a bullet point across lines at the rule width, indented under its bullet.
     *
     * @param text the text to wrap
     * @return the wrapped text without the leading indent of the first line
     */
    private static String wrap(String text) {
        StringBuilder wrapped = new StringBuilder();
        int lineLength = 0;

        for (String word : text.split(" ")) {
            if (lineLength > 0 && lineLength + word.length() + 1 > RULE_WIDTH - 4) {
                wrapped.append("\n    ");
                lineLength = 0;
            } else if (lineLength > 0) {
                wrapped.append(' ');
                lineLength++;
            }

            wrapped.append(word);
            lineLength += word.length();
        }

        return wrapped.toString();
    }

    /**
     * Formats a duration in nanoseconds as milliseconds with three significant digits.
     *
     * @param nanos the duration in nanoseconds
     * @return the duration in milliseconds
     */
    @Contract(pure = true)
    private static String millis(double nanos) {
        return significant(nanos / NANOS_PER_MILLI, 3);
    }

    /**
     * Rounds a value to the given number of significant digits.
     * <p>
     * Significant digits rather than decimal places, because the two ends of what this demo prints
     * are three orders of magnitude apart: a round takes hundreds of milliseconds and the deviation
     * of a single chunk is a fraction of one.
     * </p>
     *
     * @param value  the value to format
     * @param digits the number of significant digits to keep
     * @return the rounded value without an exponent
     */
    @Contract(pure = true)
    private static String significant(double value, int digits) {
        if (!Double.isFinite(value) || value == 0.0) {
            return "0";
        }

        return new BigDecimal(value).round(new MathContext(digits, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString();
    }
}
