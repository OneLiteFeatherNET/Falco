package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

/**
 * The {@link RegionConstants} class holds all layout constants of the Anvil region file format.
 * A region file starts with a two sector header. The first sector contains the location table
 * and the second one the timestamp table. Both tables have an entry for each of the
 * {@code 32 x 32} chunks a region can hold.
 * <p>
 * The class is not meant to be instantiated. It only provides constants and pure helper methods.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class RegionConstants {

    /**
     * The size of a single sector in bytes.
     */
    public static final int SECTOR_SIZE = 4096;

    /**
     * The amount of chunks a region file can address in a single axis.
     */
    public static final int REGION_SIZE = 32;

    /**
     * The amount of chunk entries a region file can address in total.
     */
    public static final int ENTRY_COUNT = REGION_SIZE * REGION_SIZE;

    /**
     * The amount of sectors which are reserved for the location and timestamp table.
     */
    public static final int HEADER_SECTORS = 2;

    /**
     * The size of the complete region file header in bytes.
     */
    public static final int HEADER_SIZE = HEADER_SECTORS * SECTOR_SIZE;

    /**
     * The highest amount of sectors a single chunk entry can address.
     * The sector count is stored in a single byte which limits a chunk to roughly one mebibyte.
     */
    public static final int MAX_SECTORS_PER_CHUNK = 255;

    /**
     * The amount of bytes which are used to store the length of a chunk payload.
     */
    public static final int LENGTH_FIELD_SIZE = Integer.BYTES;

    /**
     * The amount of bytes which are used to store the compression scheme of a chunk payload.
     */
    public static final int COMPRESSION_FIELD_SIZE = Byte.BYTES;

    private RegionConstants() {
    }

    /**
     * Calculates the index a chunk occupies inside the location and timestamp table.
     * The given coordinates are absolute chunk coordinates and are wrapped into the region.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the index of the chunk inside a region table
     */
    @Contract(pure = true)
    public static int index(int chunkX, int chunkZ) {
        return ((chunkZ & (REGION_SIZE - 1)) << 5) | (chunkX & (REGION_SIZE - 1));
    }

    /**
     * Calculates the byte offset of a chunk entry inside the location table.
     *
     * @param index the index of the chunk inside a region table
     * @return the byte offset of the location entry
     */
    @Contract(pure = true)
    public static int locationOffset(int index) {
        return index * Integer.BYTES;
    }

    /**
     * Calculates the byte offset of a chunk entry inside the timestamp table.
     *
     * @param index the index of the chunk inside a region table
     * @return the byte offset of the timestamp entry
     */
    @Contract(pure = true)
    public static int timestampOffset(int index) {
        return SECTOR_SIZE + index * Integer.BYTES;
    }

    /**
     * Converts an absolute chunk coordinate into the coordinate of the region which contains it.
     *
     * @param chunkCoordinate the absolute chunk coordinate
     * @return the region coordinate
     */
    @Contract(pure = true)
    public static int chunkToRegion(int chunkCoordinate) {
        return chunkCoordinate >> 5;
    }

    /**
     * Calculates the amount of sectors which are required to store the given amount of bytes.
     *
     * @param byteLength the amount of bytes to store
     * @return the amount of sectors which are required
     */
    @Contract(pure = true)
    public static int sectorsFor(int byteLength) {
        return (byteLength + SECTOR_SIZE - 1) / SECTOR_SIZE;
    }
}
