package net.onelitefeather.falco.migration;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the step chain {@link ChunkMigration#migrate(CompoundBinaryTag, int)} runs, and the three
 * steps that only move or delete data: {@code UnfoldLevel}, {@code NamespaceStatus} and
 * {@code DiscardHeightmapsAndLight}.
 */
class ChunkMigrationTest {

    @Test
    void testAPreEighteenChunkGetsItsFieldsOnTheRoot() {
        // "postprocessed", not "full" — a 1.13-era chunk's own terminal status, per NamespaceStatus's
        // own sourced javadoc. Every fixture in this module used to write "full" by hand, a value a
        // pre-1.14 chunk never actually produces, which is exactly how the missing value translation
        // this test now exercises went unnoticed by every other test in the suite.
        CompoundBinaryTag legacy = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2566)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 3)
                        .putInt("zPos", 4)
                        .putString("Status", "postprocessed")
                        .put("Sections", ListBinaryTag.empty())
                        .build())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(legacy, 4790);

        assertNull(migrated.get("Level"));
        assertEquals(3, migrated.getInt("xPos"));
        assertEquals("minecraft:full", migrated.getString("Status"),
                "a 1.13 chunk's own terminal status, postprocessed, must become minecraft:full, not "
                        + "minecraft:postprocessed, or FalcoAnvilLoader silently skips every migrated chunk");
        assertNotNull(migrated.get("sections"));
        // yPos = 0 because SettleYRange computes it from the chunk's own (here: empty) sections
        // list rather than assuming a fixed floor — see that step's javadoc for the sourced reading
        // of what yPos means ("the chunk's own lowest section", not the dimension floor) and why an
        // empty sections list falls back to 0 rather than an arbitrary default.
        assertEquals(0, migrated.getInt("yPos"));
    }

    @Test
    void testALevelFieldThatWouldOverwriteAnExistingRootFieldIsRejectedRatherThanSilentlyOverwritten() {
        CompoundBinaryTag collision = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2566)
                .putInt("xPos", 99)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 3)
                        .put("Sections", ListBinaryTag.empty())
                        .build())
                .build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> ChunkMigration.migrate(collision, 4790));

        assertTrue(exception.getMessage().contains("xPos"),
                "the failure should name the field it refused to silently overwrite");
    }

    @Test
    void testAModernChunkIsLeftAloneExceptForItsVersion() {
        CompoundBinaryTag modern = CompoundBinaryTag.builder()
                .putInt("DataVersion", 3700)
                .putString("Status", "minecraft:full")
                .put("sections", ListBinaryTag.empty())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(modern, 4790);

        assertEquals(4790, migrated.getInt("DataVersion"));
        assertEquals("minecraft:full", migrated.getString("Status"));
    }

    @Test
    void testHeightmapsAndLightAreDroppedRatherThanConverted() {
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2566)
                .put("Level", CompoundBinaryTag.builder()
                        .put("Heightmaps", CompoundBinaryTag.builder()
                                .putLongArray("WORLD_SURFACE", new long[]{1L})
                                .build())
                        .put("Sections", ListBinaryTag.empty())
                        .build())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(chunk, 4790);

        assertNull(migrated.get("Heightmaps"), "a wrongly ported heightmap never announces itself");
    }

    @Test
    void testAChunkBelowTheFloorIsDeclinedRatherThanGuessedAt() {
        CompoundBinaryTag ancient = CompoundBinaryTag.builder().putInt("DataVersion", 1000).build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> ChunkMigration.migrate(ancient, 4790));
        assertTrue(exception.getMessage().contains("1000"),
                "a present, too-old DataVersion must be named in the failure");
    }

    @Test
    void testAChunkWithNoDataVersionAtAllIsDeclinedWithADifferentMessageThanATooOldOne() {
        // CompoundBinaryTag#getInt on a missing key defaults to 0, which is indistinguishable from a
        // chunk that genuinely stamped "DataVersion: 0" unless the missing case is checked before
        // that default is ever read — a real chunk of any age never carries a literal 0.
        CompoundBinaryTag noVersion = CompoundBinaryTag.builder().build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> ChunkMigration.migrate(noVersion, 4790));
        assertFalse(exception.getMessage().contains("0"),
                "a chunk with no DataVersion field at all must not be reported as DataVersion 0, which "
                        + "no real chunk of any age actually stores");
    }
}
