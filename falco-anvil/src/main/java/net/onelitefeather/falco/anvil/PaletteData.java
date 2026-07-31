package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@link PaletteData} record holds the palette of a section together with the packed indices
 * which reference it. It is the representation the codec works with between a region file and the
 * palettes of Minestom.
 * <p>
 * The record is immutable and thread confined by usage. A loader thread builds it without any lock
 * and hands it over to a Minestom palette afterwards, which gives the data a safe publication
 * through the final fields of the record.
 * </p>
 * <p>
 * A section in which every entry holds the same value carries no packed data at all. The format
 * stores such a section with a palette of a single entry and without a data array.
 * </p>
 *
 * @param palette      the distinct values of the section in the order the format stores them
 * @param packed       the packed palette indices or null if the section holds a single value
 * @param bitsPerEntry the amount of bits a single index occupies
 * @param entryCount   the amount of entries the section holds
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
public record PaletteData(int[] palette, long @Nullable [] packed, int bitsPerEntry, int entryCount) {

    /**
     * The marker a uniformity scan reports when a section holds more than one value.
     * It is outside the range of a block state id, which is never negative.
     */
    private static final int NOT_UNIFORM = Integer.MIN_VALUE;

    /**
     * Creates a representation for a section in which every entry holds the same value.
     *
     * @param value      the value every entry of the section holds
     * @param entryCount the amount of entries the section holds
     * @return the created representation
     */
    @Contract(pure = true, value = "_, _ -> new")
    public static PaletteData single(int value, int entryCount) {
        return new PaletteData(new int[]{value}, null, 0, entryCount);
    }

    /**
     * Reads a palette which was stored in a region file.
     * <p>
     * The amount of bits per entry is verified against the length of the packed data instead of
     * being derived from the palette size alone. A writer is allowed to use more bits than the
     * palette requires and deriving the value from the palette size would decode such data
     * incorrectly.
     * </p>
     *
     * @param palette      the distinct values of the section
     * @param packed       the packed palette indices or null if the section holds a single value
     * @param entryCount   the amount of entries the section holds
     * @param minBitsPerEntry the smallest amount of bits the palette type allows
     * @return the created representation
     * @throws IOException if the palette is empty or the packed data has an unusable length
     */
    public static PaletteData read(int[] palette, long @Nullable [] packed, int entryCount, int minBitsPerEntry) throws IOException {
        if (palette.length == 0) {
            throw new IOException("The palette of a section must hold at least one entry");
        }
        if (packed == null || packed.length == 0) {
            return single(palette[0], entryCount);
        }

        int expected = BitPacker.bitsPerEntry(palette.length, minBitsPerEntry);
        int resolved = BitPacker.resolveBitsPerEntry(packed.length, entryCount, expected);

        if (resolved == 0) {
            throw new IOException(
                    "The packed data of a section holds " + packed.length + " longs which does not match any bit count for "
                            + entryCount + " entries"
            );
        }
        return new PaletteData(palette, packed, resolved, entryCount);
    }

    /**
     * Builds the representation for the given values by collecting the distinct ones into a
     * palette and packing the indices which reference them.
     *
     * @param values          the value of every entry of the section
     * @param minBitsPerEntry the smallest amount of bits the palette type allows
     * @return the created representation
     */
    public static PaletteData encode(int[] values, int minBitsPerEntry) {
        // Whole sections of a world hold one repeated state: air above the terrain, stone below it,
        // water in an ocean. Building a palette map over thousands of identical entries only to
        // collapse it again afterwards is the common case, so it is recognised first. The scan
        // stops at the first differing entry, which makes it free for every other section.
        int uniform = uniformValueOf(values);

        if (uniform != NOT_UNIFORM) {
            return single(uniform, values.length);
        }

        Map<Integer, Integer> indices = new HashMap<>();
        int[] mapped = new int[values.length];

        for (int i = 0; i < values.length; i++) {
            mapped[i] = indices.computeIfAbsent(values[i], ignored -> indices.size());
        }

        int[] palette = new int[indices.size()];

        for (Map.Entry<Integer, Integer> entry : indices.entrySet()) {
            palette[entry.getValue()] = entry.getKey();
        }

        if (palette.length == 1) {
            return single(palette[0], values.length);
        }

        int bitsPerEntry = BitPacker.bitsPerEntry(palette.length, minBitsPerEntry);
        return new PaletteData(palette, BitPacker.pack(mapped, bitsPerEntry), bitsPerEntry, values.length);
    }

    /**
     * Determines whether every entry of the given section holds the same value.
     *
     * @param values the value of every entry of the section
     * @return the repeated value, or {@link #NOT_UNIFORM} if the section holds more than one
     */
    @Contract(pure = true)
    private static int uniformValueOf(int[] values) {
        if (values.length == 0) {
            return NOT_UNIFORM;
        }

        int first = values[0];

        for (int value : values) {
            if (value != first) {
                return NOT_UNIFORM;
            }
        }
        return first;
    }

    /**
     * Checks whether every entry of the section holds the same value.
     *
     * @return true if the section holds a single value, otherwise false
     */
    @Contract(pure = true)
    public boolean isSingleValue() {
        return this.packed == null;
    }

    /**
     * Returns the value every entry of the section holds.
     *
     * @return the value of every entry
     * @throws IllegalStateException if the section does not hold a single value
     */
    @Contract(pure = true)
    public int singleValue() {
        if (!isSingleValue()) {
            throw new IllegalStateException("The section holds " + this.palette.length + " distinct values");
        }
        return this.palette[0];
    }

    /**
     * Resolves the value of every entry of the section through the palette.
     *
     * @return the value of every entry
     * @throws IOException if a packed index does not address an entry of the palette
     */
    public int[] unpack() throws IOException {
        int[] values = new int[this.entryCount];

        if (isSingleValue()) {
            java.util.Arrays.fill(values, this.palette[0]);
            return values;
        }

        int[] indices = BitPacker.unpack(this.packed, this.entryCount, this.bitsPerEntry);

        for (int i = 0; i < values.length; i++) {
            int index = indices[i];

            if (index < 0 || index >= this.palette.length) {
                throw new IOException(
                        "The packed index " + index + " does not address one of the " + this.palette.length + " palette entries"
                );
            }
            values[i] = this.palette[index];
        }
        return values;
    }
}
