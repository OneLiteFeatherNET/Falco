package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.onelitefeather.falco.migration.ChunkMigration;
import net.onelitefeather.falco.migration.MigrationContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins down the two block-entity-facing steps this task adds: {@link TranslateBlockEntities}, which
 * renames only a block entity's {@code id}, and {@link CountEntities}, which counts the entities a
 * pre-1.17 chunk still carries in its own {@code Entities} list without moving them.
 */
class BlockEntityStepTest {

    @Test
    void testABlockEntityWithNoVerifiedRenameKeepsItsIdAndPositionUnchanged() {
        // This task's research (see the report) found no verified block-entity id rename anywhere in
        // the 1.13-26.1 span, so every real id - a chest included - is expected to pass through
        // unchanged. This is the honest replacement for a "gets renamed" fixture the brief's own
        // example assumed but which the research did not confirm.
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 3700)
                .put("block_entities", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder()
                                .putString("id", "minecraft:chest")
                                .putInt("x", 5)
                                .putInt("y", 64)
                                .putInt("z", -12)
                                .build())))
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(chunk, 4790);

        ListBinaryTag blockEntities = (ListBinaryTag) migrated.get("block_entities");
        CompoundBinaryTag blockEntity = (CompoundBinaryTag) blockEntities.iterator().next();
        assertEquals("minecraft:chest", blockEntity.getString("id"));
        assertEquals(5, blockEntity.getInt("x"));
        assertEquals(64, blockEntity.getInt("y"));
        assertEquals(-12, blockEntity.getInt("z"));
    }

    @Test
    void testALegacyTileEntitiesListEndsUpUnderBlockEntitiesBecauseUnfoldLevelRenamesTheContainerToo() {
        // UnfoldLevel renames TileEntities to block_entities in the same move that renames
        // Sections to sections, both landing in the snapshot that removed Level in the first
        // place (21w43a, DataVersion 2844) - see UnfoldLevel's own Javadoc for the source.
        // TranslateBlockEntities' own TileEntities fallback is therefore never actually reached
        // for a chunk that goes through this chain; this pins the fixed, end-to-end behaviour.
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2566)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 1)
                        .putInt("zPos", 1)
                        .put("Sections", ListBinaryTag.empty())
                        .put("TileEntities", ListBinaryTag.from(List.of(
                                CompoundBinaryTag.builder().putString("id", "minecraft:furnace").build())))
                        .build())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(chunk, 4790);

        assertNull(migrated.get("TileEntities"), "the legacy container key must not survive");
        ListBinaryTag blockEntities = assertInstanceOf(ListBinaryTag.class, migrated.get("block_entities"),
                "UnfoldLevel renames TileEntities to block_entities alongside Sections -> sections");
        CompoundBinaryTag blockEntity = (CompoundBinaryTag) blockEntities.iterator().next();
        assertEquals("minecraft:furnace", blockEntity.getString("id"));
    }

    @Test
    void testTheSubstitutionMechanismWouldRenameAnIdIfAVerifiedRuleExisted() {
        // A synthetic table, not a claim about real Minecraft data: this only proves
        // TranslateBlockEntities.translate's lookup wiring works, independent of the fact that
        // today's verified table (RENAMES) is empty.
        Map<String, String> synthetic = Map.of("minecraft:old_name", "minecraft:new_name");

        assertEquals("minecraft:new_name", TranslateBlockEntities.translate("minecraft:old_name", synthetic));
        assertEquals("minecraft:untouched", TranslateBlockEntities.translate("minecraft:untouched", synthetic));
    }

    @Test
    void testTheEntitiesLeftInTheChunkAreCountedRatherThanMoved() {
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1519)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 1519)
                        .putInt("zPos", 4790)
                        .put("Sections", ListBinaryTag.empty())
                        .put("Entities", ListBinaryTag.from(List.of(
                                CompoundBinaryTag.builder().putString("id", "minecraft:zombie").build(),
                                CompoundBinaryTag.builder().putString("id", "minecraft:item").build())))
                        .build())
                .build();
        MigrationContext context = new MigrationContext(1519, 4790);

        ChunkMigration.migrate(chunk, context);

        assertEquals(2, context.entitiesLeftBehind());
    }

    @Test
    void testAChunkAtOrAfterTheExtractionVersionIsNotCountedBecauseItsEntitiesAlreadyLeftTheChunk() {
        // DataVersion 2681 is 20w45a, the snapshot that actually extracted entities into their own
        // entities/ region files - not 2724, the 1.17 release number the design's step table names.
        // See CountEntities' Javadoc and the task report for the correction and its two sources.
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2681)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .put("Sections", ListBinaryTag.empty())
                        .put("Entities", ListBinaryTag.from(List.of(
                                CompoundBinaryTag.builder().putString("id", "minecraft:zombie").build())))
                        .build())
                .build();
        MigrationContext context = new MigrationContext(2681, 4790);

        ChunkMigration.migrate(chunk, context);

        assertEquals(0, context.entitiesLeftBehind());
    }

    @Test
    void testTheEntityCountAccumulatesAcrossMultipleChunksSharingOneContext() {
        MigrationContext context = new MigrationContext(1519, 4790);
        CompoundBinaryTag chunkWithOneEntity = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1519)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .put("Sections", ListBinaryTag.empty())
                        .put("Entities", ListBinaryTag.from(List.of(
                                CompoundBinaryTag.builder().putString("id", "minecraft:cow").build())))
                        .build())
                .build();
        CompoundBinaryTag chunkWithTwoEntities = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1519)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 1)
                        .putInt("zPos", 0)
                        .put("Sections", ListBinaryTag.empty())
                        .put("Entities", ListBinaryTag.from(List.of(
                                CompoundBinaryTag.builder().putString("id", "minecraft:pig").build(),
                                CompoundBinaryTag.builder().putString("id", "minecraft:sheep").build())))
                        .build())
                .build();

        ChunkMigration.migrate(chunkWithOneEntity, context);
        ChunkMigration.migrate(chunkWithTwoEntities, context);

        assertEquals(3, context.entitiesLeftBehind());
    }
}
