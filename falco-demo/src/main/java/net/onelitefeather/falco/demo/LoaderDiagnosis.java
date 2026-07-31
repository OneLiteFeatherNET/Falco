package net.onelitefeather.falco.demo;

import net.minestom.server.instance.ChunkLoader;
import net.onelitefeather.falco.anvil.AnvilDiagnostics;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;

/**
 * The {@link LoaderDiagnosis} record is what the demo knows about a loader after a run, taken out
 * of the loader before it is closed.
 * <p>
 * It exists so the report can be rendered from fixed values in a test rather than from a live
 * loader, and so the demo asks the loader exactly once. The counters keep rising while a run is
 * still going, and a report which read them field by field could print a set of numbers that never
 * existed at the same moment.
 * </p>
 * <p>
 * Only the Falco loader carries counters. Minestom's {@code AnvilLoader} skips chunks for the same
 * reasons and reports none of it, which is why {@link #of(ChunkLoader)} returns {@code null} for
 * it instead of pretending to have zeroes.
 * </p>
 *
 * @param regionDirectory             the directory the loader resolved and read from
 * @param chunksSkippedWithoutRegionFile the chunks which were skipped because no region file exists
 * @param chunksSkippedWithoutEntry   the chunks whose region file holds no entry for them
 * @param chunksSkippedAsPartial      the chunks which are not fully generated
 * @param partialChunkStatuses        the amount of skipped chunks per stored status value
 * @param errors                      the chunks which failed to load
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public record LoaderDiagnosis(
        Path regionDirectory,
        long chunksSkippedWithoutRegionFile,
        long chunksSkippedWithoutEntry,
        long chunksSkippedAsPartial,
        Map<String, Long> partialChunkStatuses,
        long errors
) {

    /**
     * Takes the diagnosis of a loader which keeps one.
     *
     * @param loader the loader the run used, wrapped or not
     * @return the diagnosis of the loader, or null if the loader keeps no counters
     */
    @Contract(pure = true)
    public static @Nullable LoaderDiagnosis of(ChunkLoader loader) {
        // The server hands its loader to a timing wrapper, and stopping at that wrapper would
        // report nothing for the run somebody is actually watching.
        if (loader instanceof TimingChunkLoader timing) {
            return of(timing.delegate());
        }
        if (!(loader instanceof FalcoAnvilLoader falco)) {
            return null;
        }

        AnvilDiagnostics diagnostics = falco.diagnostics();
        return new LoaderDiagnosis(
                falco.regionDirectory(),
                diagnostics.chunksSkippedWithoutRegionFile(),
                diagnostics.chunksSkippedWithoutEntry(),
                diagnostics.chunksSkippedAsPartial(),
                diagnostics.partialChunkStatuses(),
                diagnostics.errors()
        );
    }

    /**
     * Returns the amount of chunks which were skipped for any of the three reasons.
     *
     * @return the amount of skipped chunks
     */
    @Contract(pure = true)
    public long chunksSkipped() {
        return this.chunksSkippedWithoutRegionFile + this.chunksSkippedWithoutEntry + this.chunksSkippedAsPartial;
    }
}
