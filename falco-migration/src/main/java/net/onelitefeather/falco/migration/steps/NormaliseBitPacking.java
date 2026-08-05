package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.onelitefeather.falco.anvil.BitPacker;
import net.onelitefeather.falco.migration.LegacyBitReader;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationException;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

/**
 * Re-packs every section's block-state data from the boundary-spanning layout every version below
 * Minecraft 1.16 (DataVersion 2529, the 20w17a snapshot — see {@link #APPLIES_BELOW}'s own javadoc
 * for why not the release number, 2566) wrote into the long-aligned layout {@code falco-anvil}'s
 * {@link BitPacker} understands.
 * <p>
 * This step runs before {@link UnfoldLevel} in {@code ChunkMigration}'s chain — its own threshold,
 * 2529, is strictly below {@code UnfoldLevel}'s, 2844, so every chunk this step applies to still
 * carries the pre-1.18 {@code Level} wrapper when this step sees it. It therefore reads
 * {@code Level.Sections}, not the root {@code sections} list later steps use, and only touches each
 * section's {@code BlockStates} long array — the key names and every other field are left exactly as
 * they were; restructuring the container itself into the modern
 * {@code block_states: \{palette, data\}} shape is
 * {@link TranslateBlockStates}'s job, which runs after this step has made every section's packing
 * long-aligned.
 * </p>
 * <p>
 * A section whose palette holds a single entry carries no {@code BlockStates} array at all — the
 * format omits it and lets the one palette entry fill the whole section — and is returned unchanged,
 * as is a chunk with no {@code Level} compound.
 * </p>
 * <p>
 * <b>The width a section is re-packed at is not the width it was read at.</b> An earlier version of
 * this step preserved whatever width {@link #exactBitsPerEntry(int)} recovered from the legacy array,
 * on the reasoning that the exact original width is always recoverable from the spanning format's own
 * length with no ambiguity (unlike the long-aligned format, which pads). That is true, but it does not
 * survive the round trip: {@link TranslateBlockStates}, reading the long-aligned array back, has only
 * the array's length and the palette size to work from, and {@code BitPacker.expectedLongCount} maps
 * more than one width to the same long count for {@link #BLOCK_ENTRIES} entries — 11 and 12 bits both
 * produce 820 longs, for instance — so a width a writer over-provisioned (the format allows more bits
 * than a palette strictly needs) is not always recoverable downstream, and a wrong guess corrupts the
 * section silently rather than throwing. Repacking at the palette's own canonical minimum,
 * {@code BitPacker.bitsPerEntry(paletteSize, 4)}, removes the ambiguity constructively instead of
 * hoping the disambiguation heuristic guesses right: the indices this step unpacked are carried
 * through exactly, only the width they are written back at changes, and every section this step
 * touches is guaranteed to carry the one width {@link TranslateBlockStates} would derive from its
 * palette alone. See {@code SectionStepsTest.testAnOverWidthPackedSectionIsCanonicalisedRatherThanPreservingTheAmbiguousWidth}
 * for the measured 11/12-bit collision this closes.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class NormaliseBitPacking implements MigrationStep {

    /**
     * DataVersion 2529, snapshot 20w17a — <b>not</b> 2566, the design document's own number, which
     * is 1.16's <em>release</em> DataVersion rather than the snapshot the change actually landed in.
     * Checked directly against 20w17a's own minecraft.wiki changelog and infobox, 2026-08-04: "Format
     * in chunks has been slightly changed... {@code BlockStates} in {@code Sections} elements no
     * longer contain values stretching over multiple 64-bit fields", DataVersion 2529. This is the
     * same release-instead-of-snapshot mistake this module has already found and corrected several
     * times over elsewhere — in the block-state rename table and in {@code CountEntities}'s own
     * threshold — so it was checked here too rather than trusted. A chunk in the gap this correction
     * closes, DataVersion
     * 2529 up to but not including 2566 (20w17a's own pre-releases through 1.16 Release Candidate 1),
     * already writes the non-spanning layout; treating it as spanning would have this step and
     * {@link LegacyBitReader} misread already-correct data for any bits-per-entry that does not evenly
     * divide 64, corrupting it on repacking.
     */
    private static final int APPLIES_BELOW = 2529;

    private static final String LEVEL_KEY = "Level";
    private static final String SECTIONS_KEY = "Sections";
    private static final String BLOCK_STATES_KEY = "BlockStates";
    private static final String PALETTE_KEY = "Palette";

    private static final int BLOCK_ENTRIES = 16 * 16 * 16;
    private static final int BITS_PER_LONG = Long.SIZE;

    /**
     * Minestom's {@code net.minestom.server.instance.palette.Palette.BLOCK_PALETTE_MIN_BITS}
     * (checked in the sources jar of {@code net.minestom:minestom}), the same constant
     * {@code TranslateBlockStates.BLOCK_PALETTE_MIN_BITS} pins for the same reason — duplicated
     * rather than shared, because {@code falco-archunit}'s {@code migrationKnowsNoMinestom} forbids
     * this module from depending on {@code net.minestom} even indirectly through a constant import.
     * Used to compute the canonical, unambiguous width every section is re-packed at; see this
     * class's own javadoc for why the width it reads is not reused for the width it writes.
     */
    private static final int BLOCK_PALETTE_MIN_BITS = 4;

    /**
     * Creates a new instance of this stateless step.
     */
    public NormaliseBitPacking() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return sourceVersion < APPLIES_BELOW;
    }

    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        if (!(chunk.get(LEVEL_KEY) instanceof CompoundBinaryTag level)) {
            return chunk;
        }

        ListBinaryTag sections = level.getList(SECTIONS_KEY, BinaryTagTypes.COMPOUND);
        if (sections.size() == 0) {
            return chunk;
        }

        ListBinaryTag normalised = ListBinaryTag.empty();
        for (BinaryTag sectionTag : sections) {
            normalised = normalised.add(sectionTag instanceof CompoundBinaryTag section
                    ? normalise(section)
                    : sectionTag);
        }

        return chunk.put(LEVEL_KEY, level.put(SECTIONS_KEY, normalised));
    }

    private static CompoundBinaryTag normalise(CompoundBinaryTag section) {
        if (!(section.get(BLOCK_STATES_KEY) instanceof LongArrayBinaryTag legacy)) {
            return section;
        }

        if (!(section.get(PALETTE_KEY) instanceof ListBinaryTag palette) || palette.size() == 0) {
            throw new MigrationException(
                    "A section carries a legacy 'BlockStates' array but no (or an empty) 'Palette' to "
                            + "address, so the bits-per-entry it was packed with cannot be recovered");
        }

        long[] packed = legacy.value();
        int readBitsPerEntry = exactBitsPerEntry(packed.length);
        int[] indices = LegacyBitReader.unpack(packed, readBitsPerEntry, BLOCK_ENTRIES);

        // Re-packed at the palette's own canonical minimum, not the (possibly wider) width the
        // section was actually read at — see this class's own javadoc for why preserving the read
        // width leaves TranslateBlockStates unable to always recover it downstream.
        int canonicalBitsPerEntry = BitPacker.bitsPerEntry(palette.size(), BLOCK_PALETTE_MIN_BITS);
        long[] repacked = BitPacker.pack(indices, canonicalBitsPerEntry);

        return section.putLongArray(BLOCK_STATES_KEY, repacked);
    }

    /**
     * Derives the bits-per-entry a legacy writer actually used from the packed array's own length.
     * <p>
     * The format explicitly allows a writer to use more bits than a palette strictly needs —
     * {@code falco-anvil}'s own {@code PaletteData.read} carries the same caveat for the modern
     * layout — which is why this derivation reads the width the writer actually used rather than
     * assuming the palette's own minimum. It is exact rather than a best guess: the legacy layout has
     * no padding at all — every long is completely full except possibly the last few bits of the
     * final one — and {@link #BLOCK_ENTRIES} (4096) is itself a multiple of 64, so
     * {@code longCount * 64} is always evenly divisible by {@code BLOCK_ENTRIES} with no rounding,
     * for any {@code bitsPerEntry} a real writer could have used. (What this method's result is then
     * re-packed at is a separate decision — see {@link #normalise(CompoundBinaryTag)}, which does not
     * reuse it.)
     * </p>
     *
     * @param longCount the length of the packed {@code BlockStates} array
     * @return the exact bits-per-entry that array was packed with
     * @throws MigrationException if {@code longCount} does not correspond to any whole
     *                             bits-per-entry over {@link #BLOCK_ENTRIES} entries — either because
     *                             it is not an exact multiple, or because it holds too few longs for
     *                             even one bit per entry
     */
    private static int exactBitsPerEntry(int longCount) {
        long totalBits = (long) longCount * BITS_PER_LONG;
        if (totalBits % BLOCK_ENTRIES != 0) {
            throw new MigrationException("A section's legacy 'BlockStates' array holds " + longCount
                    + " longs, which is not an exact multiple of " + BLOCK_ENTRIES
                    + " block positions and therefore matches no whole bits-per-entry");
        }
        int bitsPerEntry = (int) (totalBits / BLOCK_ENTRIES);
        if (bitsPerEntry == 0) {
            throw new MigrationException("A section's legacy 'BlockStates' array holds " + longCount
                    + " longs, too few for even one bit per entry over " + BLOCK_ENTRIES + " block positions");
        }
        return bitsPerEntry;
    }
}
