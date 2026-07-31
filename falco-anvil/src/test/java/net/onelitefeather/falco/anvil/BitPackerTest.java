package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the bit packing which converts palette indices into the packed long array
 * representation used by the Anvil format and back.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class BitPackerTest {

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 1", "3, 2", "4, 2", "5, 3", "8, 3", "9, 4", "16, 4", "17, 5"})
    void testBitsPerEntryGrowsWithThePaletteSize(int paletteSize, int expectedBits) {
        assertEquals(expectedBits, BitPacker.bitsPerEntry(paletteSize, 1));
    }

    @Test
    void testBitsPerEntryRespectsTheMinimum() {
        assertEquals(4, BitPacker.bitsPerEntry(2, 4));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void testBitsPerEntryRejectsAnEmptyPalette(int paletteSize) {
        assertThrows(IllegalArgumentException.class, () -> BitPacker.bitsPerEntry(paletteSize, 1));
    }

    @ParameterizedTest
    @CsvSource({"4096, 4, 256", "4096, 5, 342", "4096, 15, 1024", "64, 1, 1", "64, 3, 4"})
    void testExpectedLongCountFollowsTheEntriesPerLong(int entryCount, int bitsPerEntry, int expected) {
        assertEquals(expected, BitPacker.expectedLongCount(entryCount, bitsPerEntry));
    }

    @Test
    void testPackWritesEntriesWithoutSpanningALongBoundary() {
        // With five bits per entry twelve entries fit into a long and four bits stay unused.
        // The last entry of a long must not bleed into the next one.
        int[] values = new int[64];
        values[11] = 0b11111;

        long[] packed = BitPacker.pack(values, 5);

        assertEquals(6, packed.length);
        assertEquals(0b11111L, (packed[0] >>> 55) & 0b11111L);
        assertEquals(0L, packed[1], "the entry must stay inside the first long");
    }

    @Test
    void testPackStartsANewLongAfterTheEntriesPerLongAreExhausted() {
        int[] values = new int[64];
        values[12] = 1;

        long[] packed = BitPacker.pack(values, 5);

        assertEquals(0L, packed[0]);
        assertEquals(1L, packed[1] & 0b11111L);
    }

    @Test
    void testPackLeavesThePaddingBitsOfEachLongEmpty() {
        int[] values = new int[64];
        java.util.Arrays.fill(values, 0b11111);

        long[] packed = BitPacker.pack(values, 5);

        for (long entry : packed) {
            assertEquals(0L, entry >>> 60, "the upper padding bits must stay empty");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 12, 15})
    void testUnpackReversesPackForRandomData(int bitsPerEntry) {
        RandomGenerator random = RandomGenerator.getDefault();
        int[] values = new int[4096];
        int bound = 1 << bitsPerEntry;

        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextInt(bound);
        }

        long[] packed = BitPacker.pack(values, bitsPerEntry);

        assertEquals(BitPacker.expectedLongCount(values.length, bitsPerEntry), packed.length);
        assertArrayEquals(values, BitPacker.unpack(packed, values.length, bitsPerEntry));
    }

    @Test
    void testUnpackRejectsATooShortArray() {
        long[] packed = new long[1];

        assertThrows(IllegalArgumentException.class, () -> BitPacker.unpack(packed, 4096, 4));
    }

    @Test
    void testResolveBitsPerEntryDerivesTheValueFromTheArrayLength() {
        assertEquals(5, BitPacker.resolveBitsPerEntry(342, 4096, 4));
    }

    @Test
    void testResolveBitsPerEntryKeepsTheExpectedValueWhenTheLengthMatches() {
        assertEquals(4, BitPacker.resolveBitsPerEntry(256, 4096, 4));
    }

    @Test
    void testResolveBitsPerEntryReturnsZeroForAnUnmatchableLength() {
        assertEquals(0, BitPacker.resolveBitsPerEntry(7, 4096, 4));
    }
}
