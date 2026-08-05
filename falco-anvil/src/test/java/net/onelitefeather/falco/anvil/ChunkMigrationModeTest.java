package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the three {@link ChunkMigrationMode}s against a real region file and the production loader.
 * <p>
 * The migrator is a test double rather than {@code falco-migration}'s engine on purpose: what is
 * under test here is the loader's half of the contract — when a migrator is consulted, what happens
 * to its result, and what the world on disk looks like afterwards. Whether a particular block rename
 * is correct is the engine's own business and is tested there. {@code MigrationRoundTripTest} covers
 * the two halves together.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 2.2.0
 */
@ExtendWith(MicrotusExtension.class)
class ChunkMigrationModeTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    /**
     * A data version far below {@link FalcoAnvilLoader#DEFAULT_MINIMUM_DATA_VERSION}, so a chunk
     * carrying it is one the version guard refuses unless something raised it first.
     */
    private static final int OLD_VERSION = 1519;

    /**
     * The data version the loaders in this test write, and therefore migrate towards.
     */
    private static final int TARGET_VERSION = 4000;

    @TempDir
    private Path worldRoot;

    /**
     * A migrator that records what it was asked and stamps the target version onto the chunk.
     * <p>
     * Stamping is what makes it a useful double: a chunk that comes out carrying the target version
     * is one the version guard accepts, so a test can tell whether migration ran before or after the
     * guard by whether the chunk loads at all.
     * </p>
     */
    private static final class RecordingMigrator implements ChunkMigrator {

        private final AtomicInteger migrateCalls = new AtomicInteger();
        private final AtomicInteger canMigrateCalls = new AtomicInteger();
        private final boolean accepts;

        private RecordingMigrator(boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        public boolean canMigrate(int sourceVersion, int targetVersion) {
            this.canMigrateCalls.incrementAndGet();
            return this.accepts;
        }

        @Override
        public CompoundBinaryTag migrate(CompoundBinaryTag data, int targetVersion) {
            this.migrateCalls.incrementAndGet();
            return CompoundBinaryTag.builder().put(data).putInt("DataVersion", targetVersion).build();
        }
    }

    /**
     * A migrator that refuses every chunk it is handed.
     */
    private static final class FailingMigrator implements ChunkMigrator {

        @Override
        public boolean canMigrate(int sourceVersion, int targetVersion) {
            return true;
        }

        @Override
        public CompoundBinaryTag migrate(CompoundBinaryTag data, int targetVersion) throws ChunkDataException {
            throw new ChunkDataException(
                    ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION, "refused by the test");
        }
    }

    private FalcoAnvilLoader loader(ChunkMigrationMode mode, ChunkMigrator migrator, AnvilDiagnostics diagnostics) {
        return FalcoAnvilLoader.builder()
                .dataVersion(TARGET_VERSION)
                .diagnostics(diagnostics)
                .migration(mode)
                .chunkMigrator(migrator)
                .exceptionHandler(throwable -> {
                })
                .build(this.worldRoot, OVERWORLD);
    }

    private Path regionDirectory() {
        return this.worldRoot.resolve("dimensions/minecraft/overworld/region");
    }

    private Path regionFile(int chunkX, int chunkZ) {
        return regionDirectory().resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
    }

    /**
     * Writes a chunk that is structurally loadable and carries the given data version.
     */
    private void writeChunk(int chunkX, int chunkZ, int dataVersion) throws Exception {
        CompoundBinaryTag data = CompoundBinaryTag.builder()
                .putInt("DataVersion", dataVersion)
                .putString("Status", "minecraft:full")
                .put("sections", ListBinaryTag.empty())
                .build();

        Files.createDirectories(regionDirectory());
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        BinaryTagIO.writer().writeNamed(Map.entry("", data), target, BinaryTagIO.Compression.NONE);

        try (RegionFile file = RegionFile.open(regionFile(chunkX, chunkZ))) {
            file.writeRaw(chunkX, chunkZ, ChunkCompression.ZLIB,
                    ChunkCompression.ZLIB.compress(target.toByteArray()));
        }
    }

    @Test
    void testTheDefaultModeIsOffAndNeverConsultsAMigrator(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);
        RecordingMigrator migrator = new RecordingMigrator(true);

        try (FalcoAnvilLoader loader = loader(ChunkMigrationMode.OFF, migrator, new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        assertEquals(0, migrator.canMigrateCalls.get());
        assertEquals(0, migrator.migrateCalls.get());
    }

    @Test
    void testInMemoryMigratesTheChunkAndLeavesTheFileUntouched(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);
        byte[] before = Files.readAllBytes(regionFile(0, 0));
        RecordingMigrator migrator = new RecordingMigrator(true);
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        try (FalcoAnvilLoader loader = loader(ChunkMigrationMode.IN_MEMORY, migrator, diagnostics)) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        assertEquals(1, migrator.migrateCalls.get());
        assertEquals(1, diagnostics.chunksMigrated());
        assertEquals(Map.of(Integer.toString(TARGET_VERSION - 1), 1L), diagnostics.migratedSourceVersions());
        assertArrayEquals(before, Files.readAllBytes(regionFile(0, 0)),
                "IN_MEMORY must not write to the world");
    }

    @Test
    void testOnDiskWritesTheMigratedChunkBackSoASecondRunHasNothingToDo(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);
        byte[] before = Files.readAllBytes(regionFile(0, 0));

        RecordingMigrator first = new RecordingMigrator(true);
        try (FalcoAnvilLoader loader = loader(ChunkMigrationMode.ON_DISK, first, new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        assertEquals(1, first.migrateCalls.get());
        assertFalse(Arrays.equals(before, Files.readAllBytes(regionFile(0, 0))),
                "ON_DISK has to rewrite the region file");

        // The whole point of the mode: the stored chunk now carries the target version, so a second
        // run finds nothing to migrate. A test that only checked "the file changed" would still pass
        // if the loader had written something unreadable.
        RecordingMigrator second = new RecordingMigrator(true);
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();
        try (FalcoAnvilLoader loader = loader(ChunkMigrationMode.ON_DISK, second, diagnostics)) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        assertEquals(0, second.migrateCalls.get());
        assertEquals(0, diagnostics.chunksMigrated());
    }

    @Test
    void testOnDiskCopiesTheOriginalBeforeItWrites(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);
        byte[] original = Files.readAllBytes(regionFile(0, 0));

        try (FalcoAnvilLoader loader =
                     loader(ChunkMigrationMode.ON_DISK, new RecordingMigrator(true), new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        Path backup = this.worldRoot
                .resolve(FalcoAnvilLoader.DEFAULT_MIGRATION_BACKUP_DIRECTORY)
                .resolve("overworld")
                .resolve("r.0.0.mca");

        assertTrue(Files.isRegularFile(backup), "the original has to be copied before the first write");
        assertArrayEquals(original, Files.readAllBytes(backup),
                "the backup has to be the untouched original, not the migrated file");
    }

    @Test
    void testTheBackupDirectoryIsOutsideTheRegionDirectory(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);

        try (FalcoAnvilLoader loader =
                     loader(ChunkMigrationMode.ON_DISK, new RecordingMigrator(true), new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        // A backup inside the region directory would be read back as world data by the very loader
        // it was taken to protect, and the world would grow a duplicate of itself.
        try (var entries = Files.list(regionDirectory())) {
            assertEquals(1, entries.filter(path -> path.toString().endsWith(".mca")).count(),
                    "the region directory must hold only the world's own region file");
        }
    }

    @Test
    void testAnExistingBackupIsNotOverwrittenByALaterRun(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);

        Path backupDirectory = this.worldRoot
                .resolve(FalcoAnvilLoader.DEFAULT_MIGRATION_BACKUP_DIRECTORY).resolve("overworld");
        Files.createDirectories(backupDirectory);
        byte[] earlier = "an older run's original".getBytes();
        Files.write(backupDirectory.resolve("r.0.0.mca"), earlier);

        try (FalcoAnvilLoader loader =
                     loader(ChunkMigrationMode.ON_DISK, new RecordingMigrator(true), new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        // The earlier copy is the older original. Replacing it with this run's file would throw away
        // the last untouched copy, because this run's file may already have been migrated.
        assertArrayEquals(earlier, Files.readAllBytes(backupDirectory.resolve("r.0.0.mca")));
    }

    @Test
    void testMigrationRunsBeforeTheVersionGuard(Env env) throws Exception {
        // The chunk is older than the guard's floor, so without migration it is refused. With
        // migration it is raised above the floor first and loads. This is the ordering the whole
        // option depends on: the other way round, every world old enough to need migrating would be
        // rejected before the migrator ever saw it.
        writeChunk(0, 0, OLD_VERSION);

        try (FalcoAnvilLoader refusing = loader(ChunkMigrationMode.OFF, new RecordingMigrator(true), new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(refusing);
            assertThrows(AnvilChunkException.class, () -> refusing.loadChunk(instance, 0, 0));
        }

        RecordingMigrator migrator = new RecordingMigrator(true);
        try (FalcoAnvilLoader migrating = loader(ChunkMigrationMode.IN_MEMORY, migrator, new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(migrating);
            Chunk chunk = migrating.loadChunk(instance, 0, 0);

            assertNotNull(chunk, "a migrated chunk has to pass the guard that refused it unmigrated");
        }
        assertEquals(1, migrator.migrateCalls.get());
    }

    @Test
    void testAChunkAtTheTargetVersionIsNotMigrated(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION);
        RecordingMigrator migrator = new RecordingMigrator(true);

        try (FalcoAnvilLoader loader = loader(ChunkMigrationMode.IN_MEMORY, migrator, new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        assertEquals(0, migrator.canMigrateCalls.get(), "a current chunk must not even be offered");
        assertEquals(0, migrator.migrateCalls.get());
    }

    @Test
    void testAMigratorThatDeclinesLeavesTheChunkUntouched(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);
        byte[] before = Files.readAllBytes(regionFile(0, 0));
        RecordingMigrator migrator = new RecordingMigrator(false);
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        try (FalcoAnvilLoader loader = loader(ChunkMigrationMode.ON_DISK, migrator, diagnostics)) {
            Instance instance = env.createEmptyInstance(loader);
            assertNotNull(loader.loadChunk(instance, 0, 0));
        }

        assertEquals(1, migrator.canMigrateCalls.get());
        assertEquals(0, migrator.migrateCalls.get());
        assertEquals(0, diagnostics.chunksMigrated());
        assertArrayEquals(before, Files.readAllBytes(regionFile(0, 0)),
                "a declined chunk must not be rewritten");
    }

    @Test
    void testAFailingMigratorFailsThatChunkInsteadOfPassingItThrough(Env env) throws Exception {
        writeChunk(0, 0, TARGET_VERSION - 1);

        try (FalcoAnvilLoader loader = loader(ChunkMigrationMode.IN_MEMORY, new FailingMigrator(), new AnvilDiagnostics())) {
            Instance instance = env.createEmptyInstance(loader);

            // Passing the chunk through unmigrated would hand the server exactly the partly
            // unreadable data the mode was switched on to prevent.
            AnvilChunkException thrown =
                    assertThrows(AnvilChunkException.class, () -> loader.loadChunk(instance, 0, 0));
            assertNotNull(thrown.getCause());
        }
    }

    @Test
    void testSelectingAModeDiscoversTheEngineWithoutASecondCall() throws Exception {
        // falco-migration is on this module's test classpath and registers its adapter through
        // META-INF/services, so this asserts the seam end to end: selecting a mode is enough, and a
        // caller does not have to name the migrator as well. The counterpart — a classpath with no
        // migrator at all, which has to refuse rather than migrate nothing — cannot be written here
        // for the same reason, since this classpath always has one. FalcoChunkMigratorTest in
        // falco-migration covers the adapter's own behaviour.
        try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
                .dataVersion(TARGET_VERSION)
                .migration(ChunkMigrationMode.IN_MEMORY)
                .build(this.worldRoot, OVERWORLD)) {

            assertNotNull(loader);
        }
    }
}
