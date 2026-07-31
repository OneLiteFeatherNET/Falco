package net.onelitefeather.falco.instance;

import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.event.instance.InstanceUnregisterEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the unload path of {@link FalcoInstance}.
 * <p>
 * This is the reason the module exists. {@code InstanceManager#unregisterInstance} only unloads
 * chunks for an {@code InstanceContainer}, so an instance of any other type keeps every chunk it
 * ever loaded after it has been unregistered. The cases here fix the behaviour on the Falco side and
 * pin the Minestom behaviour that makes the fix necessary, so an upgrade which changes either of the
 * two is noticed here rather than in a heap dump.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoInstanceUnloadTest {

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
    void testUnloadChunkRemovesItAndClearsItsLoadedFlag(Env env) {
        final FalcoInstance instance = registered(env);
        final Chunk chunk = instance.loadChunk(0, 0).join();

        instance.unloadChunk(chunk);

        assertFalse(chunk.isLoaded());
        assertNull(instance.getChunk(0, 0));
        assertTrue(instance.getChunks().isEmpty());
    }

    @Test
    void testUnloadChunkCallsTheUnloadEventOnce(Env env) {
        final FalcoInstance instance = registered(env);
        final Chunk chunk = instance.loadChunk(0, 0).join();
        final AtomicInteger calls = new AtomicInteger();
        instance.eventNode().addListener(InstanceChunkUnloadEvent.class, event -> calls.incrementAndGet());

        instance.unloadChunk(chunk);
        instance.unloadChunk(chunk);

        assertEquals(1, calls.get());
    }

    @Test
    void testUnregisterUnloadsEveryChunk(Env env) {
        final InstanceManager manager = env.process().instance();
        final FalcoInstance instance = registered(env);
        final Chunk first = instance.loadChunk(0, 0).join();
        final Chunk second = instance.loadChunk(1, 0).join();

        instance.unregister(manager);

        assertFalse(instance.isRegistered());
        assertFalse(manager.getInstances().contains(instance));
        assertTrue(instance.getChunks().isEmpty());
        assertFalse(first.isLoaded());
        assertFalse(second.isLoaded());
    }

    @Test
    void testUnregisterStillCallsTheUnregisterEvent(Env env) {
        final InstanceManager manager = env.process().instance();
        final FalcoInstance instance = registered(env);
        final AtomicInteger calls = new AtomicInteger();
        instance.eventNode().addListener(InstanceUnregisterEvent.class, event -> calls.incrementAndGet());

        instance.unregister(manager);

        assertEquals(1, calls.get());
    }

    @Test
    void testUnregisterTwiceIsHarmless(Env env) {
        final InstanceManager manager = env.process().instance();
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();

        instance.unregister(manager);
        instance.unregister(manager);

        assertTrue(instance.getChunks().isEmpty());
    }

    @Test
    void testTheInstanceManagerAloneStillLeaksTheChunks(Env env) {
        final InstanceManager manager = env.process().instance();
        final FalcoInstance instance = registered(env);
        final Chunk chunk = instance.loadChunk(0, 0).join();

        manager.unregisterInstance(instance);

        // Pinned against Minestom 2026.06.20-26.1.2: the manager only unloads chunks for an
        // InstanceContainer, which is why FalcoInstance#unregister exists. If this ever starts to
        // fail, Minestom has learned to clean up foreign instances and the own path can shrink.
        assertFalse(instance.isRegistered());
        assertTrue(chunk.isLoaded());
        assertEquals(1, instance.getChunks().size());

        instance.unloadChunk(chunk);
    }
}
