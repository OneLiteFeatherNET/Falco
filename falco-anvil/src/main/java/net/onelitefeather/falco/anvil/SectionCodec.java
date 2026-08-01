package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

import java.io.IOException;

/**
 * The {@link SectionCodec} class converts between a palette container of the Anvil format and the
 * {@link PaletteData} representation the loader works with.
 * <p>
 * A palette container is the shape the format uses for the blocks and the biomes of a section. It
 * holds a list of named entries and an optional array of packed indices which reference them. A
 * container without the array describes a section in which every entry holds the same value.
 * </p>
 * <p>
 * The class is stateless so it can be used from every thread that loads or saves a chunk.
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
public final class SectionCodec {

    private static final String PALETTE_KEY = "palette";
    private static final String DATA_KEY = "data";
    private static final String NAME_KEY = "Name";
    private static final String PROPERTIES_KEY = "Properties";

    private SectionCodec() {
    }

    /**
     * Reads a palette container and resolves every entry of it.
     *
     * @param container       the palette container to read
     * @param resolver        the resolver which translates the names into ids
     * @param entryCount      the amount of entries the section holds
     * @param minBitsPerEntry the smallest amount of bits the palette type allows
     * @return the palette representation of the container
     * @throws IOException if the container is malformed
     */
    public static PaletteData decode(CompoundBinaryTag container, PaletteEntryResolver resolver, int entryCount, int minBitsPerEntry) throws IOException {
        ListBinaryTag entries = NbtReads.list(container, PALETTE_KEY, BinaryTagTypes.COMPOUND);

        if (entries.size() == 0) {
            throw new IOException("The palette container holds an empty palette");
        }

        int[] palette = new int[entries.size()];

        for (int index = 0; index < palette.length; index++) {
            CompoundBinaryTag entry = entries.getCompound(index);
            palette[index] = resolver.toId(
                    NbtReads.string(entry, NAME_KEY),
                    NbtReads.optionalCompound(entry, PROPERTIES_KEY)
            );
        }

        BinaryTag data = container.get(DATA_KEY);

        if (data == null) {
            return PaletteData.read(palette, null, entryCount, minBitsPerEntry);
        }
        if (!(data instanceof LongArrayBinaryTag packed)) {
            throw new IOException("The palette container holds a data entry which is not a long array");
        }
        return PaletteData.read(palette, NbtReads.longArray(packed), entryCount, minBitsPerEntry);
    }

    /**
     * Reads a biome palette container and resolves every entry of it.
     * <p>
     * The format stores the biome palette as a list of plain names while the block palette holds a
     * compound with a name and optional properties, so both shapes need their own conversion.
     * </p>
     *
     * @param container       the palette container to read
     * @param resolver        the resolver which translates the names into ids
     * @param entryCount      the amount of entries the section holds
     * @param minBitsPerEntry the smallest amount of bits the palette type allows
     * @return the palette representation of the container
     * @throws IOException if the container is malformed
     */
    public static PaletteData decodeBiomes(CompoundBinaryTag container, PaletteEntryResolver resolver, int entryCount, int minBitsPerEntry) throws IOException {
        ListBinaryTag entries = NbtReads.list(container, PALETTE_KEY, BinaryTagTypes.STRING);

        if (entries.size() == 0) {
            throw new IOException("The biome palette container holds an empty palette");
        }

        int[] palette = new int[entries.size()];

        for (int index = 0; index < palette.length; index++) {
            palette[index] = resolver.toId(entries.getString(index), null);
        }

        BinaryTag data = container.get(DATA_KEY);

        if (data == null) {
            return PaletteData.read(palette, null, entryCount, minBitsPerEntry);
        }
        if (!(data instanceof LongArrayBinaryTag packed)) {
            throw new IOException("The biome palette container holds a data entry which is not a long array");
        }
        return PaletteData.read(palette, NbtReads.longArray(packed), entryCount, minBitsPerEntry);
    }

    /**
     * Writes the given palette representation into a biome palette container.
     *
     * @param data     the palette representation to write
     * @param resolver the resolver which describes the ids
     * @return the created palette container
     */
    public static CompoundBinaryTag encodeBiomes(PaletteData data, PaletteEntryResolver resolver) {
        ListBinaryTag.Builder<StringBinaryTag> entries = ListBinaryTag.builder(BinaryTagTypes.STRING);

        for (int id : data.palette()) {
            entries.add(StringBinaryTag.stringBinaryTag(NbtReads.optionalString(resolver.toEntry(id), NAME_KEY)));
        }

        CompoundBinaryTag.Builder container = CompoundBinaryTag.builder().put(PALETTE_KEY, entries.build());
        long[] packed = data.packed();

        if (packed != null) {
            container.put(DATA_KEY, LongArrayBinaryTag.longArrayBinaryTag(packed));
        }
        return container.build();
    }

    /**
     * Writes the given palette representation into a palette container.
     * A representation which holds a single value is written without a data array, which is the
     * shape the format uses for a uniform section.
     *
     * @param data     the palette representation to write
     * @param resolver the resolver which describes the ids
     * @return the created palette container
     */
    public static CompoundBinaryTag encode(PaletteData data, PaletteEntryResolver resolver) {
        ListBinaryTag.Builder<CompoundBinaryTag> entries = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);

        for (int id : data.palette()) {
            entries.add(resolver.toEntry(id));
        }

        CompoundBinaryTag.Builder container = CompoundBinaryTag.builder().put(PALETTE_KEY, entries.build());
        long[] packed = data.packed();

        if (packed != null) {
            container.put(DATA_KEY, LongArrayBinaryTag.longArrayBinaryTag(packed));
        }
        return container.build();
    }
}
