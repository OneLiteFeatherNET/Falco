package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the long array read which takes a tag the caller has already resolved. A caller which
 * tested the type itself has to receive the same entries as one which lets the facade look the
 * key up, because both are used to read the very same packed array of a section.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
class NbtReadsLongArrayTest {

    @Test
    void testLongArrayOfAResolvedTagReadsEveryEntry() {
        LongArrayBinaryTag tag = LongArrayBinaryTag.longArrayBinaryTag(1L, 2L, 3L, 4L);

        assertArrayEquals(new long[]{1L, 2L, 3L, 4L}, NbtReads.longArray(tag));
    }

    @Test
    void testLongArrayOfAnEmptyResolvedTagReadsNothing() {
        assertEquals(0, NbtReads.longArray(LongArrayBinaryTag.longArrayBinaryTag()).length);
    }

    @Test
    void testBothLongArrayReadsAnswerTheSame() throws Exception {
        LongArrayBinaryTag tag = LongArrayBinaryTag.longArrayBinaryTag(-1L, 0L, Long.MAX_VALUE, Long.MIN_VALUE);
        CompoundBinaryTag compound = CompoundBinaryTag.builder().put("data", tag).build();

        assertArrayEquals(NbtReads.longArray(compound, "data"), NbtReads.longArray(tag));
    }
}
