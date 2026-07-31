package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void testTheCountersStartAtZero() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        assertEquals(0, diagnostics.chunksLoaded());
        assertEquals(0, diagnostics.chunksSaved());
        assertEquals(0, diagnostics.errors());
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
}
