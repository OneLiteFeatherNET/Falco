package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.network.packet.server.play.UpdateViewPositionPacket;
import net.minestom.testing.Collector;
import net.minestom.testing.Env;
import net.minestom.testing.TestConnection;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Asserts what US-4.01 actually asks for: a player moving into a shared instance receives no chunk
 * traffic at all.
 * <p>
 * {@code areLinked} is the mechanism and is covered next door; this class covers the outcome, so
 * that a change to {@code Player#setInstance} is caught here instead of costing a full resend per
 * transfer in production. The markers are {@code UpdateViewPositionPacket} and
 * {@code UnloadChunkPacket}, both sent unconditionally by the slow path.
 * {@code ChunkDataPacket} is asserted as well but carries no weight on its own: after the first
 * spawn Minestom holds the chunk queue until the client acknowledges a batch, which a test
 * connection never does, so that counter reads zero on both paths.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("A player moving into a Falco shared instance")
class FalcoSharedInstanceResendTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);

    private static InstanceContainer container(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        // Keeps the transfer at 25 chunks instead of 289; the paths under test do not depend on it.
        container.viewDistance(1);
        return container;
    }

    @Test
    @DisplayName("receives no view update and no chunk unload, because the chunks are the same")
    void testTheFastPathSendsNothing(Env env) {
        final InstanceContainer container = container(env);
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.viewDistance(1);

        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(container, SPAWN);

        final Collector<UpdateViewPositionPacket> views = connection.trackIncoming(UpdateViewPositionPacket.class);
        final Collector<UnloadChunkPacket> unloads = connection.trackIncoming(UnloadChunkPacket.class);
        final Collector<ChunkDataPacket> chunks = connection.trackIncoming(ChunkDataPacket.class);

        player.setInstance(shared, SPAWN).join();

        assertSame(shared, player.getInstance());
        views.assertEmpty();
        unloads.assertEmpty();
        chunks.assertEmpty();
    }

    @Test
    @DisplayName("receives the full treatment when the target does not share the chunks")
    void testTheSlowPathSendsTheMarkers(Env env) {
        final InstanceContainer container = container(env);
        final InstanceContainer unrelated = container(env);

        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(container, SPAWN);

        final Collector<UpdateViewPositionPacket> views = connection.trackIncoming(UpdateViewPositionPacket.class);
        final Collector<UnloadChunkPacket> unloads = connection.trackIncoming(UnloadChunkPacket.class);

        player.setInstance(unrelated, SPAWN).join();

        assertSame(unrelated, player.getInstance());
        views.assertCount(1);
        assertFalse(unloads.collect().isEmpty(),
                "the slow path unloads the old view chunk by chunk; if this is empty the markers are wrong, "
                        + "not the fast path");
    }

    @Test
    @DisplayName("takes the slow path when it lands in a different chunk, linked or not")
    void testTheFastPathNeedsTheSameChunk(Env env) {
        final InstanceContainer container = container(env);
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.viewDistance(1);

        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(container, SPAWN);

        final Collector<UpdateViewPositionPacket> views = connection.trackIncoming(UpdateViewPositionPacket.class);

        player.setInstance(shared, new Pos(500.5, 40, 500.5)).join();

        views.assertCount(1);
    }
}
