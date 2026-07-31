package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the conversion between the palette container of a section and the palette
 * representation of the codec. A fake resolver keeps the tests free of a Minestom server.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class SectionCodecTest {

    private static final int BLOCK_ENTRIES = 4096;
    private static final int BLOCK_MIN_BITS = 4;

    /**
     * A resolver which maps a fixed set of names to ids without touching any registry.
     */
    private static final class FakeResolver implements PaletteEntryResolver {

        private final List<String> known = new ArrayList<>(List.of("minecraft:air", "minecraft:stone", "minecraft:dirt"));
        private final List<String> unresolved = new ArrayList<>();

        @Override
        public int toId(String name, @Nullable CompoundBinaryTag properties) {
            int index = this.known.indexOf(name);

            if (index < 0) {
                this.unresolved.add(name);
                return 0;
            }
            return properties == null ? index : index + 100;
        }

        @Override
        public CompoundBinaryTag toEntry(int id) {
            return CompoundBinaryTag.builder().putString("Name", this.known.get(id % 100)).build();
        }
    }

    /**
     * Builds a palette container in the shape the Anvil format uses.
     *
     * @param names the names of the palette entries
     * @param data  the packed indices or null if the container holds a single value
     * @return the created container
     */
    private static CompoundBinaryTag container(List<String> names, long @Nullable [] data) {
        ListBinaryTag.Builder<CompoundBinaryTag> palette = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);

        for (String name : names) {
            palette.add(CompoundBinaryTag.builder().put("Name", StringBinaryTag.stringBinaryTag(name)).build());
        }

        CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder().put("palette", palette.build());

        if (data != null) {
            builder.put("data", LongArrayBinaryTag.longArrayBinaryTag(data));
        }
        return builder.build();
    }

    @Test
    void testDecodingASingleEntryContainerYieldsASingleValue() throws IOException {
        CompoundBinaryTag container = container(List.of("minecraft:stone"), null);

        PaletteData data = SectionCodec.decode(container, new FakeResolver(), BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertTrue(data.isSingleValue());
        assertEquals(1, data.singleValue());
    }

    @Test
    void testDecodingResolvesEveryPaletteEntry() throws IOException {
        int[] indices = new int[BLOCK_ENTRIES];
        indices[0] = 1;
        indices[1] = 2;
        CompoundBinaryTag container = container(
                List.of("minecraft:air", "minecraft:stone", "minecraft:dirt"),
                BitPacker.pack(indices, BLOCK_MIN_BITS)
        );

        int[] values = SectionCodec.decode(container, new FakeResolver(), BLOCK_ENTRIES, BLOCK_MIN_BITS).unpack();

        assertEquals(1, values[0]);
        assertEquals(2, values[1]);
        assertEquals(0, values[2]);
    }

    @Test
    void testDecodingPassesThePropertiesToTheResolver() throws IOException {
        CompoundBinaryTag entry = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:stone")
                .put("Properties", CompoundBinaryTag.builder().putString("axis", "y").build())
                .build();
        CompoundBinaryTag container = CompoundBinaryTag.builder()
                .put("palette", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(entry).build())
                .build();

        PaletteData data = SectionCodec.decode(container, new FakeResolver(), BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertEquals(101, data.singleValue());
    }

    @Test
    void testDecodingFailsForAMissingPalette() {
        CompoundBinaryTag container = CompoundBinaryTag.empty();

        assertThrows(IOException.class, () -> SectionCodec.decode(container, new FakeResolver(), BLOCK_ENTRIES, BLOCK_MIN_BITS));
    }

    @Test
    void testDecodingFailsForAPaletteEntryWithoutAName() {
        CompoundBinaryTag container = CompoundBinaryTag.builder()
                .put("palette", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(CompoundBinaryTag.empty()).build())
                .build();

        assertThrows(IOException.class, () -> SectionCodec.decode(container, new FakeResolver(), BLOCK_ENTRIES, BLOCK_MIN_BITS));
    }

    @Test
    void testAnUnknownNameFallsBackInsteadOfFailing() throws IOException {
        FakeResolver resolver = new FakeResolver();
        CompoundBinaryTag container = container(List.of("minecraft:mystery"), null);

        PaletteData data = SectionCodec.decode(container, resolver, BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertEquals(0, data.singleValue());
        assertEquals(List.of("minecraft:mystery"), resolver.unresolved);
    }

    @Test
    void testEncodingASingleValueOmitsTheDataArray() {
        CompoundBinaryTag container = SectionCodec.encode(PaletteData.single(1, BLOCK_ENTRIES), new FakeResolver());

        assertEquals(1, container.getList("palette").size());
        assertEquals(null, container.get("data"));
    }

    @Test
    void testEncodingWritesThePaletteAndTheData() {
        int[] values = new int[BLOCK_ENTRIES];
        values[0] = 1;
        values[1] = 2;

        CompoundBinaryTag container = SectionCodec.encode(PaletteData.encode(values, BLOCK_MIN_BITS), new FakeResolver());

        assertEquals(3, container.getList("palette").size());
        assertTrue(container.get("data") instanceof LongArrayBinaryTag);
    }

    @Test
    void testDecodingBiomesReadsAPaletteOfPlainStrings() throws IOException {
        // Unlike blocks, the format stores the biome palette as a list of names without properties.
        ListBinaryTag palette = ListBinaryTag.builder(BinaryTagTypes.STRING)
                .add(StringBinaryTag.stringBinaryTag("minecraft:air"))
                .add(StringBinaryTag.stringBinaryTag("minecraft:dirt"))
                .build();
        int[] indices = new int[64];
        indices[3] = 1;
        CompoundBinaryTag container = CompoundBinaryTag.builder()
                .put("palette", palette)
                .put("data", LongArrayBinaryTag.longArrayBinaryTag(BitPacker.pack(indices, 1)))
                .build();

        int[] values = SectionCodec.decodeBiomes(container, new FakeResolver(), 64, 1).unpack();

        assertEquals(2, values[3]);
        assertEquals(0, values[0]);
    }

    @Test
    void testDecodingASingleBiomeNeedsNoData() throws IOException {
        CompoundBinaryTag container = CompoundBinaryTag.builder()
                .put("palette", ListBinaryTag.builder(BinaryTagTypes.STRING)
                        .add(StringBinaryTag.stringBinaryTag("minecraft:dirt"))
                        .build())
                .build();

        PaletteData data = SectionCodec.decodeBiomes(container, new FakeResolver(), 64, 1);

        assertTrue(data.isSingleValue());
        assertEquals(2, data.singleValue());
    }

    @Test
    void testEncodingBiomesWritesPlainStrings() {
        CompoundBinaryTag container = SectionCodec.encodeBiomes(PaletteData.single(1, 64), new FakeResolver());

        assertEquals(BinaryTagTypes.STRING, container.getList("palette").elementType());
        assertEquals("minecraft:stone", container.getList("palette").getString(0));
    }

    @Test
    void testBiomesSurviveARoundTrip() throws IOException {
        FakeResolver resolver = new FakeResolver();
        int[] values = new int[64];

        for (int i = 0; i < values.length; i++) {
            values[i] = i % 3;
        }

        CompoundBinaryTag encoded = SectionCodec.encodeBiomes(PaletteData.encode(values, 1), resolver);
        PaletteData decoded = SectionCodec.decodeBiomes(encoded, resolver, 64, 1);

        assertArrayEquals(values, decoded.unpack());
    }

    @Test
    void testAContainerSurvivesARoundTrip() throws IOException {
        FakeResolver resolver = new FakeResolver();
        int[] values = new int[BLOCK_ENTRIES];

        for (int i = 0; i < values.length; i++) {
            values[i] = i % 3;
        }

        CompoundBinaryTag encoded = SectionCodec.encode(PaletteData.encode(values, BLOCK_MIN_BITS), resolver);
        PaletteData decoded = SectionCodec.decode(encoded, resolver, BLOCK_ENTRIES, BLOCK_MIN_BITS);

        assertArrayEquals(values, decoded.unpack());
    }
}
