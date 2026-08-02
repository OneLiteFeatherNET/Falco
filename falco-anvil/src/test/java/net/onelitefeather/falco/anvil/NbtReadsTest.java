package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the strict access facade around Adventure NBT. The facade has to turn the silent
 * defaults of the library into explicit errors and must not rely on the broken iterators
 * of the array tags.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class NbtReadsTest {

    @Test
    void testTheArrayIteratorOfAdventureSkipsTheLastEntry() {
        // This documents the library defect the facade has to work around. A for each loop over
        // a long array tag silently drops the last entry which would corrupt every chunk.
        LongArrayBinaryTag tag = LongArrayBinaryTag.longArrayBinaryTag(1L, 2L, 3L, 4L);
        int visited = 0;

        for (long _ : tag) {
            visited++;
        }

        assertEquals(4, tag.size());
        assertEquals(3, visited, "adventure-nbt 5.1.1 is expected to skip the last entry here");
    }

    @Test
    void testLongArrayReadsEveryEntry() throws Exception {
        CompoundBinaryTag compound = CompoundBinaryTag.builder()
                .put("data", LongArrayBinaryTag.longArrayBinaryTag(1L, 2L, 3L, 4L))
                .build();

        assertArrayEquals(new long[]{1L, 2L, 3L, 4L}, NbtReads.longArray(compound, "data"));
    }

    @Test
    void testLongArrayFailsForAMissingKey() {
        CompoundBinaryTag compound = CompoundBinaryTag.empty();

        ChunkDataException exception = assertThrows(ChunkDataException.class, () -> NbtReads.longArray(compound, "data"));

        assertTrue(exception.getMessage().contains("data"));
    }

    @Test
    void testLongArrayFailsForAWrongType() {
        CompoundBinaryTag compound = CompoundBinaryTag.builder().put("data", StringBinaryTag.stringBinaryTag("nope")).build();

        assertThrows(ChunkDataException.class, () -> NbtReads.longArray(compound, "data"));
    }

    @Test
    void testCompoundReturnsTheNestedCompound() throws Exception {
        CompoundBinaryTag nested = CompoundBinaryTag.builder().putInt("value", 7).build();
        CompoundBinaryTag compound = CompoundBinaryTag.builder().put("nested", nested).build();

        assertSame(nested, NbtReads.compound(compound, "nested"));
    }

    @Test
    void testCompoundFailsForAMissingKey() {
        assertThrows(ChunkDataException.class, () -> NbtReads.compound(CompoundBinaryTag.empty(), "nested"));
    }

    @Test
    void testOptionalCompoundReturnsNullForAMissingKey() {
        assertEquals(null, NbtReads.optionalCompound(CompoundBinaryTag.empty(), "nested"));
    }

    @Test
    void testListReturnsTheTypedList() throws Exception {
        ListBinaryTag list = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                .add(CompoundBinaryTag.empty())
                .build();
        CompoundBinaryTag compound = CompoundBinaryTag.builder().put("sections", list).build();

        assertEquals(1, NbtReads.list(compound, "sections", BinaryTagTypes.COMPOUND).size());
    }

    @Test
    void testListFailsForAWrongElementType() {
        ListBinaryTag list = ListBinaryTag.builder(BinaryTagTypes.INT).add(IntBinaryTag.intBinaryTag(1)).build();
        CompoundBinaryTag compound = CompoundBinaryTag.builder().put("sections", list).build();

        assertThrows(ChunkDataException.class, () -> NbtReads.list(compound, "sections", BinaryTagTypes.COMPOUND));
    }

    @Test
    void testOptionalListReturnsAnEmptyListForAMissingKey() {
        assertEquals(0, NbtReads.optionalList(CompoundBinaryTag.empty(), "sections", BinaryTagTypes.COMPOUND).size());
    }

    @Test
    void testStringReturnsTheValue() throws Exception {
        CompoundBinaryTag compound = CompoundBinaryTag.builder().putString("Name", "minecraft:stone").build();

        assertEquals("minecraft:stone", NbtReads.string(compound, "Name"));
    }

    @Test
    void testStringFailsForANumericValue() {
        CompoundBinaryTag compound = CompoundBinaryTag.builder().putInt("Name", 3).build();

        assertThrows(ChunkDataException.class, () -> NbtReads.string(compound, "Name"));
    }

    @Test
    void testIntReturnsTheValue() throws Exception {
        CompoundBinaryTag compound = CompoundBinaryTag.builder().putInt("DataVersion", 4790).build();

        assertEquals(4790, NbtReads.integer(compound, "DataVersion"));
    }

    @Test
    void testIntAcceptsANarrowerNumericType() throws Exception {
        CompoundBinaryTag compound = CompoundBinaryTag.builder().putByte("Y", (byte) -4).build();

        assertEquals(-4, NbtReads.integer(compound, "Y"));
    }

    @Test
    void testIntFailsForAMissingKey() {
        assertThrows(ChunkDataException.class, () -> NbtReads.integer(CompoundBinaryTag.empty(), "DataVersion"));
    }
}
