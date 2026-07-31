package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the search for the world. Every case here is a way of ending up with zero loaded chunks,
 * and the point of the search is that each of them produces its own sentence instead of the same
 * empty measurement.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class WorldLocatorTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    @TempDir
    private Path worldsDirectory;

    /**
     * Creates a region directory with one region file in it.
     *
     * @param regionDirectory the directory to create
     * @throws IOException if the directory or the file cannot be created
     */
    private void withRegionFile(Path regionDirectory) throws IOException {
        Files.createDirectories(regionDirectory);
        Files.write(regionDirectory.resolve("r.0.0.mca"), new byte[8192]);
    }

    /**
     * Asserts that the search failed and returns the reason.
     *
     * @param result the outcome of the search
     * @return the reason the search gave
     */
    private String reasonOf(WorldSearchResult result) {
        return assertInstanceOf(WorldSearchResult.Missing.class, result).reason();
    }

    @Test
    void testAnAbsentDirectoryIsReportedByName() {
        Path absent = this.worldsDirectory.resolve("nowhere");

        String reason = reasonOf(WorldLocator.locate(absent, OVERWORLD));

        assertTrue(reason.contains(absent.toString()), reason);
        assertTrue(reason.contains("does not exist"), reason);
    }

    @Test
    void testAnEmptyDirectoryIsReportedAsEmpty() {
        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("is empty"), reason);
    }

    @Test
    void testThePlaceholderFilesDoNotCountAsAWorld() throws IOException {
        Files.writeString(this.worldsDirectory.resolve(".gitkeep"), "");
        Files.writeString(this.worldsDirectory.resolve(".gitignore"), "*");

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("is empty"), reason);
    }

    @Test
    void testRegionFilesDroppedDirectlyAreExplained() throws IOException {
        // The most likely mistake: copying the contents of region/ instead of the world folder.
        Files.write(this.worldsDirectory.resolve("r.0.0.mca"), new byte[8192]);

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("world root"), reason);
        assertTrue(reason.contains(".mca"), reason);
    }

    @Test
    void testADirectoryWhichIsNoWorldIsNamed() throws IOException {
        Files.createDirectories(this.worldsDirectory.resolve("holiday-photos"));

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("holiday-photos"), reason);
        assertTrue(reason.contains("level.dat"), reason);
    }

    @Test
    void testTwoWorldsAreRefusedInsteadOfGuessed() throws IOException {
        withRegionFile(this.worldsDirectory.resolve("first").resolve("region"));
        withRegionFile(this.worldsDirectory.resolve("second").resolve("region"));

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("first"), reason);
        assertTrue(reason.contains("second"), reason);
        assertTrue(reason.contains("more than one"), reason);
    }

    @Test
    void testTheDimensionLayoutIsFound() throws IOException {
        Path worldRoot = this.worldsDirectory.resolve("survival");
        Path regionDirectory = worldRoot.resolve("dimensions/minecraft/overworld/region");
        withRegionFile(regionDirectory);

        WorldSearchResult.Located located = assertInstanceOf(
                WorldSearchResult.Located.class,
                WorldLocator.locate(this.worldsDirectory, OVERWORLD)
        );

        assertEquals(worldRoot, located.worldRoot());
        assertEquals(regionDirectory, located.regionDirectory());
        assertEquals(OVERWORLD, located.dimension());
        assertFalse(located.legacyLayout());
    }

    @Test
    void testTheLegacyLayoutIsFoundAndMarked() throws IOException {
        // The mark matters: Minestom's two argument constructor has no fallback to world/region,
        // so a legacy world has to be given to its single argument constructor instead.
        Path worldRoot = this.worldsDirectory.resolve("old-world");
        Path regionDirectory = worldRoot.resolve("region");
        withRegionFile(regionDirectory);

        WorldSearchResult.Located located = assertInstanceOf(
                WorldSearchResult.Located.class,
                WorldLocator.locate(this.worldsDirectory, OVERWORLD)
        );

        assertEquals(regionDirectory, located.regionDirectory());
        assertTrue(located.legacyLayout());
    }

    @Test
    void testTheDimensionLayoutWinsOverTheLegacyOne() throws IOException {
        Path worldRoot = this.worldsDirectory.resolve("both");
        withRegionFile(worldRoot.resolve("region"));
        withRegionFile(worldRoot.resolve("dimensions/minecraft/overworld/region"));

        WorldSearchResult.Located located = assertInstanceOf(
                WorldSearchResult.Located.class,
                WorldLocator.locate(this.worldsDirectory, OVERWORLD)
        );

        assertEquals(worldRoot.resolve("dimensions/minecraft/overworld/region"), located.regionDirectory());
        assertFalse(located.legacyLayout());
    }

    @Test
    void testTheWorldsDirectoryItselfMayBeTheWorld() throws IOException {
        withRegionFile(this.worldsDirectory.resolve("region"));

        WorldSearchResult.Located located = assertInstanceOf(
                WorldSearchResult.Located.class,
                WorldLocator.locate(this.worldsDirectory, OVERWORLD)
        );

        assertEquals(this.worldsDirectory, located.worldRoot());
        assertTrue(located.legacyLayout());
    }

    @Test
    void testAWorldForAnotherDimensionIsReportedWithBothExpectedPaths() throws IOException {
        Path worldRoot = this.worldsDirectory.resolve("survival");
        withRegionFile(worldRoot.resolve("dimensions/minecraft/the_nether/region"));
        Files.writeString(worldRoot.resolve("level.dat"), "");

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("minecraft:overworld"), reason);
        assertTrue(reason.contains("dimensions"), reason);
        assertTrue(reason.contains(worldRoot.resolve("region").toString()), reason);
    }

    @Test
    void testARegionDirectoryWithoutRegionFilesIsReported() throws IOException {
        Path worldRoot = this.worldsDirectory.resolve("survival");
        Files.createDirectories(worldRoot.resolve("region"));

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("no .mca file"), reason);
    }

    @Test
    void testAnEmptyDimensionDirectoryNextToAFilledLegacyOneIsCalledOut() throws IOException {
        // The trap this sentence exists for: the region files are right there in 'region', and an
        // empty dimension directory hides them from every loader because the dimension layout wins
        // as long as its directory exists. Without the hint the reader is pointed at a directory
        // they never created and told there is nothing in it.
        Path worldRoot = this.worldsDirectory.resolve("survival");
        Files.createDirectories(worldRoot.resolve("dimensions/minecraft/overworld/region"));
        withRegionFile(worldRoot.resolve("region"));

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("no .mca file"), reason);
        assertTrue(reason.contains(worldRoot.resolve("region").toString()), reason);
        assertTrue(reason.contains("wins over"), reason);
    }

    @Test
    void testAnEmptyRegionDirectoryWithoutALegacyOneCarriesNoHint() throws IOException {
        Path worldRoot = this.worldsDirectory.resolve("survival");
        Files.createDirectories(worldRoot.resolve("dimensions/minecraft/overworld/region"));

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("no .mca file"), reason);
        assertFalse(reason.contains("wins over"), reason);
    }

    @Test
    void testAWorldIsRecognisedByItsLevelDataAlone() throws IOException {
        // A world whose region directory was moved away still has to be recognised, so the reason
        // can name the missing region directory instead of claiming the folder is not a world.
        Path worldRoot = this.worldsDirectory.resolve("survival");
        Files.createDirectories(worldRoot);
        Files.writeString(worldRoot.resolve("level.dat"), "");

        String reason = reasonOf(WorldLocator.locate(this.worldsDirectory, OVERWORLD));

        assertTrue(reason.contains("no region directory"), reason);
    }
}
