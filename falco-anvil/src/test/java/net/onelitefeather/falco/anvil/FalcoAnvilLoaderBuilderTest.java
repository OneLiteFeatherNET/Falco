package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins down the builder of the loader.
 * <p>
 * The builder exists to reach the values the constructors hardcode, so the tests here are about
 * exactly that: that a value given to a slot arrives, that a slot rejects a value the loader would
 * otherwise swallow silently, and that two loaders from one builder stay independent of one another
 * where sharing would be surprising.
 * </p>
 * <p>
 * The checks in the slots are the point of several of these tests. A compression level outside the
 * supported range does not fail during construction but leaves every single chunk unsaved, because
 * {@code saveChunk} catches the exception and swallows it with a log line; the slot therefore has to
 * refuse the value at the moment it is given.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
class FalcoAnvilLoaderBuilderTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    @TempDir
    private Path worldRoot;

    @Test
    void testTheBuilderResolvesTheSameRegionDirectoryAsTheConstructor() throws IOException {
        try (FalcoAnvilLoader constructed = new FalcoAnvilLoader(this.worldRoot, OVERWORLD);
             FalcoAnvilLoader built = FalcoAnvilLoader.builder().build(this.worldRoot, OVERWORLD)) {

            assertEquals(constructed.regionDirectory(), built.regionDirectory());
            assertEquals(constructed.legacyLayout(), built.legacyLayout());
        }
    }

    @Test
    void testABuiltLoaderWritesAChunkTheConstructedOneReadsBack(Env env) throws IOException {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(1, 41, 2, Block.GOLD_BLOCK);
        } finally {
            chunk.unlockWriteLock();
        }

        try (FalcoAnvilLoader writer = FalcoAnvilLoader.builder().build(this.worldRoot, OVERWORLD)) {
            writer.saveChunk(chunk);
        }

        try (FalcoAnvilLoader reader = new FalcoAnvilLoader(this.worldRoot, OVERWORLD)) {
            Chunk read = reader.loadChunk(instance, 0, 0);

            assertNotNull(read, "the built loader wrote into the directory the constructor resolves");

            read.lockReadLock();
            try {
                assertEquals(Block.GOLD_BLOCK, read.getBlock(1, 41, 2));
            } finally {
                read.unlockReadLock();
            }
        }
    }

    @Test
    void testTheBuilderRejectsANonPositiveOpenRegionLimit() {
        FalcoAnvilLoader.Builder builder = FalcoAnvilLoader.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.openRegionLimit(0));
        assertThrows(IllegalArgumentException.class, () -> builder.openRegionLimit(-1));
    }

    @Test
    void testTheBuilderRejectsACompressionLevelOutsideTheSupportedRange() {
        FalcoAnvilLoader.Builder builder = FalcoAnvilLoader.builder();

        assertThrows(IllegalArgumentException.class,
                () -> builder.compressionLevel(ChunkCompression.FASTEST_LEVEL - 1));
        assertThrows(IllegalArgumentException.class,
                () -> builder.compressionLevel(ChunkCompression.SMALLEST_LEVEL + 1));
    }

    @Test
    void testTheBuilderRejectsASaveParallelismBelowOne() {
        FalcoAnvilLoader.Builder builder = FalcoAnvilLoader.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.saveParallelism(0));
    }

    @Test
    void testEveryBuildGetsItsOwnDiagnostics() throws IOException {
        FalcoAnvilLoader.Builder builder = FalcoAnvilLoader.builder();

        try (FalcoAnvilLoader first = builder.build(this.worldRoot, OVERWORLD);
             FalcoAnvilLoader second = builder.build(this.worldRoot, OVERWORLD)) {

            assertNotSame(first.diagnostics(), second.diagnostics(),
                    "two loaders sharing counters would also share the throttles of their warnings");
        }
    }

    @Test
    void testAGivenDiagnosticsInstanceIsTheOneTheLoaderUses() throws IOException {
        AnvilDiagnostics shared = new AnvilDiagnostics();

        try (FalcoAnvilLoader loader =
                     FalcoAnvilLoader.builder().diagnostics(shared).build(this.worldRoot, OVERWORLD)) {

            assertSame(shared, loader.diagnostics());
        }
    }

    /**
     * A resolver given to the builder is the one the loader decodes with.
     * <p>
     * Proven through a resolver that refuses to work: if the loader used its own, the chunk would
     * decode and no exception would arrive. The failure is routed into a sink of this test rather
     * than left to the default, because the default reaches Minestom's exception manager and the
     * test environment rightly fails a test whose server reported an exception.
     * </p>
     */
    @Test
    void testAGivenBlockResolverIsTheOneTheLoaderDecodesWith(Env env) throws IOException {
        Instance instance = env.createEmptyInstance();
        saveOneChunk(env, instance);

        try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
                .blockResolver(new RefusingResolver())
                .exceptionHandler(ignored -> {
                })
                .build(this.worldRoot, OVERWORLD)) {

            assertThrows(AnvilChunkException.class, () -> loader.loadChunk(instance, 0, 0));
        }
    }

    /**
     * A configured exception handler receives what would otherwise go to the server's manager.
     * <p>
     * The slot has a reason beyond metrics: {@code MinecraftServer.getExceptionManager()} reads a
     * field only {@code MinecraftServer.init()} sets, so a loader used by a tool without a server
     * process dies in the error path on a null pointer that hides the actual cause.
     * </p>
     */
    @Test
    void testAConfiguredExceptionHandlerReceivesALoadFailure(Env env) throws IOException {
        Instance instance = env.createEmptyInstance();
        saveOneChunk(env, instance);

        List<Throwable> reported = new CopyOnWriteArrayList<>();

        try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
                .blockResolver(new RefusingResolver())
                .exceptionHandler(reported::add)
                .build(this.worldRoot, OVERWORLD)) {

            assertThrows(AnvilChunkException.class, () -> loader.loadChunk(instance, 0, 0));
        }

        assertEquals(1, reported.size(), "the failure reaches the configured sink");
    }

    /**
     * Writes one chunk into the world directory so a later load has something to fail on.
     *
     * @param env      the test environment
     * @param instance the instance the chunk belongs to
     * @throws IOException if the loader cannot be closed
     */
    private void saveOneChunk(Env env, Instance instance) throws IOException {
        Chunk chunk = instance.loadChunk(0, 0).join();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(1, 41, 2, Block.GOLD_BLOCK);
        } finally {
            chunk.unlockWriteLock();
        }

        try (FalcoAnvilLoader writer = new FalcoAnvilLoader(this.worldRoot, OVERWORLD)) {
            writer.saveChunk(chunk);
        }
    }

    /**
     * A resolver which fails on every entry, so a decode cannot succeed.
     */
    private static final class RefusingResolver implements PaletteEntryResolver {

        @Override
        public int toId(String name, @Nullable CompoundBinaryTag properties) {
            throw new IllegalStateException("this resolver refuses to resolve " + name);
        }

        @Override
        public CompoundBinaryTag toEntry(int id) {
            throw new IllegalStateException("this resolver refuses to describe " + id);
        }
    }

    @Test
    void testTheBuilderCanBeReusedAfterASlotChanged() throws IOException {
        FalcoAnvilLoader.Builder builder = FalcoAnvilLoader.builder().openRegionLimit(8);

        try (FalcoAnvilLoader first = builder.build(this.worldRoot, OVERWORLD)) {
            assertEquals(0, first.openRegionCount());
        }

        try (FalcoAnvilLoader second = builder.openRegionLimit(16).build(this.worldRoot, OVERWORLD)) {
            assertEquals(0, second.openRegionCount());
        }
    }

    /**
     * A slot returns a new builder and leaves the one it was called on alone.
     * <p>
     * All three builders of the project behave this way. A mixture would be a trap: the same line
     * written against two of them would mean two different things, and the one that silently does
     * nothing is the one nobody notices.
     * </p>
     */
    @Test
    void testASlotLeavesTheBuilderItWasCalledOnUnchanged() throws IOException {
        AnvilDiagnostics mine = new AnvilDiagnostics();
        FalcoAnvilLoader.Builder base = FalcoAnvilLoader.builder();
        FalcoAnvilLoader.Builder derived = base.diagnostics(mine);

        assertNotSame(base, derived, "a slot returns a new builder");

        try (FalcoAnvilLoader fromBase = base.build(this.worldRoot, OVERWORLD);
             FalcoAnvilLoader fromDerived = derived.build(this.worldRoot, OVERWORLD)) {

            assertNotSame(mine, fromBase.diagnostics(), "the origin never learned about the value");
            assertSame(mine, fromDerived.diagnostics());
        }
    }
}
