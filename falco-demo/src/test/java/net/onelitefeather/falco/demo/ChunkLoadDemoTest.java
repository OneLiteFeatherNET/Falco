package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests where the demo looks for the world. The gradle tasks set the property, so the fallbacks are
 * only exercised by a run started by hand — which is exactly why they need a test rather than a
 * trial run.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class ChunkLoadDemoTest {

    @TempDir
    private Path workingDirectory;

    @Test
    void testTheConfiguredDirectoryWins() throws IOException {
        Files.createDirectories(this.workingDirectory.resolve("world"));
        Path configured = this.workingDirectory.resolve("somewhere-else");

        assertEquals(configured, ChunkLoadDemo.worldsDirectory(configured.toString(), this.workingDirectory));
    }

    @Test
    void testABlankPropertyFallsBackToTheWorkingDirectory() throws IOException {
        Path expected = this.workingDirectory.resolve("world");
        Files.createDirectories(expected);

        assertEquals(expected, ChunkLoadDemo.worldsDirectory("   ", this.workingDirectory));
    }

    @Test
    void testARunFromInsideTheModuleFindsItsOwnWorldDirectory() throws IOException {
        Path expected = this.workingDirectory.resolve("world");
        Files.createDirectories(expected);

        assertEquals(expected, ChunkLoadDemo.worldsDirectory(null, this.workingDirectory));
    }

    @Test
    void testARunFromTheRepositoryRootFindsTheModule() {
        assertEquals(
                this.workingDirectory.resolve("falco-demo").resolve("world"),
                ChunkLoadDemo.worldsDirectory(null, this.workingDirectory)
        );
    }
}
