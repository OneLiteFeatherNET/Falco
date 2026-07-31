package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import net.minestom.server.instance.ChunkLoader;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the bridge between the loader under measurement and the explanation the demo prints for a
 * run which returned nothing.
 * <p>
 * The demo holds its loader as a {@link ChunkLoader}, so reaching the counters is a question of
 * which loader is running rather than a given. The two cases are exactly the two run tasks, and
 * both of them have to end in something the user can read.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class LoaderDiagnosisTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    @TempDir
    private Path worldRoot;

    @Test
    void testALoaderWithoutCountersHasNoDiagnosis() {
        assertNull(LoaderDiagnosis.of(ChunkLoader.noop()));
    }

    @Test
    void testTheFalcoLoaderReportsTheDirectoryItResolved() throws IOException {
        Files.createDirectories(this.worldRoot.resolve("region"));

        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD)) {
            LoaderDiagnosis diagnosis = LoaderDiagnosis.of(loader);

            assertNotNull(diagnosis);
            assertEquals(this.worldRoot.resolve("region"), diagnosis.regionDirectory());
            assertEquals(0, diagnosis.chunksSkipped());
        }
    }

    @Test
    void testTheDiagnosisIsFoundThroughTheTimingWrapper() throws IOException {
        // The server wraps its loader for the live metrics. A diagnosis which stopped at the
        // wrapper would report nothing for exactly the run somebody is watching.
        Files.createDirectories(this.worldRoot.resolve("region"));

        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD)) {
            LoaderDiagnosis diagnosis = LoaderDiagnosis.of(new TimingChunkLoader(loader, new LiveMetrics(0L)));

            assertNotNull(diagnosis);
            assertEquals(this.worldRoot.resolve("region"), diagnosis.regionDirectory());
        }
    }

    @Test
    void testTheFalcoLoaderReportsWhyItSkippedAChunk() throws IOException {
        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD)) {
            // No region file exists anywhere below the world root, so every load is skipped for the
            // first of the three reasons and nothing else. The instance is null because the loader
            // returns before it ever looks at it on that path, and building a real one here would
            // pull a whole Minestom server into a module which deliberately starts none.
            assertNull(loader.loadChunk(null, 0, 0));
            assertNull(loader.loadChunk(null, 1, 0));

            LoaderDiagnosis diagnosis = LoaderDiagnosis.of(loader);

            assertNotNull(diagnosis);
            assertEquals(2, diagnosis.chunksSkippedWithoutRegionFile());
            assertEquals(0, diagnosis.chunksSkippedWithoutEntry());
            assertEquals(0, diagnosis.chunksSkippedAsPartial());
            assertEquals(2, diagnosis.chunksSkipped());
        }
    }
}
