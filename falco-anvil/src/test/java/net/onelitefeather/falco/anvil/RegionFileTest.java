package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the region file container which stores the raw chunk payloads.
 * The tests only work on bytes so no Minestom server is required.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class RegionFileTest extends FileTestBase {

    private static final byte[] PAYLOAD = "a compressed chunk payload".getBytes(StandardCharsets.UTF_8);

    /**
     * Creates the path of a region file inside the temporary directory of the test.
     *
     * @return the path of the region file
     */
    private Path regionPath() {
        return this.tempDir.resolve("r.0.0.mca");
    }

    /**
     * A header that is too short leaves no file handle behind.
     * <p>
     * {@code open} closes the channel itself when the header cannot be read, and the catch which
     * does that has to list every type {@code readHeader} can throw. A {@link RegionFormatException}
     * is not an {@link IOException}, so leaving it out of that catch returns from a broken header
     * with the channel still open. Measured before the fix: one descriptor on the file. After: none.
     * </p>
     * <p>
     * The check counts open descriptors through {@code /proc/self/fd} where that exists, because the
     * consequence is invisible otherwise — on Windows the leak shows up as a file that can no longer
     * be moved, which is what the fallback exercises.
     * </p>
     *
     * @throws Exception if the probe file cannot be written
     */
    @Test
    void testABrokenHeaderLeavesNoOpenHandle() throws Exception {
        Files.write(regionPath(), new byte[RegionConstants.HEADER_SIZE - 1]);

        assertThrows(RegionFormatException.class, () -> RegionFile.open(regionPath()));

        Path descriptors = Path.of("/proc/self/fd");

        if (Files.isDirectory(descriptors)) {
            assertEquals(0, openDescriptorsOn(descriptors, regionPath()),
                    "the failed open() left a channel on the file");
            return;
        }

        Path moved = this.tempDir.resolve("moved.mca");
        Files.move(regionPath(), moved);
        Files.move(moved, regionPath());
    }

    /**
     * Counts how many descriptors of this process point at the given file.
     *
     * @param descriptors the descriptor directory of this process
     * @param target      the file to count descriptors for
     * @return the amount of open descriptors on the file
     * @throws IOException if the descriptor directory cannot be listed
     */
    private static int openDescriptorsOn(Path descriptors, Path target) throws IOException {
        int open = 0;

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(descriptors)) {
            for (Path entry : entries) {
                try {
                    if (Files.readSymbolicLink(entry).equals(target)) {
                        open++;
                    }
                } catch (IOException ignored) {
                    // A descriptor which disappeared while this loop ran is not one of ours.
                }
            }
        }
        return open;
    }

    @Test
    void testOpeningAMissingFileCreatesTheHeader() throws Exception {
        try (RegionFile _ = RegionFile.open(regionPath())) {
            assertTrue(Files.exists(regionPath()));
        }
        assertEquals(RegionConstants.HEADER_SIZE, Files.size(regionPath()));
    }

    @Test
    void testReadReturnsNullForAnAbsentChunk() throws Exception {
        try (RegionFile region = RegionFile.open(regionPath())) {
            assertNull(region.readRaw(0, 0));
            assertFalse(region.hasChunk(0, 0));
        }
    }

    @Test
    void testWrittenChunkCanBeReadBack() throws Exception {
        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(3, 7, ChunkCompression.ZLIB, PAYLOAD);

            RegionFile.RawChunk chunk = region.readRaw(3, 7);

            assertNotNull(chunk);
            assertEquals(ChunkCompression.ZLIB, chunk.compression());
            assertArrayEquals(PAYLOAD, chunk.payload());
            assertTrue(region.hasChunk(3, 7));
        }
    }

    @Test
    void testChunksSurviveAReopen() throws Exception {
        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(1, 1, ChunkCompression.ZLIB, PAYLOAD);
        }

        try (RegionFile region = RegionFile.open(regionPath())) {
            RegionFile.RawChunk chunk = region.readRaw(1, 1);

            assertNotNull(chunk);
            assertArrayEquals(PAYLOAD, chunk.payload());
        }
    }

    @Test
    void testDifferentChunksDoNotOverwriteEachOther() throws Exception {
        byte[] other = "a completely different payload".getBytes(StandardCharsets.UTF_8);

        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(0, 0, ChunkCompression.ZLIB, PAYLOAD);
            region.writeRaw(31, 31, ChunkCompression.GZIP, other);

            assertArrayEquals(PAYLOAD, assertPresent(region.readRaw(0, 0)).payload());
            assertArrayEquals(other, assertPresent(region.readRaw(31, 31)).payload());
        }
    }

    @Test
    void testRewritingAChunkWithALargerPayloadKeepsTheNeighbourIntact() throws Exception {
        byte[] large = new byte[RegionConstants.SECTOR_SIZE * 3];
        RandomGenerator.getDefault().nextBytes(large);
        byte[] neighbour = "the neighbour must stay readable".getBytes(StandardCharsets.UTF_8);

        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(0, 0, ChunkCompression.ZLIB, PAYLOAD);
            region.writeRaw(1, 0, ChunkCompression.ZLIB, neighbour);
            region.writeRaw(0, 0, ChunkCompression.ZLIB, large);

            assertArrayEquals(large, assertPresent(region.readRaw(0, 0)).payload());
            assertArrayEquals(neighbour, assertPresent(region.readRaw(1, 0)).payload());
        }
    }

    @Test
    void testTheFileStaysAlignedToTheSectorSize() throws Exception {
        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(0, 0, ChunkCompression.ZLIB, PAYLOAD);
            region.writeRaw(5, 5, ChunkCompression.ZLIB, new byte[RegionConstants.SECTOR_SIZE + 17]);
        }

        assertEquals(0, Files.size(regionPath()) % RegionConstants.SECTOR_SIZE);
    }

    @Test
    void testTheLengthFieldFollowsTheSpecification() throws Exception {
        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(0, 0, ChunkCompression.ZLIB, PAYLOAD);
        }

        // The specification defines the length field as the compression byte plus the payload.
        // Minestom writes four bytes too many here, this loader must not repeat that.
        assertEquals(PAYLOAD.length + 1, readChunkLengthField());
    }

    @Test
    void testDeletingAChunkClearsItsEntry() throws Exception {
        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(2, 2, ChunkCompression.ZLIB, PAYLOAD);
            region.delete(2, 2);

            assertFalse(region.hasChunk(2, 2));
            assertNull(region.readRaw(2, 2));
        }
    }

    @Test
    void testAnOversizedChunkIsStoredInAnExternalFile() throws Exception {
        byte[] oversized = new byte[RegionConstants.MAX_SECTORS_PER_CHUNK * RegionConstants.SECTOR_SIZE + 1];
        RandomGenerator.getDefault().nextBytes(oversized);

        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(4, 6, ChunkCompression.ZLIB, oversized);

            assertTrue(Files.exists(this.tempDir.resolve("c.4.6.mcc")));
            assertArrayEquals(oversized, assertPresent(region.readRaw(4, 6)).payload());
        }
    }

    @Test
    void testShrinkingAnExternalChunkRemovesTheExternalFile() throws Exception {
        byte[] oversized = new byte[RegionConstants.MAX_SECTORS_PER_CHUNK * RegionConstants.SECTOR_SIZE + 1];
        RandomGenerator.getDefault().nextBytes(oversized);

        try (RegionFile region = RegionFile.open(regionPath())) {
            region.writeRaw(4, 6, ChunkCompression.ZLIB, oversized);
            region.writeRaw(4, 6, ChunkCompression.ZLIB, PAYLOAD);

            assertFalse(Files.exists(this.tempDir.resolve("c.4.6.mcc")));
            assertArrayEquals(PAYLOAD, assertPresent(region.readRaw(4, 6)).payload());
        }
    }

    @Test
    void testAFileWithATruncatedHeaderIsRejected() throws Exception {
        Files.write(regionPath(), new byte[RegionConstants.SECTOR_SIZE]);

        assertThrows(RegionFormatException.class, () -> RegionFile.open(regionPath()).close());
    }

    @Test
    void testAUsageAfterCloseIsRejected() throws Exception {
        RegionFile region = RegionFile.open(regionPath());
        region.close();

        // Still an IOException, and deliberately so: "already closed" is a lifecycle failure of the
        // caller, not something the stored bytes got wrong. Rule 4 of the design keeps real IO and
        // lifecycle refusals on java.io.IOException.
        assertThrows(IOException.class, () -> region.readRaw(0, 0));
    }

    @Test
    void testConcurrentWritesNeverCorruptEachOther() throws IOException, RegionFormatException, InterruptedException, ExecutionException {
        int chunkCount = 64;
        List<byte[]> payloads = new ArrayList<>(chunkCount);

        for (int i = 0; i < chunkCount; i++) {
            byte[] payload = new byte[512 + i * 64];
            RandomGenerator.getDefault().nextBytes(payload);
            payloads.add(payload);
        }

        try (RegionFile region = RegionFile.open(regionPath());
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(chunkCount);

            for (int i = 0; i < chunkCount; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    region.writeRaw(index % 32, index / 32, ChunkCompression.ZLIB, payloads.get(index));
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }

            for (int i = 0; i < chunkCount; i++) {
                assertArrayEquals(payloads.get(i), assertPresent(region.readRaw(i % 32, i / 32)).payload(), "chunk " + i + " was corrupted");
            }
        }
    }

    /**
     * Reads the length field of the chunk which is stored in the first data sector.
     *
     * @return the value of the length field
     * @throws IOException if the region file cannot be read
     */
    private int readChunkLengthField() throws Exception {
        try (FileChannel channel = FileChannel.open(regionPath(), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            channel.read(buffer, RegionConstants.HEADER_SIZE);
            return buffer.flip().getInt();
        }
    }

    /**
     * Asserts that the given chunk is present and returns it.
     *
     * @param chunk the chunk to check
     * @return the given chunk
     */
    private RegionFile.RawChunk assertPresent(RegionFile.RawChunk chunk) {
        assertNotNull(chunk);
        return chunk;
    }
}
