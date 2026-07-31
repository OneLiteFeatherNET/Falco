package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the report. The claims checked here are the ones which turn a number into a measurement:
 * that the conditions are named, that the spread is printed, that the two phases stay apart, and
 * that the output says what it is not.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class DemoReportTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    private static final DemoReport.Environment ENVIRONMENT = new DemoReport.Environment(
            16, "OpenJDK 64-Bit Server VM 25 (Eclipse Adoptium)", "Linux 6.1.0 (amd64)", 4L * 1024 * 1024 * 1024, "1.21.11"
    );

    /**
     * Builds a located world for the report.
     *
     * @return a world in the dimension layout
     */
    private WorldSearchResult.Located world() {
        return new WorldSearchResult.Located(
                Path.of("/worlds/survival"),
                Path.of("/worlds/survival/dimensions/minecraft/overworld/region"),
                OVERWORLD,
                false
        );
    }

    /**
     * Builds options with the given thread count and ten measured rounds.
     *
     * @param threads the thread count of the run
     * @return the options of the run
     */
    private DemoOptions options(int threads) {
        return new DemoOptions(LoaderKind.FALCO, threads, 64, 2, 4, OVERWORLD);
    }

    /**
     * Builds a result whose rounds have the given durations in milliseconds.
     *
     * @param warmupMillis      the durations of the warm-up rounds
     * @param measurementMillis the durations of the measured rounds
     * @return the result of a run
     */
    private LoadMeasurement.Result result(long[] warmupMillis, long[] measurementMillis) {
        return new LoadMeasurement.Result(rounds(warmupMillis), rounds(measurementMillis));
    }

    /**
     * Turns durations in milliseconds into rounds which each loaded 64 chunks.
     *
     * @param millis the durations in milliseconds
     * @return the rounds
     */
    private List<LoadMeasurement.Round> rounds(long[] millis) {
        return java.util.Arrays.stream(millis)
                .mapToObj(value -> new LoadMeasurement.Round(value * 1_000_000L, 64))
                .toList();
    }

    /**
     * Collapses every run of whitespace into a single space.
     * <p>
     * The report wraps its prose at the width of its own rules, so a sentence a test looks for is
     * split across lines at a position that changes whenever the sentence is edited. Searching the
     * flattened text asserts what the report says rather than where it happens to break.
     * </p>
     *
     * @param report the rendered report
     * @return the report as a single line
     */
    private String flat(String report) {
        return report.replaceAll("\\s+", " ");
    }

    /**
     * Renders a report of a settled run.
     *
     * @param threads the thread count of the run
     * @return the rendered report
     */
    private String render(int threads) {
        return DemoReport.render(
                options(threads),
                world(),
                64,
                result(new long[]{400, 130}, new long[]{100, 102, 98, 100}),
                ENVIRONMENT
        );
    }

    @Test
    void testTheConditionsAreNamed() {
        String report = render(4);

        assertTrue(report.contains("Threads"), report);
        assertTrue(report.contains("Chunks per round"), report);
        assertTrue(report.contains("Processors"), report);
        assertTrue(report.contains("OpenJDK 64-Bit Server VM 25"), report);
        assertTrue(report.contains("Linux 6.1.0 (amd64)"), report);
        assertTrue(report.contains("4096 MiB"), report);
        assertTrue(report.contains("minecraft:overworld"), report);
        assertTrue(report.contains("/worlds/survival"), report);
    }

    @Test
    void testTheLoaderIsNamedByItsClass() {
        assertTrue(render(4).contains("net.onelitefeather.falco.anvil.FalcoAnvilLoader"));
    }

    @Test
    void testTheWarmUpIsReportedApartFromTheMeasurement() {
        String report = render(4);
        int warmup = report.indexOf("Warm-up");
        int measurement = report.indexOf("Measurement");

        assertTrue(warmup > 0, report);
        assertTrue(measurement > warmup, report);
        // The 400 ms first round is printed, and it is above the warm-up heading only.
        assertTrue(report.substring(warmup, measurement).contains("400"), report);
    }

    @Test
    void testTheSpreadIsPrintedNextToTheMean() {
        String report = render(4);

        assertTrue(report.contains("±"), report);
        assertTrue(flat(report).contains("sample standard deviation"), report);
        assertTrue(flat(report).contains("not a confidence interval"), report);
    }

    @Test
    void testTheReportSaysItIsNotABenchmark() {
        String report = render(4);

        assertTrue(flat(report).contains("not a benchmark"), report);
        assertTrue(report.contains("falco-benchmarks"), report);
    }

    @Test
    void testTheReportWarnsThatTheAdvantageNeedsConcurrency() {
        String report = flat(render(1));

        assertTrue(report.contains("lock granularity"), report);
        assertTrue(report.contains("single threaded"), report);
        assertTrue(report.contains("holds for 1 thread and for nothing else"), report);
    }

    @Test
    void testASettledRunCarriesNoNoiseWarning() {
        assertFalse(flat(render(4)).contains("more than the difference this demo can resolve"));
    }

    @Test
    void testANoisyRunIsCalledOut() {
        String report = DemoReport.render(
                options(4),
                world(),
                64,
                result(new long[]{400}, new long[]{100, 300, 80, 220}),
                ENVIRONMENT
        );

        assertTrue(flat(report).contains("more than the difference this demo can resolve"), report);
    }

    @Test
    void testDifferingChunkCountsAreCalledOut() {
        LoadMeasurement.Result result = new LoadMeasurement.Result(
                List.of(),
                List.of(
                        new LoadMeasurement.Round(100_000_000L, 64),
                        new LoadMeasurement.Round(100_000_000L, 61),
                        new LoadMeasurement.Round(100_000_000L, 64)
                )
        );

        String report = DemoReport.render(options(4), world(), 64, result, ENVIRONMENT);

        assertTrue(report.contains("differs between rounds and should not"), report);
    }

    @Test
    void testASkippedWarmUpIsSaidOutLoud() {
        LoadMeasurement.Result result = result(new long[]{}, new long[]{100, 102, 98, 100});

        String report = DemoReport.render(options(4), world(), 64, result, ENVIRONMENT);

        assertTrue(report.contains("--warmup=0"), report);
    }

    @Test
    void testFiguresAreCutToThreeSignificantDigits() {
        // 123456789 ns is 123.456789 ms and has to be printed as 123, not as the whole expansion.
        LoadMeasurement.Result result = new LoadMeasurement.Result(
                List.of(),
                List.of(
                        new LoadMeasurement.Round(123_456_789L, 64),
                        new LoadMeasurement.Round(123_456_789L, 64)
                )
        );

        String report = DemoReport.render(options(4), world(), 64, result, ENVIRONMENT);

        assertTrue(report.contains("123 ms"), report);
        assertFalse(report.contains("123.456"), report);
    }

    @Test
    void testAMissingWorldSaysWhatIsWrongAndWhereTheWorldGoes() {
        String message = DemoReport.missingWorld(
                Path.of("/repo/falco-demo/world"),
                new WorldSearchResult.Missing("/repo/falco-demo/world is empty")
        );

        assertTrue(message.contains("/repo/falco-demo/world is empty"), message);
        assertTrue(message.contains("Where the world goes"), message);
        assertTrue(message.contains("world ROOT"), message);
        assertTrue(message.contains("level.dat"), message);
        assertTrue(message.contains("runFalcoLoader"), message);
        assertTrue(message.contains("runMinestomLoader"), message);
    }

    @Test
    void testAMissingWorldCarriesNoStackTrace() {
        String message = DemoReport.missingWorld(Path.of("/repo/falco-demo/world"), new WorldSearchResult.Missing("it is empty"));

        assertFalse(message.contains("Exception"), message);
        assertFalse(message.contains("\tat "), message);
    }

    @Test
    void testARefusedCommandLineListsTheOptions() {
        String message = DemoReport.invalidOptions("--rounds must be at least 2");

        assertTrue(message.contains("--rounds must be at least 2"), message);
        assertTrue(message.contains("--threads"), message);
        assertTrue(message.contains("--dimension"), message);
        assertTrue(message.contains("-Pthreads"), message);
    }
}
