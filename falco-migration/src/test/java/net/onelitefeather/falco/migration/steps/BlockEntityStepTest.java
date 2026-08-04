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
    void testALegacyTileEntitiesListSurvivesUnderItsOwnKeyBecauseNoStepInThisChainRenamesTheContainer() {
        // UnfoldLevel (Task 4) moves every child of Level onto the root without renaming it, except
        // Sections -> sections; TileEntities is therefore still called TileEntities once it reaches
        // this step. TranslateBlockEntities does not rename the container either - only the id inside
        // each entry - so it stays TileEntities all the way through. Documented as a known gap in
        // this class's Javadoc and the task report, not silently patched here.
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

        ListBinaryTag tileEntities = assertInstanceOf(ListBinaryTag.class, migrated.get("TileEntities"),
                "no step in this chain renames the TileEntities container key - see the report");
        CompoundBinaryTag tileEntity = (CompoundBinaryTag) tileEntities.iterator().next();
        assertEquals("minecraft:furnace", tileEntity.getString("id"));
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
