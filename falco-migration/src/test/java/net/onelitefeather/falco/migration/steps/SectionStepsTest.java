package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.onelitefeather.falco.anvil.BitPacker;
import net.onelitefeather.falco.migration.ChunkMigration;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the three section-facing steps this task adds: {@link NormaliseBitPacking}, which
 * re-packs a pre-1.16 section's boundary-spanning block data into the long-aligned layout
 * {@code BitPacker} can read; {@link RebuildBiomes}, which turns a whole-chunk biome array into a
 * palettised container per section; and {@link TranslateBlockStates}, which walks every section's
 * palette through {@link net.onelitefeather.falco.migration.BlockStateRules#translate}.
 */
class SectionStepsTest {

    private static final MigrationContext ANY_CONTEXT = new MigrationContext(1519, 4790);

    // --- NormaliseBitPacking -------------------------------------------------------------------

    @Test
    void testASectionsSpanningBlockStatesAreReadableWithBitPackerAfterNormalising() {
        // A palette of 17 distinct entries forces 5 bits per entry (BitPacker.bitsPerEntry(17, 4)):
        // 5 does not divide 64, so the legacy (pre-1.16) packing this fixture builds by hand
        // genuinely lets entries span a long boundary, exactly the case this step exists for.
        int bitsPerEntry = 5;
        int[] values = new int[16 * 16 * 16];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 17;
        }
        long[] legacyPacked = legacyPack(values, bitsPerEntry);

        ListBinaryTag palette = ListBinaryTag.empty();
        for (int i = 0; i < 17; i++) {
            palette = palette.add(CompoundBinaryTag.builder().putString("Name", "minecraft:test_" + i).build());
        }

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", ListBinaryTag.from(List.of(CompoundBinaryTag.builder()
                                .putByte("Y", (byte) 0)
                                .put("Palette", palette)
                                .putLongArray("BlockStates", legacyPacked)
                                .build())))
                        .build())
                .build();

        CompoundBinaryTag normalised = new NormaliseBitPacking().apply(chunk, ANY_CONTEXT);

        long[] repacked = normalised.getCompound("Level").getList("Sections").getCompound(0).getLongArray("BlockStates");
        int[] roundTripped = BitPacker.unpack(repacked, values.length, bitsPerEntry);
        assertArrayEquals(values, roundTripped,
                "BitPacker must read back exactly what the legacy, spanning layout held");
    }

    @Test
    void testASingleValueSectionWithNoBlockStatesArrayIsLeftAlone() {
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 0)
                .put("Palette", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putString("Name", "minecraft:air").build())))
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", ListBinaryTag.from(List.of(section)))
                        .build())
                .build();

        CompoundBinaryTag normalised = new NormaliseBitPacking().apply(chunk, ANY_CONTEXT);

        assertEquals(section, normalised.getCompound("Level").getList("Sections").getCompound(0));
    }

    // --- RebuildBiomes ---------------------------------------------------------------------------

    @Test
    void testAWidenedTwentyFourEntryBiomeArrayBecomesAUniformPaletteForItsSection() {
        int[] biomes = new int[1024];
        java.util.Arrays.fill(biomes, 0, 64, 1); // section Y=0: every cell is id 1 (plains)

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putIntArray("Biomes", biomes)
                .put("sections", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putInt("Y", 0).build())))
                .build();

        CompoundBinaryTag rebuilt = new RebuildBiomes().apply(chunk, ANY_CONTEXT);

        assertNull(rebuilt.get("Biomes"), "the whole-chunk array is consumed, not kept alongside the new containers");
        CompoundBinaryTag section = rebuilt.getList("sections").getCompound(0);
        CompoundBinaryTag biomesContainer = section.getCompound("biomes");
        assertEquals(1, biomesContainer.getList("palette").size());
        assertEquals("minecraft:plains", biomesContainer.getList("palette").getString(0));
        assertNull(biomesContainer.get("data"), "a single-entry palette carries no packed data");
    }

    @Test
    void testAPreFifteenTwoHundredFiftySixEntryArrayIsSampledAtEachQuadrantsCentreAndRepeatedByHeight() {
        // Mirrors PaperMC/DataConverter's V2202 (DataVersion 2203) exactly: for XZ quadrant (i, j)
        // the sampled column is old index ((i*4+2) << 4) | (j*4+2). Placing legacy biome id i*4+j at
        // exactly those 16 centre columns, and an id that must never be sampled everywhere else,
        // means the resulting section palette must be built purely from the centre samples in
        // exactly that (i, j) order, proving the quadrant math rather than an average or a different
        // sampling point. Ids 0-15 are themselves real, sourced entries of this step's own legacy
        // biome table (ocean through mushroom_field_shore), so the expected names below are not
        // invented for the test.
        int[] legacy = new int[256];
        java.util.Arrays.fill(legacy, 999); // never assigned in this step's table; must not be sampled
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int l = (i << 2) + 2;
                int k = (j << 2) + 2;
                legacy[(l << 4) | k] = i * 4 + j; // 16 distinct sampled ids, 0..15, in scan order
            }
        }
        List<String> expectedPalette = List.of(
                "minecraft:ocean", "minecraft:plains", "minecraft:desert", "minecraft:mountains",
                "minecraft:forest", "minecraft:taiga", "minecraft:swamp", "minecraft:river",
                "minecraft:nether_wastes", "minecraft:the_end", "minecraft:frozen_ocean", "minecraft:frozen_river",
                "minecraft:snowy_tundra", "minecraft:snowy_mountains", "minecraft:mushroom_fields",
                "minecraft:mushroom_field_shore");

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putIntArray("Biomes", legacy)
                .put("sections", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putInt("Y", 0).build(),
                        CompoundBinaryTag.builder().putInt("Y", 5).build())))
                .build();

        CompoundBinaryTag rebuilt = new RebuildBiomes().apply(chunk, ANY_CONTEXT);

        for (int sectionIndex = 0; sectionIndex < 2; sectionIndex++) {
            CompoundBinaryTag biomes = rebuilt.getList("sections").getCompound(sectionIndex).getCompound("biomes");
            ListBinaryTag palette = biomes.getList("palette");
            assertEquals(expectedPalette.size(), palette.size(),
                    "every one of the 16 sampled ids, and only the sampled ids, must appear in every Y-layer, "
                            + "because a pre-1.15 array has no variance by height");
            for (int p = 0; p < palette.size(); p++) {
                assertEquals(expectedPalette.get(p), palette.getString(p),
                        "the centre-sample scan order (i, j) fixes the palette's own order");
            }

            // The 64 cells of each section cycle through local indices 0..15 four times over (64 /
            // 16 = 4), because the widened 4x4 layer is repeated across every Y-group unchanged.
            long[] packed = biomes.getLongArray("data");
            int bitsPerEntry = BitPacker.bitsPerEntry(palette.size(), 1);
            int[] indices = BitPacker.unpack(packed, 64, bitsPerEntry);
            for (int cell = 0; cell < 64; cell++) {
                assertEquals(cell % 16, indices[cell], "cell " + cell + " of section " + sectionIndex);
            }
        }
    }

    @Test
    void testAnUnknownLegacyBiomeIdFailsRatherThanInventingAName() {
        int[] biomes = new int[1024];
        java.util.Arrays.fill(biomes, 0, 64, 253); // never assigned by PaperMC/DataConverter's V2832

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putIntArray("Biomes", biomes)
                .put("sections", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putInt("Y", 0).build())))
                .build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> new RebuildBiomes().apply(chunk, ANY_CONTEXT));
        assertTrue(exception.getMessage().contains("253"));
    }

    // --- TranslateBlockStates, including the full-chain Gegenprobe target -----------------------

    @Test
    void testALegacyTopLevelPaletteBecomesAModernBlockStatesContainer() {
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("Palette", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putString("Name", "minecraft:stone_slab").build())))
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("sections", ListBinaryTag.from(List.of(section)))
                .build();

        CompoundBinaryTag translated = new TranslateBlockStates().apply(chunk, new MigrationContext(1519, 4790));

        CompoundBinaryTag translatedSection = translated.getList("sections").getCompound(0);
        assertNull(translatedSection.get("Palette"), "the legacy container key must not survive");
        assertNull(translatedSection.get("BlockStates"));
        CompoundBinaryTag blockStates = translatedSection.getCompound("block_states");
        assertEquals("minecraft:smooth_stone_slab", blockStates.getList("palette").getCompound(0).getString("Name"),
                "stone_slab renamed below DataVersion 1901, same as BlockStateRulesTest pins directly");
    }

    /**
     * This is the case the brief and the design both single out: a 1.13 {@code cobblestone_wall}
     * with {@code north=true} must come out of the <em>whole</em> {@link ChunkMigration} chain as
     * {@code north=low} — not just out of {@link TranslateBlockStates} in isolation. Loading a wall
     * whose direction property is still the pre-1.16 boolean is exactly the case that aborts a chunk
     * load; this test is the proof the rules and the chain are actually wired together.
     */
    @Test
    void testACobblestoneWallWithNorthTrueSurvivesTheWholeChainAsNorthLow() {
        CompoundBinaryTag wallSection = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 0)
                .put("Palette", ListBinaryTag.from(List.of(CompoundBinaryTag.builder()
                        .putString("Name", "minecraft:cobblestone_wall")
                        .put("Properties", CompoundBinaryTag.builder()
                                .putString("north", "true")
                                .putString("south", "false")
                                .putString("up", "true")
                                .build())
                        .build())))
                .build();

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1519)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .putString("Status", "full")
                        .put("Sections", ListBinaryTag.from(List.of(wallSection)))
                        .build())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(chunk, 4790);

        CompoundBinaryTag properties = migrated.getList("sections").getCompound(0)
                .getCompound("block_states").getList("palette").getCompound(0).getCompound("Properties");
        assertEquals("low", properties.getString("north"));
        assertEquals("none", properties.getString("south"));
        assertEquals("true", properties.getString("up"), "up is not one of the four rewritten sides");
    }

    /**
     * Packs {@code values} using the pre-1.16 layout, in which an entry is allowed to span a long
     * boundary — the exact inverse of {@link net.onelitefeather.falco.migration.LegacyBitReader}'s
     * own unpacking, built independently here (rather than reused) so this fixture does not simply
     * assert that the production code agrees with itself.
     */
    private static long[] legacyPack(int[] values, int bitsPerEntry) {
        long totalBits = (long) values.length * bitsPerEntry;
        long[] packed = new long[(int) ((totalBits + 63) / 64)];
        long mask = (1L << bitsPerEntry) - 1L;

        for (int index = 0; index < values.length; index++) {
            long bitOffset = (long) index * bitsPerEntry;
            int longIndex = (int) (bitOffset / 64);
            int bitInLong = (int) (bitOffset % 64);
            long value = values[index] & mask;

            packed[longIndex] |= value << bitInLong;
            int bitsWrittenInFirstLong = 64 - bitInLong;
            if (bitsWrittenInFirstLong < bitsPerEntry) {
                packed[longIndex + 1] |= value >>> bitsWrittenInFirstLong;
            }
        }
        return packed;
    }
}
