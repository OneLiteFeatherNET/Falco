package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.player.PlayerChunkUnloadEvent;
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

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Asserts what US-4.01 actually asks for: a player moving into a shared instance receives no chunk
 * traffic at all.
 * <p>
 * {@code areLinked} is the mechanism and is covered next door; this class covers the outcome, so
 * that a change to {@code Player#setInstance} is caught here instead of costing a full resend per
 * transfer in production.
 * </p>
 * <h2>Which marker carries the weight</h2>
 * <p>
 * {@code ChunkDataPacket} is the direct measure of a resend, and here it does read a real number.
 * Cyano installs {@code net.minestom.testing.TestPlayerImpl} as the player provider of every test
 * connection, and that class overrides {@code Player#sendChunk(Chunk)} to push the full data packet
 * out at once instead of queueing it. {@code chunkAdder} dispatches virtually, so the batching that
 * would otherwise swallow the counter — a {@code chunkBatchLead} that {@code resetChunkQueue} never
 * clears, plus a test connection that never acknowledges a batch — is never reached. The unlinked
 * control below measures 25 of them, one per chunk of the 5x5 view that {@code viewDistance(1)}
 * produces. That makes the empty chunk collector in the fast-path test the strongest and most
 * direct US-4.01 statement in this file, not a decorative one.
 * </p>
 * <p>
 * {@code UpdateViewPositionPacket} and {@code PlayerChunkUnloadEvent} are asserted next to it
 * because they leave {@code Player#spawnPlayer} unconditionally under {@code updateChunks == true},
 * independently of how chunk bodies are delivered. They keep the file honest if a future Cyano
 * stops overriding {@code sendChunk} — at which point the chunk counter would silently drop to zero
 * on both paths, and the control's {@code assertCount(25)} would say so instead of hiding it.
 * </p>
 * <h2>Why the unload is an event and not a packet</h2>
 * <p>
 * Up to Minestom 26.1.2 the old view left {@code spawnPlayer} as one {@code UnloadChunkPacket} per
 * chunk, and this file asserted those packets. Since 26.2 the packet is withheld for a chunk that
 * is also in the new view: the client of 26.2 drops a chunk it receives an unload and a data packet
 * for within the same frame (MC-310041), and Minestom carries the workaround with a
 * {@code TODO(26.3)} to revert it once the client is fixed. The control below transfers to the same
 * position, so every one of its 25 chunks is in the new view and not a single packet is sent — the
 * per-chunk unload is still there, as the event that fires beside the suppressed packet. Asserting
 * the event keeps the marker measuring what it was written to measure across both behaviours, and
 * {@code unloads.assertEmpty()} states the 26.2 suppression itself, so the revert in 26.3 fails
 * here rather than passing unnoticed.
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
        final Collector<PlayerChunkUnloadEvent> unloadEvents =
                env.trackEvent(PlayerChunkUnloadEvent.class, EventFilter.PLAYER, player);

        player.setInstance(shared, SPAWN).join();

        assertSame(shared, player.getInstance());
        views.assertEmpty();
        unloads.assertEmpty();
        // The packet is suppressed by 26.2 whenever the chunk stays in view, so the event is what
        // separates the fast path from the slow one: the fast path never enters the unload loop.
        unloadEvents.assertEmpty();
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
        final Collector<ChunkDataPacket> chunks = connection.trackIncoming(ChunkDataPacket.class);
        final Collector<PlayerChunkUnloadEvent> unloadEvents =
                env.trackEvent(PlayerChunkUnloadEvent.class, EventFilter.PLAYER, player);

        player.setInstance(unrelated, SPAWN).join();

        assertSame(unrelated, player.getInstance());
        views.assertCount(1);
        // The slow path unloads the old view chunk by chunk: 25 events for the 5x5 view, the same
        // number as the chunk bodies below. If this ever reads zero the markers are wrong, not the
        // fast path.
        unloadEvents.assertCount(25);
        // The transfer keeps the position, so all 25 chunks of the old view are also in the new one
        // and 26.2 sends no unload packet for any of them — see the class comment.
        unloads.assertEmpty();
        // 25 = the 5x5 view of viewDistance(1). This is the resend the fast path avoids, and it is
        // the number that makes the empty chunk collector over there mean something.
        chunks.assertCount(25);
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
