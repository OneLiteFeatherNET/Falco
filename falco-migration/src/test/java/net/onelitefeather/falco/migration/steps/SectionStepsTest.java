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
    private static final int BLOCK_ENTRIES = 16 * 16 * 16;

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
    void testAnOverWidthPackedPaletteIsReadAtTheWidthItWasActuallyPackedWithButWrittenBackCanonically() {
        // 17 entries need only 5 bits (BitPacker.bitsPerEntry(17, 4)), but the format explicitly
        // allows a writer to use more. This fixture deliberately packs with 6 to prove that READING
        // still derives the width from the array's own length rather than assumed from the palette
        // size, which would silently misread 6-bit data as 5-bit and corrupt every block in the
        // section without throwing. What comes back out, though, is re-packed at the 5-bit canonical
        // width, not the 6 bits the input used — see this step's own javadoc for why preserving an
        // over-provisioned width is not safe for TranslateBlockStates to read back later.
        int bitsPerEntry = 6;
        int[] values = new int[16 * 16 * 16];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 17;
        }
        long[] overWidthPacked = legacyPack(values, bitsPerEntry);

        ListBinaryTag palette = ListBinaryTag.empty();
        for (int i = 0; i < 17; i++) {
            palette = palette.add(CompoundBinaryTag.builder().putString("Name", "minecraft:test_" + i).build());
        }

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", ListBinaryTag.from(List.of(CompoundBinaryTag.builder()
                                .putByte("Y", (byte) 0)
                                .put("Palette", palette)
                                .putLongArray("BlockStates", overWidthPacked)
                                .build())))
                        .build())
                .build();

        CompoundBinaryTag normalised = new NormaliseBitPacking().apply(chunk, ANY_CONTEXT);

        long[] repacked = normalised.getCompound("Level").getList("Sections").getCompound(0).getLongArray("BlockStates");
        int canonicalBitsPerEntry = BitPacker.bitsPerEntry(17, 4);
        assertEquals(5, canonicalBitsPerEntry, "sanity check on the fixture's own arithmetic");
        int[] roundTripped = BitPacker.unpack(repacked, values.length, canonicalBitsPerEntry);
        assertArrayEquals(values, roundTripped,
                "the values read at the 6-bit width the writer actually used must survive, but the "
                        + "output must be written at the 5-bit canonical width, not 6");
    }

    /**
     * The measured failure from the final review: a palette of 2000 entries needs 11 bits
     * ({@code BitPacker.bitsPerEntry(2000, 4)}), but a writer packing at 12 bits (still a legal,
     * over-provisioned choice) produces an array of the same length —
     * {@code BitPacker.expectedLongCount(4096, 11) == BitPacker.expectedLongCount(4096, 12) == 820} —
     * because both widths fit exactly 5 entries per 64-bit long. A step that preserved the 12-bit
     * read width verbatim left {@link TranslateBlockStates}'s own read-side heuristic no way to tell
     * the two apart from the array's length and the (untranslated) palette size alone, and it always
     * resolves the ambiguity to the smaller width — silently misreading 3274 of 4096 blocks in the
     * section the reviewer actually measured this against. Canonicalising to the palette's own
     * 11-bit minimum on the way out removes the ambiguity outright: there is no longer a 12-bit
     * array for the heuristic to guess wrong about.
     */
    @Test
    void testAnOverWidthPackedSectionIsCanonicalisedRatherThanPreservingTheAmbiguousWidth() {
        int paletteSize = 2000;
        int readBitsPerEntry = 12;
        int canonicalBitsPerEntry = BitPacker.bitsPerEntry(paletteSize, 4);
        assertEquals(11, canonicalBitsPerEntry, "sanity check on the fixture's own arithmetic");
        assertEquals(BitPacker.expectedLongCount(BLOCK_ENTRIES, readBitsPerEntry),
                BitPacker.expectedLongCount(BLOCK_ENTRIES, canonicalBitsPerEntry),
                "sanity check: 11 and 12 bits must actually collide on long count for this to be the "
                        + "case the review measured");

        int[] values = new int[BLOCK_ENTRIES];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % paletteSize;
        }
        long[] overWidthPacked = legacyPack(values, readBitsPerEntry);

        ListBinaryTag palette = ListBinaryTag.empty();
        for (int i = 0; i < paletteSize; i++) {
            palette = palette.add(CompoundBinaryTag.builder().putString("Name", "minecraft:test_" + i).build());
        }

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", ListBinaryTag.from(List.of(CompoundBinaryTag.builder()
                                .putByte("Y", (byte) 0)
                                .put("Palette", palette)
                                .putLongArray("BlockStates", overWidthPacked)
                                .build())))
                        .build())
                .build();

        CompoundBinaryTag normalised = new NormaliseBitPacking().apply(chunk, ANY_CONTEXT);

        long[] repacked = normalised.getCompound("Level").getList("Sections").getCompound(0).getLongArray("BlockStates");
        assertEquals(BitPacker.expectedLongCount(BLOCK_ENTRIES, canonicalBitsPerEntry), repacked.length,
                "the output must be written at the unambiguous 11-bit width, so its own length no "
                        + "longer collides with 12 bits' length for a downstream reader");
        int[] roundTripped = BitPacker.unpack(repacked, values.length, canonicalBitsPerEntry);
        assertArrayEquals(values, roundTripped,
                "every one of the 4096 values read at 12 bits must survive being written back at 11");
    }

    @Test
    void testASectionWithBlockStatesButNoPaletteFailsRatherThanGuessingTheWidth() {
        long[] packed = new long[820]; // a plausible length; the point is the missing Palette
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 0)
                .putLongArray("BlockStates", packed)
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", ListBinaryTag.from(List.of(section)))
                        .build())
                .build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> new NormaliseBitPacking().apply(chunk, ANY_CONTEXT));
        assertTrue(exception.getMessage().contains("Palette"));
    }

    @Test
    void testABlockStatesArrayWhoseLengthIsNotAMultipleOfSixtyFourEntriesFailsRatherThanRoundingDown() {
        // 63 longs cannot hold any whole number of bits-per-entry over 4096 positions (63 * 64 / 4096
        // rounds down to 0 under plain integer division): the old, unchecked division would have
        // silently accepted this as "0 bits per entry" and only failed two calls later, inside
        // LegacyBitReader, with an unrelated-looking IllegalArgumentException. A single palette entry
        // is fine here — even a valid single-entry section carries no BlockStates array at all, so an
        // array of any length alongside a palette is already a shape only a corrupt or hand-built
        // chunk could produce, and the step must say so itself rather than let a lower-level class do
        // it by accident.
        long[] tooShort = new long[63];
        ListBinaryTag palette = ListBinaryTag.from(List.of(
                CompoundBinaryTag.builder().putString("Name", "minecraft:stone").build(),
                CompoundBinaryTag.builder().putString("Name", "minecraft:air").build()));
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 0)
                .put("Palette", palette)
                .putLongArray("BlockStates", tooShort)
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", ListBinaryTag.from(List.of(section)))
                        .build())
                .build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> new NormaliseBitPacking().apply(chunk, ANY_CONTEXT));
        assertTrue(exception.getMessage().contains("63"));
    }

    @Test
    void testABlockStatesArrayLongEnoughForOneBitButNotAnExactMultipleFailsRatherThanRoundingDown() {
        // 100 longs: at least one bit's worth (64 longs) but 100 * 64 = 6400, which does not divide
        // evenly by 4096 (6400 / 4096 = 1.5625). Plain integer division would silently round this
        // down to "1 bit per entry" and misread the array rather than refusing it.
        long[] notAMultiple = new long[100];
        ListBinaryTag palette = ListBinaryTag.from(List.of(
                CompoundBinaryTag.builder().putString("Name", "minecraft:stone").build(),
                CompoundBinaryTag.builder().putString("Name", "minecraft:air").build()));
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 0)
                .put("Palette", palette)
                .putLongArray("BlockStates", notAMultiple)
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", ListBinaryTag.from(List.of(section)))
                        .build())
                .build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> new NormaliseBitPacking().apply(chunk, ANY_CONTEXT));
        assertTrue(exception.getMessage().contains("100"));
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
    void testAOneThousandTwentyFourEntryBiomeArrayBecomesAUniformPaletteForItsSection() {
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

    // --- Sections outside the fixed 0..15 range ---------------------------------------------------

    @Test
    void testASectionBelowZeroDoesNotCrashRebuildBiomesAndIsDroppedRatherThanKept() {
        // Vanilla writes one extra section below the real 0..15 range (Y = -1) purely to carry
        // lighting data for the section it borders. Without discarding it first, its offset into
        // the widened biome array (-1 * 64 = -64) is negative and indexing it throws
        // ArrayIndexOutOfBoundsException rather than a MigrationException.
        int[] biomes = new int[1024];
        java.util.Arrays.fill(biomes, 1);

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putIntArray("Biomes", biomes)
                .put("sections", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putInt("Y", -1).build(),
                        CompoundBinaryTag.builder().putInt("Y", 0).build())))
                .build();

        CompoundBinaryTag rebuilt = new RebuildBiomes().apply(chunk, ANY_CONTEXT);

        ListBinaryTag sections = rebuilt.getList("sections");
        assertEquals(1, sections.size(), "the Y=-1 lighting-only section must not survive into the output");
        assertEquals(0, sections.getCompound(0).getInt("Y"));
    }

    @Test
    void testAnOutOfRangeSectionDoesNotCountTowardsSettleYRangesMinimum() {
        // Exercised directly against SettleYRange, independent of whether RebuildBiomes already
        // ran and already cleaned the list - the step re-checks the range itself rather than
        // trusting the chain's ordering (see its own Javadoc).
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("sections", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putInt("Y", -1).build())))
                .build();

        CompoundBinaryTag settled = new SettleYRange().apply(chunk, ANY_CONTEXT);

        assertEquals(0, settled.getInt("yPos"),
                "no in-range section is present, so this falls back to the same default an empty list gets");
    }

    @Test
    void testSectionsAtYMinusOneAndYSixteenSurviveTheWholeChainDiscardedRatherThanCorruptingItOrYPos() {
        // The end-to-end version of the two direct tests above: a chunk with a lighting-only
        // section on both sides of the real range must migrate cleanly, end up with only its one
        // real section, and settle yPos on that real section's own Y rather than the discarded one.
        int[] biomes = new int[1024];
        java.util.Arrays.fill(biomes, 1);

        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1519)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .putString("Status", "postprocessed")
                        .putIntArray("Biomes", biomes)
                        .put("Sections", ListBinaryTag.from(List.of(
                                CompoundBinaryTag.builder().putByte("Y", (byte) -1).build(),
                                CompoundBinaryTag.builder().putByte("Y", (byte) 0).build(),
                                CompoundBinaryTag.builder().putByte("Y", (byte) 16).build())))
                        .build())
                .build();

        CompoundBinaryTag migrated = ChunkMigration.migrate(chunk, 4790);

        ListBinaryTag sections = migrated.getList("sections");
        assertEquals(1, sections.size(), "only Y=0 is real content; Y=-1 and Y=16 are lighting-only");
        assertEquals(0, sections.getCompound(0).getInt("Y"));
        assertEquals(0, migrated.getInt("yPos"), "the lowest REAL section is 0, not the discarded Y=-1");
    }

    // --- TranslateBlockStates, including the full-chain Gegenprobe target -----------------------

    @Test
    void testAMultiEntryPaletteWithNoIndicesToAddressItFailsRatherThanWritingAnIncompleteContainer() {
        // A multi-entry palette with no BlockStates array at all is not a shape a genuine writer
        // produces — the format's single-value shape (no data array) means exactly one entry — but
        // nothing upstream of this step rules it out, so this step has to refuse it itself rather
        // than silently write a palette with several named options and nothing saying which block
        // holds which.
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("Palette", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putString("Name", "minecraft:stone_slab").build(),
                        CompoundBinaryTag.builder().putString("Name", "minecraft:air").build())))
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("sections", ListBinaryTag.from(List.of(section)))
                .build();

        MigrationException exception = assertThrows(MigrationException.class,
                () -> new TranslateBlockStates().apply(chunk, new MigrationContext(1519, 4790)));
        assertTrue(exception.getMessage().contains("2"));
    }

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

    @Test
    void testAnOverWidthPackedLegacyPaletteIsDecodedAtItsActualWidthNotThePaletteMinimum() {
        // A palette of 17 entries needs only 5 bits (BitPacker.bitsPerEntry(17, 4)); this fixture
        // packs it with 6 instead - a valid choice the format explicitly allows a writer to make,
        // and exactly what NormaliseBitPacking itself would hand this step if the original writer
        // had done the same (it preserves whatever width it finds rather than compacting to the
        // minimum - see its own testAnOverWidthPackedPaletteIsReadAtTheWidthItWasActuallyPackedWith).
        // Entry 0 is a stone_slab so a rule actually fires at DataVersion 1519, forcing the full
        // decode-translate-reencode path to run rather than short-circuiting as unchanged.
        int bitsPerEntry = 6;
        int[] values = new int[16 * 16 * 16];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 17;
        }
        long[] overWidthPacked = BitPacker.pack(values, bitsPerEntry);

        ListBinaryTag palette = ListBinaryTag.empty()
                .add(CompoundBinaryTag.builder().putString("Name", "minecraft:stone_slab").build());
        for (int i = 1; i < 17; i++) {
            palette = palette.add(CompoundBinaryTag.builder().putString("Name", "minecraft:test_" + i).build());
        }

        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("Palette", palette)
                .putLongArray("BlockStates", overWidthPacked)
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("sections", ListBinaryTag.from(List.of(section)))
                .build();

        CompoundBinaryTag translated = new TranslateBlockStates().apply(chunk, new MigrationContext(1519, 4790));

        CompoundBinaryTag blockStates = translated.getList("sections").getCompound(0).getCompound("block_states");
        assertEquals("minecraft:smooth_stone_slab", blockStates.getList("palette").getCompound(0).getString("Name"),
                "sanity check that the rename actually fired, which is what forces the full decode/reencode path");

        // The rebuilt container re-encodes at its own minimal width (5 bits for 17 entries),
        // regardless of the 6 bits the input used - what must survive is the actual VALUES, i.e.
        // that decoding the 6-bit input read the right indices in the first place.
        int outputBitsPerEntry = BitPacker.bitsPerEntry(17, 4);
        long[] repacked = blockStates.getLongArray("data");
        int[] roundTripped = BitPacker.unpack(repacked, values.length, outputBitsPerEntry);
        assertArrayEquals(values, roundTripped,
                "the 6-bit width the section was actually packed at must be used to decode it, not the "
                        + "5-bit minimum a palette of 17 entries alone would suggest");
    }

    @Test
    void testASectionAlreadyInTheModernShapeWithNoFiringRuleIsPassedThroughRatherThanReencoded() {
        CompoundBinaryTag palette = CompoundBinaryTag.builder().putString("Name", "minecraft:stone").build();
        CompoundBinaryTag blockStates = CompoundBinaryTag.builder()
                .put("palette", ListBinaryTag.from(List.of(palette)))
                .build();
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("block_states", blockStates)
                .build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder()
                .put("sections", ListBinaryTag.from(List.of(section)))
                .build();

        CompoundBinaryTag translated = new TranslateBlockStates().apply(chunk, new MigrationContext(4790, 4790));

        assertEquals(section, translated.getList("sections").getCompound(0),
                "no rule applies at DataVersion 4790, so the whole section must come back exactly as read");
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
                        .putString("Status", "postprocessed")
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
