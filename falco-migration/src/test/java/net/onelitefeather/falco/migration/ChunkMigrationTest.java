package net.onelitefeather.falco.migration;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        CompoundBinaryTag legacy = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2566)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 3)
                        .putInt("zPos", 4)
                        .putString("Status", "full")
                        .put("Sections", ListBinaryTag.empty())
                        .build())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(legacy, 4790);

        assertNull(migrated.get("Level"));
        assertEquals(3, migrated.getInt("xPos"));
        assertEquals("minecraft:full", migrated.getString("Status"));
        assertNotNull(migrated.get("sections"));
        // Pins today's value, not a claim that it is the right one: yPos = 0 is UnfoldLevel's
        // provisional stand-in until Task 5's SettleYRange decides what yPos means for the target
        // version (see UnfoldLevel's javadoc). This assertion exists so that change is visible as a
        // deliberate edit to this test rather than slipping past unnoticed.
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

        assertThrows(MigrationException.class, () -> ChunkMigration.migrate(ancient, 4790));
    }
}
