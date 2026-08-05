package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import net.onelitefeather.falco.migration.ChunkMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The acceptance test of {@code falco-migration}: a chunk {@link ChunkMigration} converts is only
 * useful if {@link FalcoAnvilLoader} — the loader that actually has to read a converted world back —
 * can load it.
 * <p>
 * Every other test either module has asserts key by key against an in-memory {@link CompoundBinaryTag}.
 * That is exactly the gap the final review found: {@code NamespaceStatus} namespaced a chunk status
 * without translating its value, and nothing upstream of the loader noticed, because nothing exercised
 * the loader at all. This test instead runs the whole pipeline a converted world actually goes
 * through — a genuine 1.13 chunk, through the nine-step {@link ChunkMigration} chain, written into a
 * real region file exactly {@link FalcoAnvilLoaderIntegrationTest}'s own {@code writeRawChunk} helper
 * does, then loaded back through the production {@link FalcoAnvilLoader} — and checks that a block
 * placed by the original 1.13 chunk actually arrives. Both the chunk's {@code Status} value
 * ({@code postprocessed}, not {@code full} — see {@code NamespaceStatusTest} in {@code falco-migration}
 * for why) and its packed block data (built by hand in the pre-1.16 boundary-spanning layout, the
 * shape {@code NormaliseBitPacking} exists to re-pack) are the real, sourced 1.13 shapes, not stand-ins.
 */
@ExtendWith(MicrotusExtension.class)
class MigrationRoundTripTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");
    private static final int SECTION_BLOCK_ENTRIES = 16 * 16 * 16;

    @TempDir
    private Path worldRoot;

    @Test
    void testANineteenThirteenChunkMigratedAndReloadedThroughTheProductionLoaderKeepsItsBlocks(Env env) throws Exception {
        // A 2-entry palette (air, stone) packed at 4 bits per entry (BitPacker.bitsPerEntry(2, 4)),
        // in the pre-1.16 boundary-spanning layout every DataVersion below 2529 actually wrote. Every
        // block in the section is air (palette index 0) except one, index 0 of the packed array
        // itself (local x=0, y=0, z=0), which is stone (palette index 1).
        int[] values = new int[SECTION_BLOCK_ENTRIES];
        values[0] = 1;
        long[] legacyPacked = legacyPack(values, 4);

        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 2)
                .put("Palette", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putString("Name", "minecraft:air").build(),
                        CompoundBinaryTag.builder().putString("Name", "minecraft:stone").build())))
                .putLongArray("BlockStates", legacyPacked)
                .build();

        CompoundBinaryTag legacyChunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1519) // Minecraft 1.13 release, ChunkMigration's own floor
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 5)
                        .putInt("zPos", 5)
                        .putString("Status", "postprocessed") // the real 1.13 terminal status
                        .put("Sections", ListBinaryTag.from(List.of(section)))
                        .build())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(legacyChunk, MinecraftServer.DATA_VERSION);
        writeRawChunk(5, 5, migrated);

        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD)) {
            Instance instance = env.createEmptyInstance(loader);
            Chunk loaded = loader.loadChunk(instance, 5, 5);

            assertNotNull(loaded, "the loader's own status guard must accept the migrated chunk's "
                    + "translated status (minecraft:full), not just its namespace "
                    + "(minecraft:postprocessed would still be refused)");
            assertEquals(Block.STONE, blockAt(loaded, 0, 32, 0),
                    "the one block the 1.13 chunk actually placed must survive the whole round trip");
            assertEquals(Block.AIR, blockAt(loaded, 1, 32, 0),
                    "every other block in the section must stay air, proving the packed indices "
                            + "themselves, not just the palette, survived");
        }
    }

    /**
     * Writes chunk data straight into the region file of the temporary world, exactly
     * {@link FalcoAnvilLoaderIntegrationTest}'s own private helper of the same name does — duplicated
     * here rather than shared, since that helper is {@code private} to its own test class.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @param data   the chunk data to store
     * @throws Exception if the chunk cannot be written
     */
    private void writeRawChunk(int chunkX, int chunkZ, CompoundBinaryTag data) throws Exception {
        Path directory = this.worldRoot.resolve("dimensions/minecraft/overworld/region");
        Files.createDirectories(directory);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        BinaryTagIO.writer().writeNamed(
                Map.entry("", data), target, BinaryTagIO.Compression.NONE
        );

        try (RegionFile file = RegionFile.open(directory.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca"))) {
            file.writeRaw(chunkX, chunkZ, ChunkCompression.ZLIB, ChunkCompression.ZLIB.compress(target.toByteArray()));
        }
    }

    /**
     * Reads a block of the given chunk while holding its read lock, exactly
     * {@link FalcoAnvilLoaderIntegrationTest}'s own private helper of the same name does.
     *
     * @param chunk the chunk to read
     * @param x     the x coordinate inside the chunk
     * @param y     the y coordinate of the block
     * @param z     the z coordinate inside the chunk
     * @return the block at the given position
     */
    private static Block blockAt(Chunk chunk, int x, int y, int z) {
        chunk.lockReadLock();
        try {
            return chunk.getBlock(x, y, z);
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * Packs {@code values} using the pre-1.16 layout, in which an entry is allowed to span a long
     * boundary — the same fixture helper {@code SectionStepsTest} uses in {@code falco-migration},
     * duplicated here rather than shared across modules for the same reason
     * {@code FalcoAnvilLoaderIntegrationTest.writeRawChunk} is duplicated rather than exposed.
     */
    private static long[] legacyPack(int[] values, int bitsPerEntry) {
        long totalBits = (long) values.length * bitsPerEntry;
        long[] packed = new long[(int) ((totalBits + 63) / 64)];
        long mask = (1L << bitsPerEntry) - 1L;

        for (int index = 0; index < values.length; index++) {
            long bitOffset = (long) index * bitsPerEntry;
            int longIndex = (int) (bitOffset / 64);
            int bitInLong = (int) (bitOffset % 64);
            long value = values[index] & mask;

            packed[longIndex] |= value << bitInLong;
            int bitsWrittenInFirstLong = 64 - bitInLong;
            if (bitsWrittenInFirstLong < bitsPerEntry) {
                packed[longIndex + 1] |= value >>> bitsWrittenInFirstLong;
            }
        }
        return packed;
    }
}
