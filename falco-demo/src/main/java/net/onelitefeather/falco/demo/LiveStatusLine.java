package net.onelitefeather.falco.demo;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@link LiveStatusLine} class turns a {@link LiveMetrics.Snapshot} into the lines the player and
 * the console see.
 * <p>
 * "Judge it by hand" is a guessing game without numbers in front of the person doing the judging, and
 * numbers behind a window they have to alt-tab to are numbers nobody reads while flying. The short
 * form therefore goes to the action bar, which sits above the hotbar and stays visible during flight,
 * and the same figures go to the log at a slower interval so there is a record to look at afterwards.
 * </p>
 * <p>
 * Every duration is printed as three values, and that is the whole point of this class. A stutter is
 * an outlier, and an outlier is exactly what a mean removes: forty chunks at one millisecond and one
 * at ninety average to a perfectly healthy three. The median says what the normal case is, the
 * ninety-fifth percentile says how bad it gets regularly, and the maximum is the stutter the player
 * just felt.
 * </p>
 * <p>
 * This class is pure formatting and reads nothing, so the lines can be asserted in a test rather than
 * being read off a running server.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
public final class LiveStatusLine {

    /**
     * The text shown in place of the figures while nothing has been measured yet.
     * <p>
     * Printed rather than a row of zeros, because a zero here reads as "a chunk loads instantly"
     * instead of as "no chunk has loaded".
     * </p>
     */
    private static final String NO_SAMPLES = "no sample yet";

    /**
     * This class only formats and is never instantiated.
     */
    private LiveStatusLine() {
    }

    /**
     * Builds the line which sits above the hotbar of every player.
     *
     * @param stack        the stack the server runs
     * @param snapshot     the figures of the moment
     * @param loadedChunks the number of chunks the instance currently holds in memory
     * @return the action bar line
     */
    public static String actionBar(ServerStack stack, LiveMetrics.Snapshot snapshot, int loadedChunks) {
        return stack.displayName()
                + " | chunk " + triple(snapshot.chunkLoadMillis())
                + " | " + rate(snapshot.chunksPerSecond()) + " chunks/s"
                + " | tick " + triple(snapshot.tickMillis())
                + " | " + loadedChunks + " loaded";
    }

    /**
     * Builds the line the console receives at the report interval.
     *
     * @param stack        the stack the server runs
     * @param snapshot     the figures of the moment
     * @param loadedChunks the number of chunks the instance currently holds in memory
     * @param players      the number of players currently online
     * @return the log line
     */
    public static String logLine(ServerStack stack, LiveMetrics.Snapshot snapshot, int loadedChunks, int players) {
        return stack.option()
                + " | chunk load " + triple(snapshot.chunkLoadMillis()) + " ms over " + snapshot.chunkLoadMillis().count() + " loads"
                + " | " + rate(snapshot.chunksPerSecond()) + " chunks/s"
                + " | tick " + triple(snapshot.tickMillis()) + " ms"
                + " | " + snapshot.totalChunkLoads() + " chunks read, " + loadedChunks + " in memory"
                + " | " + players + (players == 1 ? " player" : " players");
    }

    /**
     * Builds the block a player receives from the status command.
     * <p>
     * The same figures as the action bar, with the conditions they were taken under and the sentence
     * about what a single session on a single machine is worth. Somebody who reads this while flying
     * should not have to open a document to learn what the numbers do not say.
     * </p>
     *
     * @param stack         the stack the server runs
     * @param snapshot      the figures of the moment
     * @param loadedChunks  the number of chunks the instance currently holds in memory
     * @param players       the number of players currently online
     * @param viewDistance  the chunk view distance the server was started with
     * @return the lines of the status block, in order
     */
    public static List<String> details(
            ServerStack stack,
            LiveMetrics.Snapshot snapshot,
            int loadedChunks,
            int players,
            int viewDistance
    ) {
        List<String> lines = new ArrayList<>();

        lines.add("Falco demo server — " + stack.displayName() + " stack");
        lines.addAll(stack.composition());
        lines.add("view distance  " + viewDistance + " chunks  (identical on both servers)");
        lines.add("");
        lines.add("chunk load     " + summary(snapshot.chunkLoadMillis()));
        lines.add("throughput     " + rate(snapshot.chunksPerSecond()) + " chunks/s over the last interval");
        lines.add("server tick    " + summary(snapshot.tickMillis()));
        lines.add("chunks         " + snapshot.totalChunkLoads() + " read since start, " + loadedChunks + " in memory");
        lines.add("players        " + players);
        lines.add("");
        lines.add("p50 is the normal case, p95 is how bad it gets regularly, max is the stutter you");
        lines.add("just felt. A mean would hide the last one, which is the one you are looking for.");
        lines.add("This is one session on one machine and is not a benchmark; ./gradlew");
        lines.add(":falco-benchmarks:jmh is.");

        return List.copyOf(lines);
    }

    /**
     * Formats a summary as the three figures which matter, without a unit.
     *
     * @param summary the summary to format
     * @return the three figures, or a note that nothing has been measured
     */
    @Contract(pure = true)
    private static String triple(SampleWindow.Summary summary) {
        if (summary.count() == 0) {
            return NO_SAMPLES;
        }

        return millis(summary.median()) + "/" + millis(summary.percentile95()) + "/" + millis(summary.maximum());
    }

    /**
     * Formats a summary with its labels, its unit and the number of samples behind it.
     *
     * @param summary the summary to format
     * @return the labelled figures, or a note that nothing has been measured
     */
    @Contract(pure = true)
    private static String summary(SampleWindow.Summary summary) {
        if (summary.count() == 0) {
            return NO_SAMPLES;
        }

        return "p50 " + millis(summary.median()) + " ms, p95 " + millis(summary.percentile95()) + " ms, max "
                + millis(summary.maximum()) + " ms, mean " + millis(summary.mean()) + " ms over "
                + summary.count() + " samples";
    }

    /**
     * Formats a duration in milliseconds with as many digits as it is worth.
     * <p>
     * A chunk load is a fraction of a millisecond and a stutter is three digits of them, so a fixed
     * number of decimals would either drop the interesting part of the small figures or print noise
     * after the large ones.
     * </p>
     *
     * @param value the duration in milliseconds
     * @return the formatted duration, without a unit
     */
    @Contract(pure = true)
    static String millis(double value) {
        if (value >= 100.0) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value >= 10.0) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Formats a rate of chunks per second.
     *
     * @param value the rate in chunks per second
     * @return the formatted rate, without a unit
     */
    @Contract(pure = true)
    static String rate(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
