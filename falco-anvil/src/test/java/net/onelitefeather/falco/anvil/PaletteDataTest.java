package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the palette representation which the codec uses between the region file and the
 * palettes of Minestom.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class PaletteDataTest {

    private static final int BLOCK_ENTRIES = 4096;
    private static final int BLOCK_MIN_BITS = 4;

    @Test
    void testASingleValueNeedsNoPackedData() {
        PaletteData data = PaletteData.single(7, BLOCK_ENTRIES);

        assertTrue(data.isSingleValue());
        assertEquals(7, data.singleValue());
        assertNull(data.packed());
        assertEquals(BLOCK_ENTRIES, data.entryCount());
    }

    @Test
    void testEncodingAHomogeneousSectionCollapsesToASingleValue() {
        int[] values = new int[BLOCK_ENTRIES];
        Arrays.fill(values, 42);

        PaletteData data = PaletteData.encode(values, BLOCK_MIN_BITS);

        assertTrue(data.isSingleValue());
        assertEquals(42, data.singleValue());
    }

    @Test
    void testEncodingKeepsEveryDistinctValue() throws IOException {
        int[] values = new int[BLOCK_ENTRIES];

        for (int i = 0; i < values.length; i++) {
            values[i] = i % 5;
        }

        PaletteData data = PaletteData.encode(values, BLOCK_MIN_BITS);

        assertFalse(data.isSingleValue());
        assertEquals(5, data.palette().length);
        assertArrayEquals(values, data.unpack());
    }

    @Test
    void testEncodingRespectsTheMinimumBitsOfTheType() {
        int[] values = new int[BLOCK_ENTRIES];
        values[0] = 1;

        PaletteData data = PaletteData.encode(values, BLOCK_MIN_BITS);

        assertEquals(BLOCK_MIN_BITS, data.bitsPerEntry());
    }

    @Test
    void testEncodingBiomesUsesASmallerMinimum() {
        int[] values = new int[64];
        values[0] = 1;

        PaletteData data = PaletteData.encode(values, 1);

        assertEquals(1, data.bitsPerEntry());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 16, 17, 200, 4096})
    void testEncodeAndUnpackAreInverseForRandomData(int distinctValues) throws IOException {
        RandomGenerator random = RandomGenerator.getDefault();
        int[] values = new int[BLOCK_ENTRIES];

        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextInt(distinctValues);
        }
        for (int i = 0; i < distinctValues; i++) {
            values[i % values.length] = i;
        }

        PaletteData data = PaletteData.encode(values, BLOCK_MIN_BITS);

        assertArrayEquals(values, data.unpack());
    }

    @Test
    void testReadingAcceptsDataWhichMatchesTheExpectedBitCount() throws IOException {
        int[] palette = {10, 20, 30};
        long[] packed = BitPacker.pack(new int[BLOCK_ENTRIES], BLOCK_MIN_BITS);

        PaletteData data = PaletteData.read(palette, packed, BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertEquals(BLOCK_MIN_BITS, data.bitsPerEntry());
        assertSame(packed, data.packed());
    }

    @Test
    void testReadingRecoversABitCountWhichIsLargerThanTheMinimum() throws IOException {
        // A foreign writer may use more bits than the palette size requires. Minestom derives the
        // bit count from the palette length alone and would decode this data incorrectly.
        int[] palette = {10, 20, 30};
        long[] packed = BitPacker.pack(new int[BLOCK_ENTRIES], 6);

        PaletteData data = PaletteData.read(palette, packed, BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertEquals(6, data.bitsPerEntry());
    }

    @Test
    void testReadingRejectsAnUnmatchableLength() {
        int[] palette = {10, 20};
        long[] packed = new long[7];

        assertThrows(IOException.class, () -> PaletteData.read(palette, packed, BLOCK_ENTRIES, BLOCK_MIN_BITS));
    }

    @Test
    void testReadingASinglePaletteEntryWithoutDataYieldsASingleValue() throws IOException {
        PaletteData data = PaletteData.read(new int[]{99}, null, BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertTrue(data.isSingleValue());
        assertEquals(99, data.singleValue());
    }

    @Test
    void testReadingRejectsAnEmptyPalette() {
        assertThrows(IOException.class, () -> PaletteData.read(new int[0], null, BLOCK_ENTRIES, BLOCK_MIN_BITS));
    }

    @Test
    void testUnpackResolvesEveryEntryThroughThePalette() throws IOException {
        int[] palette = {100, 200, 300, 400};
        int[] indices = new int[BLOCK_ENTRIES];
        indices[0] = 3;
        indices[1] = 1;
        long[] packed = BitPacker.pack(indices, BLOCK_MIN_BITS);

        PaletteData data = PaletteData.read(palette, packed, BLOCK_ENTRIES, BLOCK_MIN_BITS);
        int[] values = data.unpack();

        assertEquals(400, values[0]);
        assertEquals(200, values[1]);
        assertEquals(100, values[2]);
    }

    @Test
    void testUnpackOfASingleValueFillsEveryEntry() throws IOException {
        int[] values = PaletteData.single(5, 64).unpack();

        assertEquals(64, values.length);
        assertArrayEquals(new int[64], subtract(values, 5));
    }

    @Test
    void testReadingRejectsAPaletteIndexOutsideOfThePalette() throws IOException {
        int[] indices = new int[BLOCK_ENTRIES];
        indices[5] = 3;
        long[] packed = BitPacker.pack(indices, BLOCK_MIN_BITS);
        PaletteData data = PaletteData.read(new int[]{1, 2}, packed, BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertThrows(IOException.class, data::unpack);
    }

    /**
     * Subtracts the given amount from every entry of the array.
     *
     * @param values the values to reduce
     * @param amount the amount to subtract
     * @return the reduced values
     */
    private static int[] subtract(int[] values, int amount) {
        int[] result = new int[values.length];

        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] - amount;
        }
        return result;
    }

    @Test
    void testEncodingAUniformSectionTouchesNoMap() {
        // A section of one repeated state is the common case: air, stone, or water fill whole
        // sections of a world. Building a palette map over 4096 identical entries to then collapse
        // it again is pure waste, so the uniform case has to be recognised before that happens.
        int[] values = new int[BLOCK_ENTRIES];
        Arrays.fill(values, 77);

        PaletteData data = PaletteData.encode(values, BLOCK_MIN_BITS);

        assertTrue(data.isSingleValue());
        assertEquals(77, data.singleValue());
        assertEquals(1, data.palette().length);
        assertNull(data.packed());
    }

    @Test
    void testEncodingStillHandlesASingleDifferingEntry() {
        // The shortcut must not swallow a section that is uniform except for one block.
        int[] values = new int[BLOCK_ENTRIES];
        Arrays.fill(values, 5);
        values[BLOCK_ENTRIES - 1] = 6;

        PaletteData data = PaletteData.encode(values, BLOCK_MIN_BITS);

        assertFalse(data.isSingleValue());
        assertEquals(2, data.palette().length);
    }

    @Test
    void testEncodingAnEmptySectionIsUniform() {
        PaletteData data = PaletteData.encode(new int[BLOCK_ENTRIES], BLOCK_MIN_BITS);

        assertTrue(data.isSingleValue());
        assertEquals(0, data.singleValue());
    }
}
