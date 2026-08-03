package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the diagnostics which throttle repeating warnings and collect the counters for the
 * summary of a loader.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class AnvilDiagnosticsTest {

    @Test
    void testTheFirstReportOfANameIsAllowed() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertTrue(diagnostics.reportUnknownBlock("minecraft:custom"));
    }

    @Test
    void testARepeatedNameIsSuppressed() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        diagnostics.reportUnknownBlock("minecraft:custom");

        assertFalse(diagnostics.reportUnknownBlock("minecraft:custom"));
    }

    @Test
    void testDifferentNamesAreReportedSeparately() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertTrue(diagnostics.reportUnknownBlock("minecraft:a"));
        assertTrue(diagnostics.reportUnknownBlock("minecraft:b"));
        assertEquals(2, diagnostics.unknownBlockCount());
    }

    @Test
    void testBlocksAndBiomesUseSeparateBudgets() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        diagnostics.reportUnknownBlock("minecraft:shared");

        assertTrue(diagnostics.reportUnknownBiome("minecraft:shared"));
    }

    @Test
    void testTheTrackedNamesAreCappedToProtectTheHeap() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        for (int i = 0; i < AnvilDiagnostics.MAX_TRACKED_NAMES; i++) {
            assertTrue(diagnostics.reportUnknownBlock("minecraft:block_" + i));
        }

        assertFalse(diagnostics.reportUnknownBlock("minecraft:one_too_many"));
        assertEquals(AnvilDiagnostics.MAX_TRACKED_NAMES, diagnostics.unknownBlockCount());
    }

    @Test
    void testAOnceOnlyFlagOnlyPassesTheFirstTime() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertTrue(diagnostics.reportPartialChunk());
        assertFalse(diagnostics.reportPartialChunk());
    }

    @Test
    void testAReportWithoutAStatusIsCountedUnderTheUnknownStatus() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        diagnostics.reportPartialChunk();

        assertEquals(1, diagnostics.chunksSkippedAsPartial());
        assertEquals(Map.of(AnvilDiagnostics.UNKNOWN_STATUS, 1L), diagnostics.partialChunkStatuses());
    }

    @Test
    void testEveryMissingRegionFileIsCountedEvenThoughOnlyOneIsLogged() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertTrue(diagnostics.reportMissingRegionFile());
        assertFalse(diagnostics.reportMissingRegionFile());
        assertFalse(diagnostics.reportMissingRegionFile());
        assertEquals(3, diagnostics.chunksSkippedWithoutRegionFile());
    }

    @Test
    void testEveryMissingChunkEntryIsCountedEvenThoughOnlyOneIsLogged() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertTrue(diagnostics.reportMissingChunkEntry());
        assertFalse(diagnostics.reportMissingChunkEntry());
        assertEquals(2, diagnostics.chunksSkippedWithoutEntry());
    }

    @Test
    void testEveryPartialChunkIsCountedUnderItsOwnStatus() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        diagnostics.reportPartialChunk("minecraft:features");
        diagnostics.reportPartialChunk("minecraft:features");
        diagnostics.reportPartialChunk("minecraft:surface");

        assertEquals(3, diagnostics.chunksSkippedAsPartial());
        assertEquals(Map.of("minecraft:features", 2L, "minecraft:surface", 1L), diagnostics.partialChunkStatuses());
    }

    @Test
    void testEveryStatusValueIsLoggedExactlyOnce() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertTrue(diagnostics.reportPartialChunk("minecraft:features"));
        assertFalse(diagnostics.reportPartialChunk("minecraft:features"));
        assertTrue(diagnostics.reportPartialChunk("minecraft:surface"));
    }

    @Test
    void testTheThreeSkipReasonsAreCountedSeparately() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        diagnostics.reportMissingRegionFile();
        diagnostics.reportMissingChunkEntry();
        diagnostics.reportMissingChunkEntry();
        diagnostics.reportPartialChunk("minecraft:features");
        diagnostics.reportPartialChunk("minecraft:features");
        diagnostics.reportPartialChunk("minecraft:features");

        assertEquals(1, diagnostics.chunksSkippedWithoutRegionFile());
        assertEquals(2, diagnostics.chunksSkippedWithoutEntry());
        assertEquals(3, diagnostics.chunksSkippedAsPartial());
        assertEquals(6, diagnostics.chunksSkipped());
    }

    @Test
    void testTheTrackedStatusValuesAreCappedWithoutLosingTheTotal() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        for (int i = 0; i < AnvilDiagnostics.MAX_TRACKED_NAMES; i++) {
            assertTrue(diagnostics.reportPartialChunk("falco:status_" + i));
        }

        assertFalse(diagnostics.reportPartialChunk("falco:one_too_many"));
        assertEquals(AnvilDiagnostics.MAX_TRACKED_NAMES, diagnostics.partialChunkStatuses().size());
        assertEquals(AnvilDiagnostics.MAX_TRACKED_NAMES + 1L, diagnostics.chunksSkippedAsPartial());
    }

    @Test
    void testTheReportedStatusValuesCannotBeChangedFromOutside() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        diagnostics.reportPartialChunk("minecraft:features");
        Map<String, Long> statuses = diagnostics.partialChunkStatuses();

        assertThrows(UnsupportedOperationException.class, () -> statuses.put("falco:injected", 1L));
    }

    @Test
    void testTheCountersStartAtZero() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertEquals(0, diagnostics.chunksLoaded());
        assertEquals(0, diagnostics.chunksSaved());
        assertEquals(0, diagnostics.errors());
        assertEquals(0, diagnostics.chunksSkippedWithoutRegionFile());
        assertEquals(0, diagnostics.chunksSkippedWithoutEntry());
        assertEquals(0, diagnostics.chunksSkippedAsPartial());
        assertEquals(0, diagnostics.chunksSkipped());
        assertTrue(diagnostics.partialChunkStatuses().isEmpty());
    }

    @Test
    void testTheCountersAddUp() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        diagnostics.countChunkLoaded();
        diagnostics.countChunkLoaded();
        diagnostics.countChunkSaved();
        diagnostics.countError();

        assertEquals(2, diagnostics.chunksLoaded());
        assertEquals(1, diagnostics.chunksSaved());
        assertEquals(1, diagnostics.errors());
    }

    @Test
    void testConcurrentReportsElectExactlyOneWinnerPerName() throws InterruptedException, ExecutionException {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 32;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>(threadCount);

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return diagnostics.reportUnknownBlock("minecraft:contested");
                }));
            }
            start.countDown();

            int winners = 0;

            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners, "exactly one thread may report the same name");
        }
    }

    @Test
    void testConcurrentCountingLosesNoIncrement() throws InterruptedException, ExecutionException {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        int threadCount = 16;
        int perThread = 500;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < perThread; j++) {
                        diagnostics.countChunkLoaded();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertEquals((long) threadCount * perThread, diagnostics.chunksLoaded());
    }

    @Test
    void testAnUnsupportedVersionIsCountedUnderItsOwnValue() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertTrue(diagnostics.reportUnsupportedChunkVersion("1976"));
        assertFalse(diagnostics.reportUnsupportedChunkVersion("1976"));
        assertTrue(diagnostics.reportUnsupportedChunkVersion("2724"));

        assertEquals(3, diagnostics.chunksSkippedAsUnsupported());
        assertEquals(Map.of("1976", 2L, "2724", 1L), diagnostics.unsupportedChunkVersions());
    }

    @Test
    void testAChunkWithoutAStoredVersionIsCountedApart() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        diagnostics.reportUnsupportedChunkVersion(AnvilDiagnostics.UNKNOWN_DATA_VERSION);

        assertEquals(Map.of(AnvilDiagnostics.UNKNOWN_DATA_VERSION, 1L),
                diagnostics.unsupportedChunkVersions());
    }

    @Test
    void testTheVersionBreakdownIsSortedByValue() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        diagnostics.reportUnsupportedChunkVersion("2724");
        diagnostics.reportUnsupportedChunkVersion("1976");

        assertEquals(List.of("1976", "2724"),
                List.copyOf(diagnostics.unsupportedChunkVersions().keySet()));
    }
}
