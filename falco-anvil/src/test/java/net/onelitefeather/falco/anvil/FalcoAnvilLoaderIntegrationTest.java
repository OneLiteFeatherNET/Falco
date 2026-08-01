package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the chunk loader against a running Minestom environment. The tests cover the round trip
 * of a chunk through the region file which is the behaviour the loader exists for.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoAnvilLoaderIntegrationTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    @TempDir
    private Path worldRoot;

    /**
     * Creates a loader for the temporary world of the test.
     *
     * @return the created loader
     */
    private FalcoAnvilLoader loader() {
        return new FalcoAnvilLoader(this.worldRoot, OVERWORLD);
    }

    @Test
    void testLoadingAnAbsentChunkReturnsNull(Env env) throws IOException {
        try (FalcoAnvilLoader loader = loader()) {
            Instance instance = env.createEmptyInstance(loader);

            assertNull(loader.loadChunk(instance, 0, 0));
        }
    }

    @Test
    void testTheLoaderReportsParallelSupport() throws IOException {
        try (FalcoAnvilLoader loader = loader()) {
            assertTrue(loader.supportsParallelLoading());
            assertTrue(loader.supportsParallelSaving());
        }
    }

    @Test
    void testASavedChunkKeepsItsBlocks(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(2, 3).join();
        place(chunk, 0, 40, 0, Block.STONE);
        place(chunk, 5, 41, 7, Block.DIRT);
        place(chunk, 15, 42, 15, Block.OAK_PLANKS);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader reader = loader()) {
            Chunk loaded = reader.loadChunk(instance, 2, 3);

            assertNotNull(loaded);
            assertEquals(Block.STONE, blockAt(loaded, 0, 40, 0));
            assertEquals(Block.DIRT, blockAt(loaded, 5, 41, 7));
            assertEquals(Block.OAK_PLANKS, blockAt(loaded, 15, 42, 15));
            assertEquals(Block.AIR, blockAt(loaded, 1, 40, 0));
        }
    }

    @Test
    void testTheRegionFileIsCreatedInTheDimensionDirectory(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 0, 40, 0, Block.STONE);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        Path expected = this.worldRoot.resolve("dimensions/minecraft/overworld/region/r.0.0.mca");
        assertTrue(Files.exists(expected), "expected a region file at " + expected);
    }

    @Test
    void testABlockWithNbtSurvivesTheRoundTrip(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(0, 0).join();
        Block sign = Block.OAK_SIGN.withNbt(CompoundBinaryTag.builder()
                .putString("falco_marker", "kept")
                .build());
        place(chunk, 3, 45, 3, sign);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader reader = loader()) {
            Chunk loaded = reader.loadChunk(instance, 0, 0);

            assertNotNull(loaded);
            Block restored = blockAt(loaded, 3, 45, 3);
            assertEquals(Block.OAK_SIGN.key(), restored.key());
            assertEquals("kept", restored.nbtOrEmpty().getString("falco_marker"));
        }
    }

    @Test
    void testBlockEntitiesAreStoredWithAbsoluteCoordinates(Env env) throws IOException {
        // The format stores the position of a block entity in world coordinates. Writing chunk
        // local ones still round trips through this loader, but the file would not be readable
        // by the game or by any other tool.
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(2, 3).join();
        Block sign = Block.OAK_SIGN.withNbt(CompoundBinaryTag.builder()
                .putString("falco_marker", "kept")
                .build());
        place(chunk, 5, 45, 7, sign);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        CompoundBinaryTag data = readStoredChunk(2, 3);
        ListBinaryTag entities =
                data.getList("block_entities", BinaryTagTypes.COMPOUND);

        assertEquals(1, entities.size());
        CompoundBinaryTag entity = entities.getCompound(0);
        assertEquals(2 * 16 + 5, entity.getInt("x"));
        assertEquals(45, entity.getInt("y"));
        assertEquals(3 * 16 + 7, entity.getInt("z"));
    }

    @Test
    void testABlockEntityInAFarChunkIsRestoredAtItsPosition(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(5, 9).join();
        Block sign = Block.OAK_SIGN.withNbt(CompoundBinaryTag.builder()
                .putString("falco_marker", "far")
                .build());
        place(chunk, 1, 44, 2, sign);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader reader = loader()) {
            Chunk loaded = reader.loadChunk(instance, 5, 9);

            assertNotNull(loaded);
            assertEquals("far", blockAt(loaded, 1, 44, 2).nbtOrEmpty().getString("falco_marker"));
        }
    }

    /**
     * Reads the stored chunk data straight from the region file without using the loader.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the stored chunk data
     * @throws IOException if the chunk cannot be read
     */
    private CompoundBinaryTag readStoredChunk(int chunkX, int chunkZ) throws IOException {
        Path region = this.worldRoot.resolve("dimensions/minecraft/overworld/region")
                .resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");

        try (RegionFile file = RegionFile.open(region)) {
            RegionFile.RawChunk raw = file.readRaw(chunkX, chunkZ);
            assertNotNull(raw);
            return BinaryTagIO.unlimitedReader().read(
                    new ByteArrayInputStream(raw.decompress()),
                    BinaryTagIO.Compression.NONE
            );
        }
    }

    @Test
    void testBlocksWithPropertiesKeepThem(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(1, 1).join();
        Block slab = Block.OAK_SLAB.withProperty("type", "top");
        place(chunk, 4, 44, 4, slab);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader reader = loader()) {
            Chunk loaded = reader.loadChunk(instance, 1, 1);

            assertNotNull(loaded);
            assertEquals("top", blockAt(loaded, 4, 44, 4).getProperty("type"));
        }
    }

    @Test
    void testSavingManyChunksInParallelKeepsEveryOne(Env env) throws IOException, InterruptedException, ExecutionException {
        Instance instance = env.createEmptyInstance(loader());
        List<Chunk> chunks = new ArrayList<>();

        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                Chunk chunk = instance.loadChunk(x, z).join();
                place(chunk, 0, 40, 0, Block.STONE);
                place(chunk, 1, 40, 0, x + z == 0 ? Block.DIRT : Block.OAK_PLANKS);
                chunks.add(chunk);
            }
        }

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunks(chunks);
        }

        try (FalcoAnvilLoader reader = loader()) {
            for (Chunk chunk : chunks) {
                Chunk loaded = reader.loadChunk(instance, chunk.getChunkX(), chunk.getChunkZ());

                assertNotNull(loaded, "chunk " + chunk.getChunkX() + "/" + chunk.getChunkZ() + " is missing");
                assertEquals(Block.STONE, blockAt(loaded, 0, 40, 0));
            }
        }
    }

    @Test
    void testLoadingInParallelReturnsEveryChunk(Env env) throws IOException, InterruptedException, ExecutionException {
        Instance instance = env.createEmptyInstance(loader());
        List<Chunk> chunks = new ArrayList<>();

        for (int x = 0; x < 4; x++) {
            Chunk chunk = instance.loadChunk(x, 0).join();
            place(chunk, 0, 40, 0, Block.STONE);
            chunks.add(chunk);
        }

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunks(chunks);
        }

        try (FalcoAnvilLoader reader = loader();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Chunk>> futures = new ArrayList<>();

            for (int x = 0; x < 4; x++) {
                int chunkX = x;
                futures.add(executor.submit(() -> reader.loadChunk(instance, chunkX, 0)));
            }
            for (Future<Chunk> future : futures) {
                assertNotNull(future.get());
            }
        }
    }

    @Test
    void testTheDiagnosticsCountTheProcessedChunks(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 0, 40, 0, Block.STONE);

        try (FalcoAnvilLoader loader = loader()) {
            loader.saveChunk(chunk);
            loader.loadChunk(instance, 0, 0);

            assertEquals(1, loader.diagnostics().chunksSaved());
            assertEquals(1, loader.diagnostics().chunksLoaded());
            assertEquals(0, loader.diagnostics().errors());
        }
    }

    @Test
    void testACorruptedChunkFailsInsteadOfLookingAbsent(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 0, 40, 0, Block.STONE);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        // Overwrite the payload with bytes which are not a valid compressed chunk. Reporting this
        // as an absent chunk would make the server regenerate it and overwrite the real data.
        Path region = this.worldRoot.resolve("dimensions/minecraft/overworld/region/r.0.0.mca");
        byte[] bytes = Files.readAllBytes(region);
        Arrays.fill(bytes, RegionConstants.HEADER_SIZE + 5, bytes.length, (byte) 0x7F);
        Files.write(region, bytes);

        try (FalcoAnvilLoader reader = loader()) {
            // The test environment turns a reported exception into an assertion error, so the test
            // asserts on the propagation itself instead of on a concrete type. What matters is that
            // the call does not return null, which would make the server regenerate the chunk.
            Throwable failure = assertThrows(Throwable.class, () -> reader.loadChunk(instance, 0, 0));

            assertNotNull(failure);
            assertEquals(1, reader.diagnostics().errors());
        }
    }

    @Test
    void testABlockHandlerSurvivesTheRoundTrip(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(0, 0).join();
        BlockHandler handler =
                MinecraftServer.getBlockManager().getHandlerOrDummy("minecraft:sign");
        place(chunk, 6, 46, 6, Block.OAK_SIGN.withHandler(handler));

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader reader = loader()) {
            Chunk loaded = reader.loadChunk(instance, 0, 0);

            assertNotNull(loaded);
            Block restored = blockAt(loaded, 6, 46, 6);
            assertNotNull(restored.handler(), "the block handler must be restored");
            assertEquals("minecraft:sign", restored.handler().getKey().asString());
        }
    }

    @Test
    void testTheHandlerIdIsNotKeptAsBlockNbt(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(0, 0).join();
        BlockHandler handler =
                MinecraftServer.getBlockManager().getHandlerOrDummy("minecraft:sign");
        place(chunk, 7, 46, 7, Block.OAK_SIGN.withHandler(handler));

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader reader = loader()) {
            Chunk loaded = reader.loadChunk(instance, 0, 0);

            assertNotNull(loaded);
            // The position and the handler id belong to the file format, not to the block itself.
            assertEquals("", blockAt(loaded, 7, 46, 7).nbtOrEmpty().getString("id"));
        }
    }

    @Test
    void testUnloadingEveryChunkOfARegionClosesItsFile(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());

        try (FalcoAnvilLoader loader = loader()) {
            Chunk first = instance.loadChunk(0, 0).join();
            Chunk second = instance.loadChunk(1, 0).join();
            place(first, 0, 40, 0, Block.STONE);
            place(second, 0, 40, 0, Block.STONE);
            loader.saveChunk(first);
            loader.saveChunk(second);

            Chunk loadedFirst = loader.loadChunk(instance, 0, 0);
            Chunk loadedSecond = loader.loadChunk(instance, 1, 0);
            assertNotNull(loadedFirst);
            assertNotNull(loadedSecond);
            assertEquals(1, loader.openRegionCount());

            loader.unloadChunk(loadedFirst);
            assertEquals(1, loader.openRegionCount(), "the file is still used by the second chunk");

            loader.unloadChunk(loadedSecond);
            assertEquals(0, loader.openRegionCount(), "the last chunk of the region was unloaded");
        }
    }

    @Test
    void testAnUnloadedChunkCanBeLoadedAgain(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());

        try (FalcoAnvilLoader loader = loader()) {
            Chunk chunk = instance.loadChunk(0, 0).join();
            place(chunk, 0, 40, 0, Block.STONE);
            loader.saveChunk(chunk);

            Chunk loaded = loader.loadChunk(instance, 0, 0);
            assertNotNull(loaded);
            loader.unloadChunk(loaded);

            Chunk again = loader.loadChunk(instance, 0, 0);

            assertNotNull(again);
            assertEquals(Block.STONE, blockAt(again, 0, 40, 0));
        }
    }

    @Test
    void testTheAmountOfOpenRegionFilesStaysBounded(Env env) throws IOException {
        // Chunks are unloaded without telling the loader which of its region files became unused,
        // so the loader has to bound the amount of open files itself instead of counting users.
        Instance instance = env.createEmptyInstance(loader());

        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD, 2)) {
            for (int region = 0; region < 5; region++) {
                Chunk chunk = instance.loadChunk(region * 32, 0).join();
                place(chunk, 0, 40, 0, Block.STONE);
                loader.saveChunk(chunk);
            }

            assertTrue(loader.openRegionCount() <= 2, "expected at most two open region files but found " + loader.openRegionCount());
        }
    }

    @Test
    void testAChunkStaysReadableAfterItsRegionFileWasEvicted(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());

        try (FalcoAnvilLoader loader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD, 1)) {
            Chunk first = instance.loadChunk(0, 0).join();
            place(first, 0, 40, 0, Block.STONE);
            loader.saveChunk(first);

            Chunk second = instance.loadChunk(64, 0).join();
            place(second, 0, 40, 0, Block.DIRT);
            loader.saveChunk(second);

            // The first region file was evicted by now and has to be reopened transparently.
            Chunk reloaded = loader.loadChunk(instance, 0, 0);

            assertNotNull(reloaded);
            assertEquals(Block.STONE, blockAt(reloaded, 0, 40, 0));
        }
    }

    @Test
    void testAChunkWithoutARegionFileIsCountedAsSkipped(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());

        try (FalcoAnvilLoader loader = loader()) {
            assertNull(loader.loadChunk(instance, 0, 0));

            assertEquals(1, loader.diagnostics().chunksSkippedWithoutRegionFile());
            assertEquals(0, loader.diagnostics().chunksSkippedWithoutEntry());
            assertEquals(0, loader.diagnostics().chunksSkippedAsPartial());
        }
    }

    @Test
    void testAChunkWithoutAnEntryInItsRegionFileIsCountedAsSkipped(Env env) throws IOException {
        // The region file exists because a neighbour of the chunk was written into it, so the two
        // reasons "no file at all" and "no entry in the file" can only be told apart by the counter.
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 0, 40, 0, Block.STONE);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader loader = loader()) {
            assertNull(loader.loadChunk(instance, 1, 1));

            assertEquals(0, loader.diagnostics().chunksSkippedWithoutRegionFile());
            assertEquals(1, loader.diagnostics().chunksSkippedWithoutEntry());
            assertEquals(0, loader.diagnostics().chunksSkippedAsPartial());
        }
    }

    @Test
    void testAPartiallyGeneratedChunkIsCountedUnderItsStatus(Env env) throws IOException {
        // minecraft:features is what a chunk carries which the game generated but never finished,
        // and it is the value a user has to see instead of a bare "not fully generated".
        Instance instance = env.createEmptyInstance(loader());
        writeRawChunk(0, 0, CompoundBinaryTag.builder()
                .putString("Status", "minecraft:features")
                .build());
        writeRawChunk(1, 0, CompoundBinaryTag.builder()
                .putString("Status", "minecraft:features")
                .build());
        writeRawChunk(2, 0, CompoundBinaryTag.builder()
                .putString("status", "minecraft:noise")
                .build());

        try (FalcoAnvilLoader loader = loader()) {
            assertNull(loader.loadChunk(instance, 0, 0));
            assertNull(loader.loadChunk(instance, 1, 0));
            assertNull(loader.loadChunk(instance, 2, 0));

            assertEquals(3, loader.diagnostics().chunksSkippedAsPartial());
            assertEquals(
                    Map.of("minecraft:features", 2L, "minecraft:noise", 1L),
                    loader.diagnostics().partialChunkStatuses()
            );
            assertEquals(0, loader.diagnostics().chunksSkippedWithoutRegionFile());
            assertEquals(0, loader.diagnostics().chunksSkippedWithoutEntry());
        }
    }

    @Test
    void testTheResolvedRegionDirectoryIsTheDimensionOne() throws IOException {
        Files.createDirectories(this.worldRoot.resolve("dimensions/minecraft/overworld/region"));

        try (FalcoAnvilLoader loader = loader()) {
            assertEquals(this.worldRoot.resolve("dimensions/minecraft/overworld/region"), loader.regionDirectory());
            assertFalse(loader.legacyLayout());
        }
    }

    @Test
    void testTheResolvedRegionDirectoryFallsBackToTheOlderLayout() throws IOException {
        Files.createDirectories(this.worldRoot.resolve("region"));

        try (FalcoAnvilLoader loader = loader()) {
            assertEquals(this.worldRoot.resolve("region"), loader.regionDirectory());
            assertTrue(loader.legacyLayout());
        }
    }

    @Test
    void testAnEmptyDimensionDirectoryStillWinsOverAFilledOlderOne(Env env) throws IOException {
        // This is the trap the diagnostics exist for, not a behaviour anybody decided on: an empty
        // dimension directory next to a filled 'region' one sends the loader looking into the empty
        // one while every tool which scans the world finds the chunks in the other. The test pins
        // the behaviour so a later fix has to change it deliberately, and it checks that the
        // counters name what happened.
        Instance instance = env.createEmptyInstance(loader());
        Files.createDirectories(this.worldRoot.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(this.worldRoot.resolve("region"));

        try (FalcoAnvilLoader loader = loader()) {
            assertEquals(this.worldRoot.resolve("dimensions/minecraft/overworld/region"), loader.regionDirectory());
            assertNull(loader.loadChunk(instance, 0, 0));
            assertEquals(1, loader.diagnostics().chunksSkippedWithoutRegionFile());
        }
    }

    /**
     * Writes chunk data straight into the region file of the temporary world.
     * <p>
     * The loader itself only ever writes a chunk which is fully generated, so a chunk with any
     * other status has to be placed by hand.
     * </p>
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @param data   the chunk data to store
     * @throws IOException if the chunk cannot be written
     */
    private void writeRawChunk(int chunkX, int chunkZ, CompoundBinaryTag data) throws IOException {
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

    @Test
    void testUnloadingAForeignChunkIsIgnored(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = instance.loadChunk(9, 9).join();

        try (FalcoAnvilLoader loader = loader()) {
            loader.unloadChunk(chunk);
        }
    }

    /**
     * Places a block in the given chunk while holding its write lock.
     * The block setter of a chunk requires the caller to hold that lock.
     *
     * @param chunk the chunk which receives the block
     * @param x     the x coordinate inside the chunk
     * @param y     the y coordinate of the block
     * @param z     the z coordinate inside the chunk
     * @param block the block to place
     */
    private static void place(Chunk chunk, int x, int y, int z, Block block) {
        chunk.lockWriteLock();
        try {
            chunk.setBlock(x, y, z, block);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Reads a block of the given chunk while holding its read lock.
     * The block getter of a chunk requires the caller to hold that lock.
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
}
