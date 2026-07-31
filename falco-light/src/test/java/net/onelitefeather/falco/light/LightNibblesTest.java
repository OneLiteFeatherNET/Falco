package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the nibble storage of a light section. Two light levels share one byte, and a section
 * whose levels are all equal carries no array at all.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class LightNibblesTest {

    @Test
    void testAUniformSectionCarriesNoArray() {
        LightNibbles nibbles = LightNibbles.uniform(15);

        assertTrue(nibbles.isUniform());
        assertEquals(15, nibbles.get(0, 0, 0));
        assertEquals(15, nibbles.get(15, 15, 15));
    }

    @Test
    void testAUniformSectionReportsTheEmptyArrayWithoutAllocating() {
        LightNibbles nibbles = LightNibbles.uniform(0);

        assertEquals(0, nibbles.toArray().length, "a fully dark section needs no bytes on disk");
    }

    @Test
    void testAUniformSectionExpandsIntoAFullArrayWhenAskedFor() {
        byte[] array = LightNibbles.uniform(15).toDenseArray();

        assertEquals(LightNibbles.ARRAY_LENGTH, array.length);
        for (byte value : array) {
            assertEquals((byte) 0xFF, value, "every nibble of the byte has to carry the level");
        }
    }

    @Test
    void testWritingADifferentLevelBreaksTheUniformState() {
        LightNibbles nibbles = LightNibbles.uniform(0);
        nibbles.set(1, 2, 3, 7);

        assertFalse(nibbles.isUniform());
        assertEquals(7, nibbles.get(1, 2, 3));
        assertEquals(0, nibbles.get(1, 2, 4));
    }

    @Test
    void testWritingTheSameLevelKeepsTheUniformState() {
        LightNibbles nibbles = LightNibbles.uniform(4);
        nibbles.set(1, 2, 3, 4);

        assertTrue(nibbles.isUniform(), "writing the value it already holds must not allocate");
    }

    @Test
    void testTwoLevelsShareOneByte() {
        LightNibbles nibbles = LightNibbles.uniform(0);
        nibbles.set(0, 0, 0, 1);
        nibbles.set(1, 0, 0, 2);

        byte[] array = nibbles.toArray();

        assertEquals(LightNibbles.ARRAY_LENGTH, array.length);
        assertEquals(1, array[0] & 0x0F);
        assertEquals(2, (array[0] >> 4) & 0x0F);
    }

    @Test
    void testEveryPositionIsAddressedSeparately() {
        LightNibbles nibbles = LightNibbles.uniform(0);
        RandomGenerator random = RandomGenerator.getDefault();
        int[][][] expected = new int[16][16][16];

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int level = random.nextInt(16);
                    expected[x][y][z] = level;
                    nibbles.set(x, y, z, level);
                }
            }
        }

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(expected[x][y][z], nibbles.get(x, y, z), "mismatch at " + x + "/" + y + "/" + z);
                }
            }
        }
    }

    @Test
    void testAStoredArrayIsReadBackIdentically() {
        byte[] source = new byte[LightNibbles.ARRAY_LENGTH];
        RandomGenerator.getDefault().nextBytes(source);

        LightNibbles nibbles = LightNibbles.of(source);

        assertArrayEquals(source, nibbles.toArray());
    }

    @Test
    void testAnArrayOfOnlyOneLevelIsRecognisedAsUniform() {
        byte[] source = new byte[LightNibbles.ARRAY_LENGTH];
        java.util.Arrays.fill(source, (byte) 0x88);

        LightNibbles nibbles = LightNibbles.of(source);

        assertTrue(nibbles.isUniform(), "an array of a single repeated level should not be kept");
        assertEquals(8, nibbles.get(5, 5, 5));
    }

    @Test
    void testTheStoredArrayIsCopiedOnRead() {
        LightNibbles nibbles = LightNibbles.uniform(0);
        nibbles.set(0, 0, 0, 5);

        assertNotSame(nibbles.toArray(), nibbles.toArray(), "callers must not be able to mutate the storage");
    }

    @Test
    void testAnArrayOfTheWrongLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LightNibbles.of(new byte[100]));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 16})
    void testALevelOutsideTheRangeIsRejected(int level) {
        LightNibbles nibbles = LightNibbles.uniform(0);

        assertThrows(IllegalArgumentException.class, () -> nibbles.set(0, 0, 0, level));
    }

    @Test
    void testTheUniformLevelIsRejectedWhenOutsideTheRange() {
        assertThrows(IllegalArgumentException.class, () -> LightNibbles.uniform(16));
    }

    @Test
    void testFillResetsTheSectionToASingleLevel() {
        LightNibbles nibbles = LightNibbles.uniform(0);
        nibbles.set(3, 3, 3, 9);
        nibbles.fill(2);

        assertTrue(nibbles.isUniform(), "filling has to release the array again");
        assertEquals(2, nibbles.get(3, 3, 3));
    }

    @Test
    void testACopyIsIndependentOfItsSource() {
        LightNibbles source = LightNibbles.uniform(0);
        source.set(1, 1, 1, 3);

        LightNibbles copy = source.copy();
        copy.set(1, 1, 1, 9);

        assertEquals(3, source.get(1, 1, 1));
        assertEquals(9, copy.get(1, 1, 1));
    }

    @Test
    void testCopyingAUniformSectionSharesNoArray() {
        LightNibbles copy = LightNibbles.uniform(7).copy();

        assertTrue(copy.isUniform());
        assertEquals(7, copy.get(0, 0, 0));
    }

    @Test
    void testTheMaximumLevelIsStoredWithoutSignIssues() {
        LightNibbles nibbles = LightNibbles.uniform(0);
        nibbles.set(2, 2, 2, 15);

        assertEquals(15, nibbles.get(2, 2, 2), "a nibble is unsigned, 15 must not read back as -1");
    }

    @Test
    void testALevelPerPositionIsPackedIntoNibbles() {
        byte[] levels = new byte[LightNibbles.BLOCK_COUNT];

        for (int index = 0; index < levels.length; index++) {
            levels[index] = (byte) (index % 16);
        }
        LightNibbles nibbles = LightNibbles.ofLevels(levels, 0);

        assertFalse(nibbles.isUniform());

        for (int index = 0; index < levels.length; index++) {
            assertEquals(levels[index], nibbles.get(index & 15, index >> 8, (index >> 4) & 15));
        }
    }

    @Test
    void testPackingAndSettingProduceTheSameBytes() {
        // The packer replaces a loop which wrote every position through set. Both have to end in
        // exactly the same bytes, because those bytes go to a client.
        RandomGenerator random = RandomGenerator.getDefault();
        byte[] levels = new byte[LightNibbles.BLOCK_COUNT];
        LightNibbles written = LightNibbles.uniform(0);

        for (int index = 0; index < levels.length; index++) {
            int level = random.nextInt(16);
            levels[index] = (byte) level;

            if (level != 0) {
                written.set(index & 15, index >> 8, (index >> 4) & 15, level);
            }
        }

        assertArrayEquals(written.toDenseArray(), LightNibbles.ofLevels(levels, 0).toDenseArray());
    }

    @Test
    void testALevelArrayOfOneRepeatedLevelNeedsNoArray() {
        byte[] levels = new byte[LightNibbles.BLOCK_COUNT];
        java.util.Arrays.fill(levels, (byte) 7);

        LightNibbles nibbles = LightNibbles.ofLevels(levels, 0);

        assertTrue(nibbles.isUniform());
        assertEquals(7, nibbles.get(3, 9, 12));
    }

    @Test
    void testPackingReadsTheSectionWhichStartsAtTheGivenOffset() {
        // A whole chunk column keeps the levels of all of its sections in one array, so a section
        // has to be packed out of the middle of it.
        byte[] levels = new byte[LightNibbles.BLOCK_COUNT * 3];
        java.util.Arrays.fill(levels, LightNibbles.BLOCK_COUNT, LightNibbles.BLOCK_COUNT * 2, (byte) 4);

        LightNibbles second = LightNibbles.ofLevels(levels, LightNibbles.BLOCK_COUNT);

        assertTrue(second.isUniform());
        assertEquals(4, second.get(0, 0, 0));
        assertEquals(0, LightNibbles.ofLevels(levels, 0).get(0, 0, 0));
    }

    @Test
    void testALevelOutsideTheAllowedRangeIsRejected() {
        byte[] levels = new byte[LightNibbles.BLOCK_COUNT];
        levels[77] = 42;

        assertThrows(IllegalArgumentException.class, () -> LightNibbles.ofLevels(levels, 0));
    }

    @Test
    void testALevelArrayWhichIsTooShortIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LightNibbles.ofLevels(new byte[10], 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LightNibbles.ofLevels(new byte[LightNibbles.BLOCK_COUNT], 1)
        );
    }
}
