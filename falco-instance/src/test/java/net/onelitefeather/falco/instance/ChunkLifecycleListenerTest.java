package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Establishes that a chunk can carry more than one lifecycle extension, which is US-3.03.
 * <p>
 * Before this stage a chunk had exactly one extension point and it was its superclass, so
 * {@code FalcoLightingChunk} occupied it and nothing else could be installed beside light. Two
 * listeners on one chunk, both notified on every transition, is the shape that removes that limit.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The lifecycle listeners of a chunk")
class ChunkLifecycleListenerTest {

    /**
     * A listener which writes down what it was told, in order.
     */
    private static final class Recording implements ChunkLifecycleListener {

        /**
         * The name this listener writes in front of every entry.
         */
        private final String name;

        /**
         * Where the entries go.
         */
        private final List<String> log;

        /**
         * Creates a recording listener.
         *
         * @param name the name of this listener
         * @param log  where the entries go
         */
        private Recording(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void onPublish(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":publish:" + event.chunk().getChunkX());
        }

        @Override
        public void onLoad(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":load");
        }

        @Override
        public void onTick(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":tick:" + event.time());
        }

        @Override
        public void onUnload(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":unload");
        }

        @Override
        public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
            this.log.add(this.name + ":block:" + x + "/" + y + "/" + z);
        }
    }

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("notifies both listeners on every transition, in registration order")
    void testTwoListenersBothHearEverything(Env env) {
        final FalcoInstance instance = registered(env);
        final List<String> log = new ArrayList<>();
        instance.lifecycle().addListener(new Recording("first", log));
        instance.lifecycle().addListener(new Recording("second", log));

        final Chunk chunk = instance.loadChunk(0, 0).join();
        chunk.lockWriteLock();
        try {
            FalcoChunk.require(chunk).setBlock(1, 64, 1, Block.STONE, null, null);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(7L);
        instance.unloadChunk(chunk);

        assertEquals(List.of(
                "first:publish:0", "second:publish:0",
                "first:load", "second:load",
                "first:block:1/64/1", "second:block:1/64/1",
                "first:tick:7", "second:tick:7",
                "first:unload", "second:unload"), log);
    }

    @Test
    @DisplayName("holds no listener until one is registered")
    void testAChunkStartsWithoutAListener(Env env) {
        final FalcoInstance instance = registered(env);

        assertNull(new FalcoChunk(instance, 0, 0).lifecycleListener(),
                "a chunk nobody listens to has to hold null, not an empty composite");
        assertNull(instance.lifecycle().listener());
    }

    @Test
    @DisplayName("keeps the single listener single when there is only one")
    void testOneListenerIsNotWrapped(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycleListener only = new Recording("only", new ArrayList<>());

        instance.lifecycle().addListener(only);

        assertSame(only, instance.lifecycle().listener(),
                "one listener composes with nothing, so it has to be stored as it is");
    }

    @Test
    @DisplayName("gives a chunk of a plain container a listener too")
    void testAChunkCanCarryItsOwnListener(Env env) {
        final FalcoInstance instance = registered(env);
        final List<String> log = new ArrayList<>();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        chunk.addLifecycleListener(new Recording("own", log));
        chunk.tick(3L);

        assertEquals(List.of("own:tick:3"), log,
                "the listener lives on the chunk, so a chunk outside a Falco instance can carry one");
    }
}
