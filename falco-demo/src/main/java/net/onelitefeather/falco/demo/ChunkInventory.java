package net.onelitefeather.falco.demo;

import net.onelitefeather.falco.anvil.RegionConstants;
import org.jetbrains.annotations.Contract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The {@link ChunkInventory} class lists the chunks a world really contains.
 * <p>
 * The demo cannot simply load a square of coordinates around the origin. A world the user hands it
 * may be a small flat map, may start at an offset, and will almost certainly have holes. Loading a
 * coordinate that was never generated costs a header lookup and nothing else, so a measurement over
 * guessed coordinates would mostly measure absent chunks and report a splendid number for both
 * loaders.
 * </p>
 * <p>
 * The listing reads the location table of each region file, which is the same 4 KiB header both
 * loaders read. It is done once before the measurement and never inside it, so which loader is
 * being measured has no influence on which chunks are asked for.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class ChunkInventory {

    /**
     * The name of a region file, with the region coordinates it covers.
     */
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * This class only provides the static scan and is never instantiated.
     */
    private ChunkInventory() {
    }

    /**
     * The position of a chunk in absolute chunk coordinates.
     *
     * @param x the chunk x coordinate
     * @param z the chunk z coordinate
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    public record ChunkPosition(int x, int z) {
    }

    /**
     * Collects the positions of the chunks which are present in the given region directory.
     * <p>
     * The order is deterministic — region files sorted by coordinate, chunks in the order of the
     * location table — so two runs ask for the same chunks in the same order. That matters more
     * than spreading the load: a random selection would change what is being measured between the
     * Falco run and the Minestom run, and the two runs happen in separate processes.
     * </p>
     *
     * @param regionDirectory the directory which holds the {@code .mca} files
     * @param limit           the greatest number of chunks to collect
     * @return the positions of the present chunks, at most {@code limit} of them
     * @throws IOException              if the directory or one of its region files cannot be read
     * @throws IllegalArgumentException if the limit is not positive
     */
    public static List<ChunkPosition> scan(Path regionDirectory, int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("The chunk limit must be positive but was " + limit);
        }

        List<ChunkPosition> positions = new ArrayList<>(Math.min(limit, RegionConstants.ENTRY_COUNT));

        for (Path regionFile : regionFiles(regionDirectory)) {
            Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());

            if (!matcher.matches()) {
                continue;
            }

            collect(regionFile, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), limit, positions);

            if (positions.size() >= limit) {
                break;
            }
        }

        return List.copyOf(positions);
    }

    /**
     * Reads the location table of one region file and appends every chunk it marks as present.
     * A file which is shorter than its own header is skipped rather than reported: a truncated
     * region file is a property of the world the user supplied and not something the demo can
     * repair, and refusing the whole run over one of them would be the worse behaviour.
     *
     * @param regionFile the region file to read
     * @param regionX    the x coordinate of the region
     * @param regionZ    the z coordinate of the region
     * @param limit      the greatest number of chunks in total
     * @param target     the list the positions are appended to
     * @throws IOException if the region file cannot be read
     */
    private static void collect(Path regionFile, int regionX, int regionZ, int limit, List<ChunkPosition> target) throws IOException {
        if (Files.size(regionFile) < RegionConstants.SECTOR_SIZE) {
            return;
        }

        ByteBuffer header = ByteBuffer.allocate(RegionConstants.SECTOR_SIZE);

        try (SeekableByteChannel channel = Files.newByteChannel(regionFile)) {
            while (header.hasRemaining() && channel.read(header) >= 0) {
                // Reading until the buffer is full, because a single read is allowed to return less.
            }
        }

        if (header.hasRemaining()) {
            return;
        }

        header.flip();

        for (int index = 0; index < RegionConstants.ENTRY_COUNT && target.size() < limit; index++) {
            int entry = header.getInt(RegionConstants.locationOffset(index));

            if (!isPresent(entry)) {
                continue;
            }

            int chunkX = regionX * RegionConstants.REGION_SIZE + (index & (RegionConstants.REGION_SIZE - 1));
            int chunkZ = regionZ * RegionConstants.REGION_SIZE + (index >>> 5);
            target.add(new ChunkPosition(chunkX, chunkZ));
        }
    }

    /**
     * Decides whether a location table entry describes a chunk which was written.
     * An entry holds a three byte sector offset and a one byte sector count, and both have to be
     * plausible: an offset below the two header sectors would point into the header itself.
     *
     * @param entry the raw location table entry
     * @return whether the entry describes a present chunk
     */
    @Contract(pure = true)
    private static boolean isPresent(int entry) {
        int offset = entry >>> 8;
        int sectors = entry & 0xFF;
        return sectors > 0 && offset >= RegionConstants.HEADER_SECTORS;
    }

    /**
     * Lists the region files of a directory, sorted by their coordinates.
     *
     * @param regionDirectory the directory which holds the region files
     * @return the region files in a deterministic order
     * @throws IOException if the directory cannot be read
     */
    private static List<Path> regionFiles(Path regionDirectory) throws IOException {
        try (Stream<Path> stream = Files.list(regionDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> REGION_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ChunkInventory::regionZ).thenComparingInt(ChunkInventory::regionX))
                    .toList();
        }
    }

    /**
     * Reads the x coordinate out of a region file name.
     *
     * @param regionFile the region file
     * @return the x coordinate of the region
     */
    private static int regionX(Path regionFile) {
        return coordinate(regionFile, 1);
    }

    /**
     * Reads the z coordinate out of a region file name.
     *
     * @param regionFile the region file
     * @return the z coordinate of the region
     */
    private static int regionZ(Path regionFile) {
        return coordinate(regionFile, 2);
    }

    /**
     * Reads one of the two coordinates out of a region file name.
     *
     * @param regionFile the region file
     * @param group      the capturing group of the coordinate
     * @return the coordinate, or zero for a name which does not match
     */
    private static int coordinate(Path regionFile, int group) {
        Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(group)) : 0;
    }
}
