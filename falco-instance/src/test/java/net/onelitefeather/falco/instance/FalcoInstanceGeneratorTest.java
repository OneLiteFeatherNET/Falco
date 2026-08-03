package net.onelitefeather.falco.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the generator path of {@link FalcoInstance}.
 * <p>
 * A generator is the only source of blocks for a world which has no chunk loader, so the cases here
 * first pin that it runs at all and that it runs only when the loader had nothing. The larger half
 * of the class is about the failure behaviour, because that is where the implementation of Minestom
 * cannot be copied: {@code InstanceContainer#generateChunk(Chunk, Generator)} hands the live block
 * palettes of the chunk to the generator and swallows whatever the generator throws into the
 * exception manager. A generator which fails halfway therefore leaves a chunk that is half generated,
 * published and reported as loaded, and the caller of {@code loadChunk} never learns about it. The
 * tests named for a throwing generator would pass on such an implementation only if that behaviour
 * were accepted, so they are the ones which describe the deviation.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.2.0
 * @since 0.3.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoInstanceGeneratorTest {

    /**
     * The height at which every case of this class places its marker blocks.
     */
    private static final int MARKER_Y = 40;

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env    the environment which provides the server process
     * @param loader the loader chunks are read from, null for a loader which loads nothing
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env, @Nullable ChunkLoader loader) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    /**
     * Builds a generator which writes a single block and then reports success.
     *
     * @param x     the block X the generator writes to
     * @param z     the block Z the generator writes to
     * @param block the block the generator writes
     * @return the generator
     */
    private static Generator writing(int x, int z, Block block) {
        return unit -> unit.modifier().setBlock(x, MARKER_Y, z, block);
    }

    @Test
    void testTheInstanceHandsBackTheGeneratorItWasGiven(Env env) {
        final FalcoInstance instance = registered(env, null);
        final Generator generator = writing(0, 0, Block.STONE);

        instance.setGenerator(generator);

        assertSame(generator, instance.generator());

        instance.setGenerator(null);

        assertNull(instance.generator());
    }

    @Test
    void testAGeneratedChunkCarriesTheBlocksOfTheGenerator(Env env) {
        final FalcoInstance instance = registered(env, null);
        instance.setGenerator(writing(3, 5, Block.DIAMOND_BLOCK));

        final Chunk chunk = instance.loadChunk(0, 0).join();

        assertTrue(chunk.isLoaded());
        assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(3, MARKER_Y, 5));
    }

    @Test
    void testTheGeneratorDoesNotRunWhenTheLoaderProducedTheChunk(Env env) {
        final AtomicInteger runs = new AtomicInteger();
        final FalcoInstance instance = registered(env, new SuppliedLoader());
        instance.setGenerator(unit -> {
            runs.incrementAndGet();
            unit.modifier().setBlock(0, MARKER_Y, 0, Block.DIAMOND_BLOCK);
        });

        instance.loadChunk(0, 0).join();

        assertEquals(0, runs.get());
        assertEquals(Block.AIR, instance.getBlock(0, MARKER_Y, 0));
    }

    @Test
    void testABlockWhichNeedsItsOwnEntrySurvivesGeneration(Env env) {
        final Block chest = Block.CHEST.withNbt(CompoundBinaryTag.builder().putString("falco", "kept").build());
        final FalcoInstance instance = registered(env, null);
        instance.setGenerator(writing(2, 2, chest));

        instance.loadChunk(0, 0).join();

        // The palette of a section only stores a block state, so a block which carries nbt survives
        // only if the separate entry map of the chunk was written as well.
        final Block generated = instance.getBlock(2, MARKER_Y, 2);
        assertNotNull(generated.nbt());
        assertEquals("kept", generated.nbt().getString("falco"));
    }

    @Test
    void testAThrowingGeneratorFailsTheLoadInsteadOfPublishingAHalfChunk(Env env) {
        final FalcoInstance instance = registered(env, null);
        instance.setGenerator(unit -> {
            unit.modifier().setBlock(1, MARKER_Y, 1, Block.DIAMOND_BLOCK);
            throw new IllegalStateException("the generator gave up halfway");
        });

        final CompletionException failure = assertThrows(CompletionException.class,
                () -> instance.loadChunk(0, 0).join());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        // Nothing of the failed chunk may be reachable afterwards. Minestom keeps the chunk, reports
        // the failure to the exception manager and hands the half generated chunk out as if the load
        // had worked, which is the behaviour this case exists to reject.
        assertNull(instance.getChunk(0, 0));
        assertTrue(instance.getChunks().isEmpty());
    }

    @Test
    void testAThrowingGeneratorLeavesAnAlreadyLoadedChunkUntouched(Env env) {
        final FalcoInstance instance = registered(env, null);
        final Chunk chunk = instance.loadChunk(0, 0).join();
        instance.setBlock(4, MARKER_Y, 4, Block.STONE);

        final CompletionException failure = assertThrows(CompletionException.class,
                () -> instance.generateChunk(0, 0, unit -> {
                    unit.modifier().setBlock(5, MARKER_Y, 5, Block.DIAMOND_BLOCK);
                    throw new IllegalStateException("the generator gave up halfway");
                }).join());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertSame(chunk, instance.getChunk(0, 0));
        assertTrue(chunk.isLoaded());
        // The write the generator managed before it failed must not be visible, and the block which
        // was there before it started must still be.
        assertEquals(Block.AIR, instance.getBlock(5, MARKER_Y, 5));
        assertEquals(Block.STONE, instance.getBlock(4, MARKER_Y, 4));
    }

    @Test
    void testGenerateChunkAppliesTheGeneratorToAnAlreadyLoadedChunk(Env env) {
        final FalcoInstance instance = registered(env, null);
        instance.loadChunk(0, 0).join();
        instance.setBlock(6, MARKER_Y, 6, Block.STONE);

        instance.generateChunk(0, 0, writing(7, 7, Block.DIAMOND_BLOCK)).join();

        assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(7, MARKER_Y, 7));
        // A generator which does not touch a position leaves what was there, which is what makes
        // this method usable to add to a world rather than only to build one.
        assertEquals(Block.STONE, instance.getBlock(6, MARKER_Y, 6));
    }

    @Test
    void testAForkIntoAnUnloadedChunkIsAppliedWhenThatChunkIsLoaded(Env env) {
        final FalcoInstance instance = registered(env, null);
        instance.setGenerator(unit -> {
            if (unit.absoluteStart().chunkX() != 0 || unit.absoluteStart().chunkZ() != 0) return;
            unit.fork(setter -> setter.setBlock(new Vec(16, MARKER_Y, 0), Block.GOLD_BLOCK));
        });

        instance.loadChunk(0, 0).join();

        // The fork targets a chunk which is not loaded yet, so it has to be remembered rather than
        // dropped. It becomes visible only once that chunk arrives.
        assertNull(instance.getChunk(1, 0));

        instance.loadChunk(1, 0).join();

        assertEquals(Block.GOLD_BLOCK, instance.getBlock(16, MARKER_Y, 0));
    }

    @Test
    void testAForkIntoALoadedChunkReachesItImmediately(Env env) {
        final FalcoInstance instance = registered(env, null);
        instance.loadChunk(1, 0).join();

        instance.generateChunk(0, 0, unit ->
                unit.fork(setter -> setter.setBlock(new Vec(16, MARKER_Y, 0), Block.GOLD_BLOCK))).join();

        assertEquals(Block.GOLD_BLOCK, instance.getBlock(16, MARKER_Y, 0));
    }

    /**
     * Pins the order of the two halves of a commit, through the one thing that can observe it.
     * <p>
     * A block which needs its own entry is written through {@code Chunk#setBlock}, and that method
     * runs {@code if (needsCompleteHeightmapRefresh) calculateFullHeightmap()} before it refreshes
     * anything. On a chunk that was just generated the flag is true, so the first such block latches
     * both heightmaps — and {@code Heightmap#refresh(int)} sets a private {@code needsRefresh} to
     * false which nothing public can set back, so whatever the chunk knew at that moment is what it
     * keeps. If the specials of a section are written inside the loop that commits the palettes, that
     * moment is halfway through the commit and the heights are computed over a chunk that is missing
     * everything above the section the block happens to sit in.
     * </p>
     * <p>
     * The case is built to make that gap wide and the wrong answer a specific number rather than a
     * smell. Stone fills {@code y = -64..127}, which is the sections of index {@code 0..11}, and the
     * block carrying nbt sits at {@code y = 64}, which is index {@code 8}. Committed in one pass the
     * surface is {@code 127}; committed with the special written from inside the loop it is
     * {@code 79} — the top of the highest section that had been committed when the latch fired — and
     * {@code 79} is what this case reported in both heightmaps before the fix.
     * </p>
     * <p>
     * The height is read rather than the packet, because {@code Heightmap#getHeight} answers from its
     * array without recomputing anything once {@code needsRefresh} is false, which it is in both
     * arms. The assertion therefore reads what the chunk stored and never triggers the refresh it is
     * asserting about.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    void testTheHeightmapsSeeTheWholeChunkAndNotHalfOfIt(Env env) {
        final Block marked = Block.CHEST.withNbt(CompoundBinaryTag.builder().putString("falco", "kept").build());
        final FalcoInstance instance = registered(env, null);
        instance.setGenerator(unit -> {
            unit.modifier().fill(new Vec(0, -64, 0), new Vec(16, 128, 16), Block.STONE);
            unit.modifier().setBlock(0, 64, 0, marked);
        });

        instance.loadChunk(0, 0).join();

        final Chunk chunk = instance.getChunk(0, 0);

        assertNotNull(chunk);
        assertEquals(127, chunk.worldSurfaceHeightmap().getHeight(1, 1),
                "the stone reaches y=127, and a heightmap latched halfway through the commit reports "
                        + "the top of the section the special block sits in instead");
        assertEquals(127, chunk.motionBlockingHeightmap().getHeight(1, 1),
                "both heightmaps are latched by the same call, so both are wrong together");
    }

    /**
     * Pins that both ways into the generator refresh the block change timestamp.
     * <p>
     * The refresh used to sit at the end of the commit itself, where one line covered both entry
     * points. It now sits at the two call sites, because the timestamp belongs to the instance and
     * the commit belongs to {@link ChunkGeneration}. Nothing observed that line before, so a move
     * which dropped it at one of the two would have been green everywhere.
     * </p>
     *
     * @param env the environment which provides the server process
     */
    @Test
    void testBothWaysIntoTheGeneratorMoveTheBlockChangeTime(Env env) {
        final FalcoInstance instance = registered(env, null);
        instance.setGenerator(writing(3, 5, Block.STONE));
        final long beforeLoad = instance.getLastBlockChangeTime();

        instance.loadChunk(0, 0).join();

        assertNotEquals(beforeLoad, instance.getLastBlockChangeTime(),
                "a chunk which came out of the generator on its load carries blocks that were not there");

        final long beforeGenerate = instance.getLastBlockChangeTime();

        // The chunk is already loaded, so this is the second entry point and not the first one again.
        instance.generateChunk(0, 0, writing(6, 7, Block.DIAMOND_BLOCK)).join();

        assertNotEquals(beforeGenerate, instance.getLastBlockChangeTime(),
                "a generator run over a chunk which is already loaded changes blocks just the same");
    }

    @Test
    void testAChunkStaysEmptyWithoutAGenerator(Env env) {
        final FalcoInstance instance = registered(env, null);

        instance.loadChunk(0, 0).join();

        assertNull(instance.generator());
        assertEquals(Block.AIR, instance.getBlock(0, MARKER_Y, 0));
    }

    /**
     * A loader which answers every request with an empty chunk of the instance.
     * <p>
     * Used to prove that the generator is skipped when the loader already knows the chunk, which is
     * the contract {@code ChunkLoader#loadChunk} states.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.3.0
     */
    private static final class SuppliedLoader implements ChunkLoader {

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
        }

        @Override
        public void saveChunk(Chunk chunk) {
            // Nothing is written anywhere; this loader only exists to answer a load.
        }
    }
}
