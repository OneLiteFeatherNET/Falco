package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.onelitefeather.falco.light.ChunkLightScheduler;
import net.onelitefeather.falco.light.ChunkLightService;
import net.onelitefeather.falco.light.FalcoLightingChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Proves that the three modules run as one stack, through both doors that reach it.
 * <p>
 * This is the combination the project could not build at all, and the reason is worth keeping in
 * view: {@code FalcoInstance} accepted only {@code FalcoChunk} because {@code Chunk#onLoad()} and
 * {@code Chunk#unload()} are {@code protected} and unreachable across a package, while a lighting
 * chunk extended {@code DynamicChunk} — one superclass slot, two claimants.
 * </p>
 * <p>
 * Two doors, because two of them are open and each is worth pinning. {@code setChunkLifecycle} takes
 * a pair of {@code Consumer<Chunk>} and works for a chunk type this repository never sees; that is
 * what the first three cases drive, and it is the route a consumer with their own chunk still takes.
 * {@code FalcoLightingChunk} no longer needs it, because US-3.06 made it a {@code FalcoChunk}, and
 * the last case drives that: a chunk supplier and nothing else.
 * </p>
 * <p>
 * The test lives here rather than in one of the three modules because this is the only module that
 * may know all of them.
 * </p>
 *
 * @author TheMeinerLP
 * @version 2.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoStackIntegrationTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    @TempDir
    private Path worldRoot;

    /**
     * Places a block in the given chunk while holding its write lock.
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

    @Test
    void testAFalcoInstanceCarriesLightingChunksAndLightsThem(Env env) {
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(service)
                .executor(Runnable::run)
                .build();

        FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        instance.setChunkSupplier(scheduler.supplier());
        instance.setChunkLifecycle(
                chunk -> ((FalcoLightingChunk) chunk).markLoaded(),
                chunk -> ((FalcoLightingChunk) chunk).markUnloaded());
        env.process().instance().registerInstance(instance);

        Chunk chunk = instance.loadChunk(0, 0).join();

        assertInstanceOf(FalcoLightingChunk.class, chunk, "the instance carries the chunk of the light module");

        place(chunk, 8, 40, 8, Block.GLOWSTONE);
        chunk.tick(1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8), "and the light engine lights it");
    }

    @Test
    void testTheConfiguredLifecycleUnloadsALightingChunk(Env env) {
        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(new ChunkLightService())
                .executor(Runnable::run)
                .build();

        FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        instance.setChunkSupplier(scheduler.supplier());
        instance.setChunkLifecycle(
                chunk -> ((FalcoLightingChunk) chunk).markLoaded(),
                chunk -> ((FalcoLightingChunk) chunk).markUnloaded());
        env.process().instance().registerInstance(instance);

        Chunk chunk = instance.loadChunk(0, 0).join();
        instance.unloadChunk(chunk);

        assertNull(instance.getChunk(0, 0), "the instance let the chunk go");
        assertEquals(false, chunk.isLoaded(), "and the chunk was told, through the configured half");
    }

    @Test
    void testTheWholeStackRunsWithTheAnvilLoaderUnderneath(Env env) throws IOException {
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(service)
                .executor(Runnable::run)
                .build();

        try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder().build(this.worldRoot, OVERWORLD)) {
            FalcoInstance instance =
                    new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
            instance.setChunkSupplier(scheduler.supplier());
            instance.setChunkLifecycle(
                    chunk -> ((FalcoLightingChunk) chunk).markLoaded(),
                    chunk -> ((FalcoLightingChunk) chunk).markUnloaded());
            env.process().instance().registerInstance(instance);

            Chunk chunk = instance.loadChunk(0, 0).join();

            assertNotNull(chunk, "an empty world yields a generated chunk rather than a failure");
            assertInstanceOf(FalcoLightingChunk.class, chunk);

            place(chunk, 8, 40, 8, Block.GLOWSTONE);
            chunk.tick(1L);

            assertEquals(15, service.blockLightAt(chunk, 8, 40, 8));

            instance.saveChunksToStorage().join();
        }
    }

    /**
     * The whole stack with no lifecycle pair at all, which is what US-3.06 bought.
     * <p>
     * Every other case in this class hands {@code FalcoInstance} two {@code Consumer<Chunk>} that
     * cast to {@code FalcoLightingChunk}. That pair was the price of the two chunk types being
     * unrelated; now one extends the other, so the instance drives the hooks itself and the caller
     * writes one line. The unload is asserted as well, because the pair used to be the only thing
     * that could clear the loaded flag of this chunk type.
     * </p>
     */
    @Test
    void testTheStackNeedsNoLifecyclePairAnyMore(Env env) throws IOException {
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = ChunkLightScheduler.builder(service)
                .executor(Runnable::run)
                .build();

        try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder().build(this.worldRoot, OVERWORLD)) {
            FalcoInstance instance =
                    new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
            instance.setChunkSupplier(scheduler.supplier());
            env.process().instance().registerInstance(instance);

            Chunk chunk = instance.loadChunk(0, 0).join();

            assertInstanceOf(FalcoLightingChunk.class, chunk);

            place(chunk, 8, 40, 8, Block.GLOWSTONE);
            chunk.tick(1L);

            assertEquals(15, service.blockLightAt(chunk, 8, 40, 8),
                    "the light runs without anybody wiring the two modules together");

            instance.unloadChunk(chunk);

            assertNull(instance.getChunk(0, 0), "the instance let the chunk go");
            assertFalse(chunk.isLoaded(), "and reached the unload hook without a configured half");
        }
    }
}
