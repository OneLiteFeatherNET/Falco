package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.exception.ExceptionHandler;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a closed loader stays closed.
 * <p>
 * The loader reports parallel loading and saving as supported, so a server runs one task per chunk
 * and those tasks are still in flight when the shutdown closes the loader. A task which reaches the
 * region cache after that would open a file which nobody closes again, and it would write into a
 * world which is already considered closed. The tests here pin the behaviour of that window down.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoAnvilLoaderLifecycleTest {

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

    /**
     * Writes a single chunk into the temporary world so the load path has something to read.
     *
     * @param instance the instance which owns the chunk
     * @return the written chunk
     * @throws IOException if the chunk cannot be written
     */
    private Chunk storeChunk(Instance instance) throws IOException {
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 0, 40, 0, Block.STONE);

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunk(chunk);
        }
        return chunk;
    }

    @Test
    void testLoadingAfterCloseIsRejected(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        storeChunk(instance);

        FalcoAnvilLoader loader = loader();
        loader.close();

        // Returning the chunk would use a loader which released its files, and returning null would
        // make the server generate a replacement which overwrites the stored chunk on the next save.
        assertThrows(IllegalStateException.class, () -> loader.loadChunk(instance, 0, 0));
    }

    @Test
    void testLoadingAfterCloseOpensNoRegionFile(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        storeChunk(instance);

        FalcoAnvilLoader loader = loader();
        loader.close();

        assertThrows(IllegalStateException.class, () -> loader.loadChunk(instance, 0, 0));
        assertEquals(
                0, loader.openRegionCount(),
                "a closed loader may not hold a region file because nothing closes it again"
        );
    }

    @Test
    void testSavingAfterCloseIsRejected(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        Chunk chunk = storeChunk(instance);

        FalcoAnvilLoader loader = loader();
        loader.close();

        // Swallowing the call would write into a world which is already considered closed and leak
        // the region file it opened for that write.
        assertThrows(IllegalStateException.class, () -> loader.saveChunk(chunk));
        assertEquals(0, loader.openRegionCount(), "a closed loader may not hold a region file");
    }

    @Test
    void testAClosedLoaderCanBeClosedAgain(Env env) throws IOException {
        Instance instance = env.createEmptyInstance(loader());
        storeChunk(instance);

        FalcoAnvilLoader loader = loader();
        Chunk loaded = loader.loadChunk(instance, 0, 0);

        assertNotNull(loaded);
        loader.close();
        loader.close();

        assertEquals(0, loader.openRegionCount());
    }

    @Test
    void testClosingWhileLoadsAreRunningLeavesNoOpenRegionFile(Env env) throws IOException, InterruptedException, ExecutionException {
        // A loader is closed while its tasks are still running, because the loader reports parallel
        // loading as supported and therefore receives one task per chunk. A task which reaches the
        // region cache after the close would open a file which nothing closes again, and it would
        // find the cache emptied right under it. A load which is refused is fine, a load which
        // fails on a file the loader itself closed is not, and a load which reports the chunk as
        // absent would make the server overwrite it.
        int regionCount = 8;
        int rounds = 40;
        Instance instance = env.createEmptyInstance(loader());
        List<Chunk> chunks = new ArrayList<>(regionCount);

        for (int region = 0; region < regionCount; region++) {
            Chunk chunk = instance.loadChunk(region * 32, 0).join();
            place(chunk, 0, 40, 0, Block.STONE);
            chunks.add(chunk);
        }

        try (FalcoAnvilLoader writer = loader()) {
            writer.saveChunks(chunks);
        }

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch running = new CountDownLatch(regionCount);
        FalcoAnvilLoader loader = loader();
        ExceptionHandler previous = MinecraftServer.getExceptionManager().getExceptionHandler();
        MinecraftServer.getExceptionManager().setExceptionHandler(_ -> {
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(chunks.size());

            for (Chunk chunk : chunks) {
                futures.add(executor.submit(() -> {
                    running.countDown();

                    for (int round = 0; round < rounds; round++) {
                        try {
                            Chunk loaded = loader.loadChunk(instance, chunk.getChunkX(), chunk.getChunkZ());

                            if (loaded == null) {
                                failures.add(new IllegalStateException(
                                        "the chunk " + chunk.getChunkX() + "/" + chunk.getChunkZ() + " was reported as absent"
                                ));
                            }
                        } catch (IllegalStateException expected) {
                            // A loader which is closed refuses further work, which is the contract.
                            return null;
                        } catch (Throwable failure) {
                            failures.add(failure);
                        }
                    }
                    return null;
                }));
            }
            assertTrue(running.await(60L, TimeUnit.SECONDS), "the workers did not start in time");
            loader.close();

            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            MinecraftServer.getExceptionManager().setExceptionHandler(previous);
        }

        if (!failures.isEmpty()) {
            fail("a load may only be refused, never fail, while the loader closes but " + failures.size()
                    + " of them failed, the first one was: " + failures.getFirst(), failures.getFirst());
        }
        assertEquals(
                0, loader.openRegionCount(),
                "a task which ran into the close may not leave a region file behind because nothing closes it again"
        );
    }

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
}
