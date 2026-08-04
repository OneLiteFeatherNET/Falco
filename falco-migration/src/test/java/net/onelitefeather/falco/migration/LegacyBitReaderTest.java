package net.onelitefeather.falco.migration;

import net.onelitefeather.falco.anvil.BitPacker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down {@link LegacyBitReader}: that it reads an entry whole even when it spans two longs, and
 * that {@code falco-anvil}'s {@link BitPacker} — which restarts at a long boundary for every entry —
 * would read the same bytes differently.
 */
class LegacyBitReaderTest {

    @Test
    void testAnEntryThatSpansTwoLongsIsReadWhole() {
        // 5 bits per entry, entry index 12: its continuous bit offset is 12 * 5 = 60, so its 5 bits
        // occupy continuous positions 60, 61, 62, 63, 64 — the last four bits of packed[0] and the
        // first bit of packed[1] (bit positions are LSB-numbered within each long, matching
        // BitPacker's own "<< bitOffset" convention).
        //
        // packed[0] = 0xF000_0000_0000_0000L: its top hex digit (F = 1111) occupies bits 60-63, so
        // bit60=1, bit61=1, bit62=1, bit63=1; every other bit of packed[0] is 0.
        // packed[1] = 0x0000_0000_0000_0001L: only its bit 0 is set, which is continuous bit 64.
        //
        // Reading the 5 bits of entry 12 from the low end to the high end: bit60=1 (entry bit 0),
        // bit61=1 (bit 1), bit62=1 (bit 2), bit63=1 (bit 3), bit64=1 (bit 4). All five bits are 1,
        // so the entry's value is 0b11111 = 31 — not 1 (which is what only reading packed[1]'s low
        // bits, ignoring the four bits packed[0] contributes, would give).
        long[] packed = {0xF000_0000_0000_0000L, 0x0000_0000_0000_0001L};

        int[] values = LegacyBitReader.unpack(packed, 5, 13);

        assertEquals(0b11111, values[12], "the entry crosses the long boundary and must be read whole");
    }

    @Test
    void testTheModernReaderWouldGetThatWrong() {
        // BitPacker restarts at a long boundary for every entry instead of walking a continuous bit
        // stream: with 5 bits per entry, 64 / 5 = 12 entries fit in one long (using only its low 60
        // bits; BitPacker leaves the top 4 bits of every long unused rather than letting an entry
        // spill into the next one). Entry 12 is therefore the FIRST entry of the SECOND long:
        // longIndex = 12 / 12 = 1, bitOffset = (12 % 12) * 5 = 0 — it reads bits 0-4 of packed[1],
        // which is 0b00001 = 1, not the 31 LegacyBitReader reads for the same bytes.
        //
        // BitPacker.unpack's own parameter order is (packed, entryCount, bitsPerEntry) — the
        // opposite of LegacyBitReader.unpack's (packed, bitsPerEntry, entryCount) — so the call
        // below is BitPacker.unpack(packed, 13, 5), not BitPacker.unpack(packed, 5, 13).
        long[] packed = {0xF000_0000_0000_0000L, 0x0000_0000_0000_0001L};

        int[] modern = BitPacker.unpack(packed, 13, 5);
        int[] legacy = LegacyBitReader.unpack(packed, 5, 13);

        assertNotEquals(modern[12], legacy[12],
                "if these agree, the legacy reader is not doing anything and this module does not need it");
    }
}
