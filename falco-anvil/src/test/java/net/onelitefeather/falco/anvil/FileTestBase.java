package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The {@link FileTestBase} is a base class for all tests that need a temporary directory.
 * It will create a temporary directory for each test and delete it after the test is finished.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 0.1.0
 */
abstract class FileTestBase {

    @TempDir
    protected Path tempDir;

    @AfterEach
    void tearDown() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir)) {
            for (Path file : stream) {
                Files.delete(file);
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }
}
