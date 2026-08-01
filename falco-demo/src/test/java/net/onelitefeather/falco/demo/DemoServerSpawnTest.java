package net.onelitefeather.falco.demo;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down where the demo puts a player.
 * <p>
 * The position matters more than it looks. It is the first thing a person sees, and the search for
 * it is also the only place that notices a loader which reads nothing: a chunk the region header
 * lists but which arrives without a single block is the signature of a loader pointed at the wrong
 * directory. Without that warning somebody flies through an invisible world and concludes that the
 * other stack is faster, which is the one wrong answer this demo exists to prevent.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.0.0
 */
@ExtendWith(MicrotusExtension.class)
class DemoServerSpawnTest {

    @Test
    void testTheSpawnSitsAboveTheHighestBlockOfItsChunk(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(3, -2).join();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(8, 41, 8, Block.STONE);
        } finally {
            chunk.unlockWriteLock();
        }

        Pos spawn = DemoServer.spawn(instance, new ChunkInventory.ChunkPosition(3, -2));

        assertEquals(3 * 16 + 8 + 0.5, spawn.x(), "the spawn is in the middle of the chunk it was given");
        assertEquals(-2 * 16 + 8 + 0.5, spawn.z());
        assertTrue(spawn.y() > 41, "and above the block it found, not below it: " + spawn.y());
    }

    /**
     * A chunk without a single block falls back rather than dropping the player into the void.
     */
    @Test
    void testAnEmptyChunkFallsBackToAFixedHeight(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        Pos spawn = DemoServer.spawn(instance, new ChunkInventory.ChunkPosition(0, 0));

        assertEquals(128, spawn.y(), "the documented fallback height");
    }

    /**
     * The spawn follows the chunk the world search found, rather than sitting at the origin.
     * <p>
     * This is the property that was lost: the server hardcoded {@code Pos(0, 65, 0)} and never
     * called the search at all, so a world whose chunks lie anywhere else put the player into empty
     * space — and the warning about a loader that reads nothing never ran.
     * </p>
     */
    @Test
    void testTheSpawnFollowsTheChunkItWasGivenRatherThanTheOrigin(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(10, 10).join();

        Pos spawn = DemoServer.spawn(instance, new ChunkInventory.ChunkPosition(10, 10));

        assertEquals(168.5, spawn.x(), "10 * 16 + 8 + 0.5, nowhere near the origin");
        assertEquals(168.5, spawn.z());
    }
}
