package net.onelitefeather.falco.demo;

import net.onelitefeather.falco.anvil.RegionConstants;
import net.onelitefeather.falco.demo.ChunkInventory.ChunkPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the chunk listing against handwritten region headers. A listing which invented coordinates
 * would make both loaders answer from the location table and report a magnificent number for a
 * world that was never touched.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class ChunkInventoryTest {

    @TempDir
    private Path regionDirectory;

    /**
     * Writes a region file whose location table marks the given local chunks as present.
     *
     * @param regionX      the x coordinate of the region
     * @param regionZ      the z coordinate of the region
     * @param localIndices the indices inside the location table which are marked as written
     * @throws IOException if the file cannot be written
     */
    private void writeRegion(int regionX, int regionZ, int... localIndices) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(RegionConstants.HEADER_SIZE);

        for (int index : localIndices) {
            // Two header sectors, so the first payload sector is two, and one sector of length.
            header.putInt(RegionConstants.locationOffset(index), (RegionConstants.HEADER_SECTORS << 8) | 1);
        }

        Files.write(this.regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca"), header.array());
    }

    @Test
    void testAPresentChunkIsListedWithItsAbsoluteCoordinates() throws IOException {
        // Index 33 is local x 1, local z 1, and the region starts at chunk 32, 64.
        writeRegion(1, 2, 33);

        List<ChunkPosition> positions = ChunkInventory.scan(this.regionDirectory, 16);

        assertEquals(List.of(new ChunkPosition(33, 65)), positions);
    }

    @Test
    void testNegativeRegionCoordinatesAreRead() throws IOException {
        writeRegion(-1, -2, 0);

        List<ChunkPosition> positions = ChunkInventory.scan(this.regionDirectory, 16);

        assertEquals(List.of(new ChunkPosition(-32, -64)), positions);
    }

    @Test
    void testAnEmptyEntryIsNotListed() throws IOException {
        writeRegion(0, 0, 5);

        List<ChunkPosition> positions = ChunkInventory.scan(this.regionDirectory, 16);

        assertEquals(1, positions.size());
        assertEquals(new ChunkPosition(5, 0), positions.getFirst());
    }

    @Test
    void testAnEntryPointingIntoTheHeaderIsNotListed() throws IOException {
        // A sector offset below two would address the location table itself, which no writer does.
        ByteBuffer header = ByteBuffer.allocate(RegionConstants.HEADER_SIZE);
        header.putInt(RegionConstants.locationOffset(7), (1 << 8) | 1);
        Files.write(this.regionDirectory.resolve("r.0.0.mca"), header.array());

        assertTrue(ChunkInventory.scan(this.regionDirectory, 16).isEmpty());
    }

    @Test
    void testAnEntryWithoutSectorsIsNotListed() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(RegionConstants.HEADER_SIZE);
        header.putInt(RegionConstants.locationOffset(7), RegionConstants.HEADER_SECTORS << 8);
        Files.write(this.regionDirectory.resolve("r.0.0.mca"), header.array());

        assertTrue(ChunkInventory.scan(this.regionDirectory, 16).isEmpty());
    }

    @Test
    void testTheLimitStopsTheScan() throws IOException {
        writeRegion(0, 0, 0, 1, 2, 3, 4, 5);

        assertEquals(3, ChunkInventory.scan(this.regionDirectory, 3).size());
    }

    @Test
    void testTheOrderIsDeterministicAcrossRegionFiles() throws IOException {
        writeRegion(1, 0, 0);
        writeRegion(0, 0, 0);
        writeRegion(0, 1, 0);

        List<ChunkPosition> positions = ChunkInventory.scan(this.regionDirectory, 16);

        assertEquals(List.of(new ChunkPosition(0, 0), new ChunkPosition(32, 0), new ChunkPosition(0, 32)), positions);
    }

    @Test
    void testAFileWhichIsNotARegionFileIsIgnored() throws IOException {
        writeRegion(0, 0, 0);
        Files.writeString(this.regionDirectory.resolve("session.lock"), "");
        Files.writeString(this.regionDirectory.resolve("r.0.0.mcr"), "");

        assertEquals(1, ChunkInventory.scan(this.regionDirectory, 16).size());
    }

    @Test
    void testATruncatedRegionFileIsSkippedInsteadOfFailingTheRun() throws IOException {
        Files.write(this.regionDirectory.resolve("r.0.0.mca"), new byte[128]);
        writeRegion(1, 0, 0);

        assertEquals(List.of(new ChunkPosition(32, 0)), ChunkInventory.scan(this.regionDirectory, 16));
    }

    @Test
    void testADirectoryWithoutRegionFilesYieldsNothing() throws IOException {
        assertTrue(ChunkInventory.scan(this.regionDirectory, 16).isEmpty());
    }

    @Test
    void testTheResultIsUnmodifiable() throws IOException {
        writeRegion(0, 0, 0);

        List<ChunkPosition> positions = ChunkInventory.scan(this.regionDirectory, 16);

        assertThrows(UnsupportedOperationException.class, () -> positions.add(new ChunkPosition(0, 0)));
    }

    @Test
    void testALimitOfZeroIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ChunkInventory.scan(this.regionDirectory, 0));
    }

    @Test
    void testEveryEntryOfAFullRegionIsListed() throws IOException {
        int[] indices = new int[RegionConstants.ENTRY_COUNT];

        for (int index = 0; index < indices.length; index++) {
            indices[index] = index;
        }

        writeRegion(0, 0, indices);

        List<ChunkPosition> positions = ChunkInventory.scan(this.regionDirectory, RegionConstants.ENTRY_COUNT);

        assertEquals(RegionConstants.ENTRY_COUNT, positions.size());
        assertTrue(positions.contains(new ChunkPosition(31, 31)));
        assertFalse(positions.contains(new ChunkPosition(32, 0)));
    }
}
