package net.onelitefeather.falco.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.tag.Tag;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins whose tags a shared instance writes when it is asked to save.
 * <p>
 * The defect this covers is silent by construction: Minestom's shared instance forwards the call to
 * its container, the container hands itself to the loader, the loader writes the container's tags,
 * and the operation reports success. Nothing is lost that anyone could notice at the time — the tags
 * of the view are simply never written. So the assertion has to be on the argument the loader
 * received, not on whether the call succeeded.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("Saving a Falco shared instance")
class FalcoSharedInstanceSaveTest {

    private static final Tag<String> OWNER = Tag.String("owner");

    @Test
    @DisplayName("hands the loader this instance, with this instance's tags")
    void testTheViewSavesItsOwnTags(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader();
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.setTag(OWNER, "shared");

        shared.saveInstance().join();

        assertEquals(1, loader.saved().size());
        assertSame(shared, loader.saved().getFirst());
        assertEquals("shared", loader.written().getFirst().getString("owner"));
    }

    @Test
    @DisplayName("leaves the container's own save alone")
    void testTheContainerStillSavesItself(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader();
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.setTag(OWNER, "shared");

        container.saveInstance().join();

        assertEquals(1, loader.saved().size());
        assertSame(container, loader.saved().getFirst());
        assertEquals("container", loader.written().getFirst().getString("owner"));
    }

    /**
     * A loader which records the instance it was asked to save and the tags that instance carried
     * at that moment.
     */
    private static final class RecordingChunkLoader implements ChunkLoader {

        private final List<Instance> saved = new CopyOnWriteArrayList<>();
        private final List<CompoundBinaryTag> written = new CopyOnWriteArrayList<>();

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveInstance(Instance instance) {
            this.saved.add(instance);
            this.written.add(instance.tagHandler().asCompound());
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        private List<Instance> saved() {
            return this.saved;
        }

        private List<CompoundBinaryTag> written() {
            return this.written;
        }
    }
}
