package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

/**
 * Unpacks the long-array bit packing every version below Minecraft 1.16 (DataVersion 2566) wrote,
 * in which an entry is allowed to span the boundary between two longs.
 * <p>
 * {@code falco-anvil}'s {@link net.onelitefeather.falco.anvil.BitPacker} cannot read that layout:
 * its own {@code pack} is documented "without letting an entry span two longs", and its
 * {@code unpack} computes {@code longIndex = index / entriesPerLong} — an offset that restarts at
 * a long boundary for every entry rather than walking a single continuous bit stream. Below 1.16 the
 * format instead lays entries out back to back with no padding at all, so an entry whose bit range
 * crosses a 64-bit boundary is genuinely split across {@code packed[n]} and {@code packed[n + 1]}.
 * This class reads exactly that: a continuous bit offset, {@code index * bitsPerEntry}, with no
 * restart.
 * </p>
 * <p>
 * {@link net.onelitefeather.falco.migration.steps.NormaliseBitPacking} is this reader's only caller:
 * it reads a legacy section once with this class and re-packs the result with {@code BitPacker}, so
 * every step downstream of it only ever sees the long-aligned layout {@code BitPacker} already
 * understands.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class LegacyBitReader {

    private static final int BITS_PER_LONG = Long.SIZE;

    private LegacyBitReader() {
    }

    /**
     * Unpacks {@code entryCount} entries of {@code bitsPerEntry} bits each from a continuous,
     * boundary-spanning bit stream.
     *
     * @param packed       the packed representation to read
     * @param bitsPerEntry the amount of bits a single entry occupies
     * @param entryCount   the amount of entries to read
     * @return the unpacked entries, in order
     * @throws IllegalArgumentException if {@code bitsPerEntry} is not within {@code [1, 64]}, or if
     *                                   {@code packed} does not hold enough bits for
     *                                   {@code entryCount} entries of {@code bitsPerEntry} bits each
     */
    @Contract(pure = true)
    public static int[] unpack(long[] packed, int bitsPerEntry, int entryCount) {
        if (bitsPerEntry <= 0 || bitsPerEntry > BITS_PER_LONG) {
            throw new IllegalArgumentException(
                    "The amount of bits per entry must be within [1, 64] but was " + bitsPerEntry);
        }

        long totalBits = (long) entryCount * bitsPerEntry;
        int requiredLongs = (int) ((totalBits + BITS_PER_LONG - 1) / BITS_PER_LONG);
        if (packed.length < requiredLongs) {
            throw new IllegalArgumentException(
                    "The packed data holds " + packed.length + " longs but " + requiredLongs + " are required");
        }

        long mask = bitsPerEntry == BITS_PER_LONG ? -1L : (1L << bitsPerEntry) - 1L;
        int[] values = new int[entryCount];

        for (int index = 0; index < entryCount; index++) {
            long bitOffset = (long) index * bitsPerEntry;
            int longIndex = (int) (bitOffset / BITS_PER_LONG);
            int bitInLong = (int) (bitOffset % BITS_PER_LONG);
            int bitsAvailable = BITS_PER_LONG - bitInLong;

            long low = packed[longIndex] >>> bitInLong;
            long value = bitsAvailable >= bitsPerEntry
                    ? low
                    : low | (packed[longIndex + 1] << bitsAvailable);

            values[index] = (int) (value & mask);
        }
        return values;
    }
}
