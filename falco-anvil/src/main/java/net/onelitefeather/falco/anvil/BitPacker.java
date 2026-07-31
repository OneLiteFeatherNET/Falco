package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

/**
 * The {@link BitPacker} converts palette indices into the packed long array representation
 * which the Anvil format uses for block and biome data.
 * <p>
 * Since the world format of Minecraft 1.16 an entry never spans two longs. Every long stores
 * {@code 64 / bitsPerEntry} entries and the remaining upper bits stay empty. The class only
 * contains pure functions so the encoding can be verified without any file or server access.
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
public final class BitPacker {

    private static final int BITS_PER_LONG = Long.SIZE;

    private BitPacker() {
    }

    /**
     * Calculates the amount of bits which are required to address every entry of a palette.
     * The result never falls below the given minimum which the format defines per palette type.
     *
     * @param paletteSize    the amount of entries the palette holds
     * @param minBitsPerEntry the smallest amount of bits the palette type allows
     * @return the amount of bits per entry
     * @throws IllegalArgumentException if the palette does not hold at least one entry
     */
    @Contract(pure = true)
    public static int bitsPerEntry(int paletteSize, int minBitsPerEntry) {
        if (paletteSize <= 0) {
            throw new IllegalArgumentException("The palette must hold at least one entry but held " + paletteSize);
        }
        int required = Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1);
        return Math.max(Math.max(required, 1), minBitsPerEntry);
    }

    /**
     * Calculates the amount of longs which are required to store the given amount of entries.
     *
     * @param entryCount   the amount of entries to store
     * @param bitsPerEntry the amount of bits a single entry occupies
     * @return the amount of longs which are required
     * @throws IllegalArgumentException if the amount of bits per entry is not usable
     */
    @Contract(pure = true)
    public static int expectedLongCount(int entryCount, int bitsPerEntry) {
        int entriesPerLong = entriesPerLong(bitsPerEntry);
        return (entryCount + entriesPerLong - 1) / entriesPerLong;
    }

    /**
     * Packs the given entries into a long array without letting an entry span two longs.
     *
     * @param values       the entries to pack
     * @param bitsPerEntry the amount of bits a single entry occupies
     * @return the packed representation of the given entries
     * @throws IllegalArgumentException if the amount of bits per entry is not usable
     */
    @Contract(pure = true)
    public static long[] pack(int[] values, int bitsPerEntry) {
        int entriesPerLong = entriesPerLong(bitsPerEntry);
        long mask = mask(bitsPerEntry);
        long[] packed = new long[expectedLongCount(values.length, bitsPerEntry)];

        for (int index = 0; index < values.length; index++) {
            int longIndex = index / entriesPerLong;
            int bitOffset = (index % entriesPerLong) * bitsPerEntry;
            packed[longIndex] |= (values[index] & mask) << bitOffset;
        }
        return packed;
    }

    /**
     * Unpacks the given long array back into the single entries it holds.
     *
     * @param packed       the packed representation to read
     * @param entryCount   the amount of entries the packed representation holds
     * @param bitsPerEntry the amount of bits a single entry occupies
     * @return the unpacked entries
     * @throws IllegalArgumentException if the amount of bits per entry is not usable or if the
     *                                  packed array is too short for the requested entry count
     */
    @Contract(pure = true)
    public static int[] unpack(long[] packed, int entryCount, int bitsPerEntry) {
        int required = expectedLongCount(entryCount, bitsPerEntry);

        if (packed.length < required) {
            throw new IllegalArgumentException(
                    "The packed data holds " + packed.length + " longs but " + required + " are required"
            );
        }

        int entriesPerLong = entriesPerLong(bitsPerEntry);
        long mask = mask(bitsPerEntry);
        int[] values = new int[entryCount];

        for (int index = 0; index < entryCount; index++) {
            int longIndex = index / entriesPerLong;
            int bitOffset = (index % entriesPerLong) * bitsPerEntry;
            values[index] = (int) ((packed[longIndex] >>> bitOffset) & mask);
        }
        return values;
    }

    /**
     * Derives the amount of bits per entry from the length of an already packed array.
     * The format allows a writer to use more bits than the palette size requires, so the
     * expected amount of bits is only a starting point and is verified against the length.
     *
     * @param longCount           the amount of longs the packed representation holds
     * @param entryCount          the amount of entries the packed representation holds
     * @param expectedBitsPerEntry the amount of bits which the palette size suggests
     * @return the amount of bits per entry or zero if no amount matches the given length
     */
    @Contract(pure = true)
    public static int resolveBitsPerEntry(int longCount, int entryCount, int expectedBitsPerEntry) {
        if (expectedBitsPerEntry > 0 && expectedLongCount(entryCount, expectedBitsPerEntry) == longCount) {
            return expectedBitsPerEntry;
        }

        for (int candidate = 1; candidate <= BITS_PER_LONG; candidate++) {
            if (expectedLongCount(entryCount, candidate) == longCount) {
                return candidate;
            }
        }
        return 0;
    }

    /**
     * Calculates how many entries fit into a single long.
     *
     * @param bitsPerEntry the amount of bits a single entry occupies
     * @return the amount of entries per long
     * @throws IllegalArgumentException if the amount of bits per entry is not usable
     */
    @Contract(pure = true)
    private static int entriesPerLong(int bitsPerEntry) {
        if (bitsPerEntry <= 0 || bitsPerEntry > BITS_PER_LONG) {
            throw new IllegalArgumentException("The amount of bits per entry must be within [1, 64] but was " + bitsPerEntry);
        }
        return BITS_PER_LONG / bitsPerEntry;
    }

    /**
     * Creates the bit mask which isolates a single entry.
     *
     * @param bitsPerEntry the amount of bits a single entry occupies
     * @return the mask for a single entry
     */
    @Contract(pure = true)
    private static long mask(int bitsPerEntry) {
        return bitsPerEntry == BITS_PER_LONG ? -1L : (1L << bitsPerEntry) - 1L;
    }
}
