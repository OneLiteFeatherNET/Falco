package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down that {@link FalcoChunk} holds the same block at every position as {@code DynamicChunk},
 * the chunk Minestom ships with.
 * <p>
 * This is the equivalence US-1.03 promises and NFR-004 requires evidence for: a bridge chunk that
 * merely compiles against {@code Chunk} is not enough, since the storage behind it was rewritten from
 * scratch in Task 3. The two chunks are filled through the very same {@code fill} routine from the
 * very same seed and then read back position by position, so a divergence names the exact coordinate
 * and the exact fill it happened under instead of a vague "the chunks disagree somewhere".
 * </p>
 * <p>
 * The parameter axis is the number of distinct block states a chunk is filled with, from a single
 * state up to 1024. A palette-backed storage bit-packs its entries only as wide as the number of
 * distinct states demands, so a bug tied to a particular bit width would hide at one end of this axis
 * and show only at the other.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@DisplayName("A Falco chunk against the chunk of Minestom")
class FalcoChunkEquivalenceTest {

    private static final long SEED = 20260801L;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private static Instance instance;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
    }

    private static void fill(Chunk chunk, int distinctStates, long seed) {
        final Random random = new Random(seed);
        final Block[] blocks = new Block[distinctStates];

        for (int index = 0; index < distinctStates; index++) {
            blocks[index] = Block.fromStateId(index + 1);
        }
        chunk.lockWriteLock();
        try {
            for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        chunk.setBlock(x, y, z, blocks[random.nextInt(distinctStates)]);
                    }
                }
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    @ParameterizedTest(name = "{0} distinct states")
    @ValueSource(ints = {1, 2, 16, 64, 256, 1024})
    @DisplayName("holds the same block at every position")
    void testEveryPositionAgrees(int distinctStates) {
        final Chunk minestom = new DynamicChunk(instance, 0, 0);
        final Chunk falco = new FalcoChunk(instance, 0, 0);

        fill(minestom, distinctStates, SEED);
        fill(falco, distinctStates, SEED);

        int nonAir = 0;

        minestom.lockReadLock();
        falco.lockReadLock();
        try {
            for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final Block expected = minestom.getBlock(x, y, z);
                        final Block actual = falco.getBlock(x, y, z);

                        assertEquals(expected, actual,
                                "block at " + x + "/" + y + "/" + z + " with " + distinctStates + " states");
                        if (!expected.isAir()) {
                            nonAir++;
                        }
                    }
                }
            }
        } finally {
            falco.unlockReadLock();
            minestom.unlockReadLock();
        }
        assertTrue(nonAir > 0, "the fixture wrote nothing, so this run compared two empty chunks");
    }
}
