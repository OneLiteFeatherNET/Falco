package net.onelitefeather.falco.benchmark.support;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.onelitefeather.falco.anvil.PaletteData;
import net.onelitefeather.falco.anvil.PaletteEntryResolver;
import net.onelitefeather.falco.anvil.SectionCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * The {@link ChunkPayloads} class turns a {@link ChunkColumn} into the NBT and the bytes a region
 * file stores.
 * <p>
 * The conversion follows the save path of the loader step by step. It builds one palette container
 * per section, wraps them into the chunk compound the format defines and serialises the result
 * without compression. A benchmark can therefore compress the very same bytes a real save would
 * hand to the region file.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class ChunkPayloads {

    private static final BinaryTagIO.Writer TAG_WRITER = BinaryTagIO.writer();
    private static final int INITIAL_BUFFER = 64 * 1024;

    /**
     * Blocks the creation of an instance because the class only holds converters.
     */
    private ChunkPayloads() {
    }

    /**
     * Builds the chunk compound of the given chunk.
     *
     * @param column        the chunk to describe
     * @param blockResolver the resolver which names the block states
     * @param biomeResolver the resolver which names the biomes
     * @return the chunk data of the chunk
     */
    public static CompoundBinaryTag encode(ChunkColumn column, PaletteEntryResolver blockResolver, PaletteEntryResolver biomeResolver) {
        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);

        for (int section = 0; section < column.sectionCount(); section++) {
            sections.add(encodeSection(column, section, blockResolver, biomeResolver));
        }

        return CompoundBinaryTag.builder()
                .putInt("DataVersion", 4189)
                .putInt("xPos", 0)
                .putInt("zPos", 0)
                .putInt("yPos", -4)
                .putString("Status", "minecraft:full")
                .putLong("LastUpdate", 0L)
                .put("sections", sections.build())
                .put("block_entities", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).build())
                .build();
    }

    /**
     * Builds the data of a single section.
     *
     * @param column        the chunk which holds the section
     * @param section       the index of the section inside the chunk
     * @param blockResolver the resolver which names the block states
     * @param biomeResolver the resolver which names the biomes
     * @return the data of the section
     */
    private static CompoundBinaryTag encodeSection(ChunkColumn column, int section, PaletteEntryResolver blockResolver, PaletteEntryResolver biomeResolver) {
        PaletteData blocks = PaletteData.encode(column.blockStates()[section], BenchmarkConstants.BLOCK_PALETTE_MIN_BITS);
        PaletteData biomes = PaletteData.encode(column.biomes()[section], BenchmarkConstants.BIOME_PALETTE_MIN_BITS);

        return CompoundBinaryTag.builder()
                .putByte("Y", (byte) (section - 4))
                .put("block_states", SectionCodec.encode(blocks, blockResolver))
                .put("biomes", SectionCodec.encodeBiomes(biomes, biomeResolver))
                .putByteArray("SkyLight", column.skyLight()[section])
                .putByteArray("BlockLight", column.blockLight()[section])
                .build();
    }

    /**
     * Serialises the given chunk compound without compressing it.
     *
     * @param data the chunk data to serialise
     * @return the uncompressed bytes of the chunk
     * @throws IOException if the data cannot be written
     */
    public static byte[] serialize(CompoundBinaryTag data) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream(INITIAL_BUFFER);
        TAG_WRITER.writeNamed(Map.entry("", data), target, BinaryTagIO.Compression.NONE);
        return target.toByteArray();
    }

    /**
     * Builds the uncompressed bytes of a chunk in one step.
     *
     * @param column        the chunk to describe
     * @param blockResolver the resolver which names the block states
     * @param biomeResolver the resolver which names the biomes
     * @return the uncompressed bytes of the chunk
     * @throws IOException if the data cannot be written
     */
    public static byte[] serialize(ChunkColumn column, PaletteEntryResolver blockResolver, PaletteEntryResolver biomeResolver) throws IOException {
        return serialize(encode(column, blockResolver, biomeResolver));
    }
}
