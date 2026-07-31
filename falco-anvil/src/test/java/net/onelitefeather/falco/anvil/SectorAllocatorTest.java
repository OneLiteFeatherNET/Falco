package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the sector allocation logic which is the core of the region file space management.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class SectorAllocatorTest {

    @Test
    void testFreshAllocatorReservesTheHeaderSectors() {
        SectorAllocator allocator = new SectorAllocator(RegionConstants.HEADER_SECTORS);

        assertEquals(RegionConstants.HEADER_SECTORS, allocator.totalSectors());
        assertFalse(allocator.isFree(0));
        assertFalse(allocator.isFree(1));
    }

    @Test
    void testAllocateGrowsBeyondTheHeaderWhenNoFreeSpaceExists() {
        SectorAllocator allocator = new SectorAllocator(RegionConstants.HEADER_SECTORS);

        assertEquals(2, allocator.allocate(3));
        assertEquals(5, allocator.totalSectors());
    }

    @Test
    void testAllocateReusesFreedSectorsWithFirstFit() {
        SectorAllocator allocator = new SectorAllocator(RegionConstants.HEADER_SECTORS);
        int first = allocator.allocate(2);
        int second = allocator.allocate(2);
        allocator.free(first, 2);

        assertEquals(first, allocator.allocate(2));
        assertEquals(second + 2, allocator.totalSectors());
    }

    @Test
    void testAllocateSkipsAGapThatIsTooSmall() {
        SectorAllocator allocator = new SectorAllocator(RegionConstants.HEADER_SECTORS);
        int first = allocator.allocate(1);
        allocator.allocate(1);
        allocator.free(first, 1);

        assertEquals(4, allocator.allocate(2));
    }

    @Test
    void testFreedSectorsAreMarkedAsFree() {
        SectorAllocator allocator = new SectorAllocator(RegionConstants.HEADER_SECTORS);
        int offset = allocator.allocate(2);
        allocator.free(offset, 2);

        assertTrue(allocator.isFree(offset));
        assertTrue(allocator.isFree(offset + 1));
    }

    @Test
    void testReserveMarksAnExistingRangeAsUsed() {
        SectorAllocator allocator = new SectorAllocator(10);
        allocator.reserve(4, 2);

        assertFalse(allocator.isFree(4));
        assertFalse(allocator.isFree(5));
        assertTrue(allocator.isFree(6));
    }

    @Test
    void testReserveRejectsAnOverlappingRange() {
        SectorAllocator allocator = new SectorAllocator(10);
        allocator.reserve(4, 2);

        assertThrows(IllegalStateException.class, () -> allocator.reserve(5, 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void testAllocateRejectsANonPositiveCount(int count) {
        SectorAllocator allocator = new SectorAllocator(RegionConstants.HEADER_SECTORS);

        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(count));
    }

    @Test
    void testRepeatedAllocationsNeverOverlap() {
        SectorAllocator allocator = new SectorAllocator(RegionConstants.HEADER_SECTORS);
        boolean[] occupied = new boolean[512];

        for (int i = 1; i <= 32; i++) {
            int count = (i % 4) + 1;
            int offset = allocator.allocate(count);
            for (int sector = offset; sector < offset + count; sector++) {
                assertFalse(occupied[sector], "sector " + sector + " was handed out twice");
                occupied[sector] = true;
            }
        }
    }
}
