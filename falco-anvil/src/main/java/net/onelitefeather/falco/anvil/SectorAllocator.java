package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.Contract;

import java.util.BitSet;

/**
 * The {@link SectorAllocator} tracks which sectors of a region file are currently in use.
 * It hands out sector ranges with a first fit strategy and reuses ranges which were freed
 * before. When no gap is large enough the allocator grows the region file virtually by
 * returning a range behind the current end.
 * <p>
 * The class is deliberately free of any file access so the allocation logic can be tested
 * on its own. Instances are not thread safe and must be guarded by the owning region file.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
final class SectorAllocator {

    private final BitSet usedSectors;
    private int totalSectors;

    /**
     * Creates a new allocator which considers the first sectors as permanently used.
     * The header of a region file always occupies {@link RegionConstants#HEADER_SECTORS} sectors.
     *
     * @param totalSectors the amount of sectors the region file currently spans
     */
    SectorAllocator(int totalSectors) {
        int sectors = Math.max(totalSectors, RegionConstants.HEADER_SECTORS);
        this.usedSectors = new BitSet(sectors);
        this.usedSectors.set(0, RegionConstants.HEADER_SECTORS);
        this.totalSectors = sectors;
    }

    /**
     * Allocates a consecutive range of sectors and marks it as used.
     * The allocator reuses a freed gap when one is large enough, otherwise the returned range
     * starts behind the current end of the region file.
     *
     * @param count the amount of sectors to allocate
     * @return the index of the first sector of the allocated range
     * @throws IllegalArgumentException if the given count is not positive
     */
    int allocate(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("The sector count must be positive but was " + count);
        }

        int candidate = this.usedSectors.nextClearBit(RegionConstants.HEADER_SECTORS);

        while (candidate < this.totalSectors) {
            int occupied = this.usedSectors.nextSetBit(candidate);
            int available = occupied == -1 ? this.totalSectors - candidate : occupied - candidate;

            if (available >= count) {
                break;
            }
            candidate = this.usedSectors.nextClearBit(candidate + available);
        }

        this.usedSectors.set(candidate, candidate + count);
        this.totalSectors = Math.max(this.totalSectors, candidate + count);
        return candidate;
    }

    /**
     * Marks an existing range of sectors as used without searching for a free gap.
     * The method is used while reading the header of an already existing region file.
     *
     * @param offset the index of the first sector of the range
     * @param count  the amount of sectors the range spans
     * @throws IllegalArgumentException if the offset or the count is invalid
     * @throws IllegalStateException    if the range overlaps an already reserved range
     */
    void reserve(int offset, int count) {
        if (offset < RegionConstants.HEADER_SECTORS) {
            throw new IllegalArgumentException("The sector offset must not point into the header but was " + offset);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("The sector count must be positive but was " + count);
        }
        int overlap = this.usedSectors.nextSetBit(offset);

        if (overlap != -1 && overlap < offset + count) {
            throw new IllegalStateException("The sector range [" + offset + ", " + (offset + count) + ") overlaps sector " + overlap);
        }

        this.usedSectors.set(offset, offset + count);
        this.totalSectors = Math.max(this.totalSectors, offset + count);
    }

    /**
     * Marks a range of sectors as free so a later allocation can reuse it.
     * The region file is never shrunk, the freed space stays part of the file.
     *
     * @param offset the index of the first sector of the range
     * @param count  the amount of sectors the range spans
     */
    void free(int offset, int count) {
        if (offset < RegionConstants.HEADER_SECTORS || count <= 0) {
            return;
        }
        this.usedSectors.clear(offset, offset + count);
    }

    /**
     * Checks whether the given sector is currently not used by any chunk.
     *
     * @param sector the index of the sector to check
     * @return true if the sector is free, otherwise false
     */
    @Contract(pure = true)
    boolean isFree(int sector) {
        return !this.usedSectors.get(sector);
    }

    /**
     * Returns the amount of sectors the region file currently spans.
     *
     * @return the amount of sectors
     */
    @Contract(pure = true)
    int totalSectors() {
        return this.totalSectors;
    }
}
