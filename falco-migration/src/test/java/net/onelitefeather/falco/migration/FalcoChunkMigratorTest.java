package net.onelitefeather.falco.migration;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.onelitefeather.falco.anvil.ChunkDataException;
import net.onelitefeather.falco.anvil.ChunkMigrator;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the adapter that offers {@link ChunkMigration} to the loader: which versions it accepts,
 * how it translates the engine's unchecked failure into the loader's checked one, and that it is
 * actually registered as a service.
 */
class FalcoChunkMigratorTest {

    private static final int TARGET = 4000;

    private final FalcoChunkMigrator migrator = new FalcoChunkMigrator();

    @Test
    void testAChunkBelowTheFlatteningIsDeclined() {
        assertFalse(migrator.canMigrate(ChunkMigration.MINIMUM_SOURCE_VERSION - 1, TARGET),
                "below 1.13 a chunk holds numeric block ids this engine does not speak");
        assertTrue(migrator.canMigrate(ChunkMigration.MINIMUM_SOURCE_VERSION, TARGET),
                "the floor itself is supported");
    }

    @Test
    void testAChunkNotOlderThanTheTargetIsDeclined() {
        assertFalse(migrator.canMigrate(TARGET, TARGET));
        assertFalse(migrator.canMigrate(TARGET + 1, TARGET),
                "a chunk newer than the server is not something to upgrade");
        assertTrue(migrator.canMigrate(TARGET - 1, TARGET));
    }

    @Test
    void testTheEngineHasNoCeiling() {
        // The engine's floor is a real limit; its lack of a ceiling is what lets a 1.20 world be
        // lifted to a current server at all, which is the case the loader option was built for.
        assertTrue(migrator.canMigrate(3465, 4790), "a 1.20.1 chunk has to be accepted");
    }

    @Test
    void testAMigratedChunkCarriesTheTargetVersion() throws Exception {
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2566)
                .putString("Status", "minecraft:full")
                .put("sections", ListBinaryTag.empty())
                .build();

        CompoundBinaryTag migrated = migrator.migrate(chunk, TARGET);

        assertNotNull(migrated);
        assertEquals(TARGET, migrated.getInt("DataVersion"));
    }

    @Test
    void testAnEngineFailureArrivesAsAChunkDataExceptionThatKeepsItsCause() {
        // Below the floor the engine throws MigrationException, which is unchecked and belongs to
        // this module. Letting it cross into the loader unchanged would land it in the loader's
        // generic RuntimeException handler and be reported as an unspecified defect rather than as
        // this chunk's data being unconvertible.
        CompoundBinaryTag tooOld = CompoundBinaryTag.builder()
                .putInt("DataVersion", ChunkMigration.MINIMUM_SOURCE_VERSION - 1)
                .put("sections", ListBinaryTag.empty())
                .build();

        ChunkDataException thrown = assertThrows(ChunkDataException.class, () -> migrator.migrate(tooOld, TARGET));

        assertEquals(ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION, thrown.reason());
        assertInstanceOf(MigrationException.class, thrown.getCause());
    }

    @Test
    void testTheAdapterIsRegisteredAsAService() {
        // Without the META-INF/services entry a caller who selects a migration mode gets a loader
        // that refuses to build, and the failure would point at the classpath rather than at the
        // missing resource in this module.
        boolean registered = ServiceLoader.load(ChunkMigrator.class, ChunkMigrator.class.getClassLoader())
                .stream()
                .anyMatch(provider -> provider.type() == FalcoChunkMigrator.class);

        assertTrue(registered, "FalcoChunkMigrator has to be registered for ChunkMigrator");
    }
}
