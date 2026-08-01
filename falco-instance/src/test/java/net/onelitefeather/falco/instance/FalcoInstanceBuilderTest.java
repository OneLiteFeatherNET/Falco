package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the builder of the instance and the shutdown it makes reachable.
 * <p>
 * The order inside {@code shutdown} is the part that costs data if it is wrong.
 * {@code saveChunksToStorage} takes a snapshot of the chunk map, and {@code unregister} empties that
 * map, so a save after the unregister writes nothing and reports success. The tests here pin the
 * order rather than trusting the reading.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoInstanceBuilderTest {

    @Test
    void testTheBuilderRegistersAnInstanceWithTheDefaultsOfTheConstructors(Env env) {
        InstanceManager manager = env.process().instance();

        FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD).register(manager);

        assertTrue(instance.isRegistered());
        assertTrue(manager.getInstances().contains(instance));
        assertSame(ChunkLoader.noop().getClass(), instance.getChunkLoader().getClass(),
                "no loader means the no-op loader, as in the constructors");
    }

    @Test
    void testASlotLeavesTheBuilderItWasCalledOnUnchanged(Env env) {
        UUID first = UUID.randomUUID();
        FalcoInstance.Builder base = FalcoInstance.builder(DimensionType.OVERWORLD).uuid(first);
        FalcoInstance.Builder derived = base.uuid(UUID.randomUUID());

        assertNotSame(base, derived, "a slot returns a new builder");
        assertEquals(first, base.register(env.process().instance()).getUuid(),
                "the origin still carries the uuid it was given");
    }

    @Test
    void testShutdownSavesBeforeItUnregisters(Env env) {
        RecordingLoader loader = new RecordingLoader();
        InstanceManager manager = env.process().instance();

        FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .register(manager);
        instance.loadChunk(0, 0).join();

        instance.shutdown(manager);

        assertEquals(1, loader.saved.size(), "the chunk was saved, which only works before the unregister");
        assertFalse(instance.isRegistered());
        assertTrue(instance.getChunks().isEmpty());
    }

    @Test
    void testShutdownCanSkipTheSaveForAWorldThatIsOnlyRead(Env env) {
        RecordingLoader loader = new RecordingLoader();
        InstanceManager manager = env.process().instance();

        FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .saveOnShutdown(false)
                .register(manager);
        instance.loadChunk(0, 0).join();

        instance.shutdown(manager);

        assertTrue(loader.saved.isEmpty(), "a read-only world says so and nothing is written");
        assertFalse(instance.isRegistered());
    }

    @Test
    void testShutdownClosesTheLoaderOnlyWhenTheInstanceOwnsIt(Env env) throws IOException {
        RecordingLoader shared = new RecordingLoader();
        RecordingLoader owned = new RecordingLoader();
        InstanceManager manager = env.process().instance();

        FalcoInstance borrower = FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(shared)
                .register(manager);
        FalcoInstance owner = FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(owned)
                .ownsLoader(true)
                .register(manager);

        borrower.shutdown(manager);
        owner.shutdown(manager);

        assertFalse(shared.closed, "a borrowed loader outlives the instance that used it");
        assertTrue(owned.closed, "an owned loader is closed with its instance");
    }

    @Test
    void testAFailedSaveStopsTheShutdownInsteadOfCarryingOn(Env env) {
        InstanceManager manager = env.process().instance();
        FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(new FailingLoader())
                .register(manager);
        instance.loadChunk(0, 0).join();

        assertThrows(FalcoInstanceException.class, () -> instance.shutdown(manager));

        assertTrue(instance.isRegistered(),
                "the instance is still there, so a second attempt can still save the chunks");
        assertFalse(instance.getChunks().isEmpty(), "and the chunks are still in memory");
    }

    /**
     * A loader which records what it was asked to do and can be closed.
     */
    private static final class RecordingLoader implements ChunkLoader, AutoCloseable {

        private final List<Chunk> saved = new ArrayList<>();
        private boolean closed;

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
            this.saved.add(chunk);
        }

        @Override
        public void saveChunks(Collection<Chunk> chunks) {
            this.saved.addAll(chunks);
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    /**
     * A loader whose save always fails, so the shutdown has something to stop on.
     */
    private static final class FailingLoader implements ChunkLoader {

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
            throw new IllegalStateException("this loader cannot save");
        }

        @Override
        public void saveChunks(Collection<Chunk> chunks) {
            throw new IllegalStateException("this loader cannot save");
        }
    }
}
