package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.onelitefeather.falco.anvil.BitPacker;
import net.onelitefeather.falco.migration.LegacyBitReader;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

/**
 * Re-packs every section's block-state data from the boundary-spanning layout every version below
 * Minecraft 1.16 (DataVersion 2566) wrote into the long-aligned layout {@code falco-anvil}'s
 * {@link BitPacker} understands.
 * <p>
 * This step runs before {@link UnfoldLevel} in {@code ChunkMigration}'s chain — its own threshold,
 * 2566, is strictly below {@code UnfoldLevel}'s, 2844, so every chunk this step applies to still
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
     * times over for the block-state rename table and, in Task 6, {@code CountEntities} — so it was
     * checked here too rather than trusted. A chunk in the gap this correction closes, DataVersion
     * 2529 up to but not including 2566 (20w17a's own pre-releases through 1.16 Release Candidate 1),
     * already writes the non-spanning layout; treating it as spanning would have this step and
     * {@link LegacyBitReader} misread already-correct data for any bits-per-entry that does not evenly
     * divide 64, corrupting it on repacking.
     */
    private static final int APPLIES_BELOW = 2529;

    private static final String LEVEL_KEY = "Level";
    private static final String SECTIONS_KEY = "Sections";
    private static final String PALETTE_KEY = "Palette";
    private static final String BLOCK_STATES_KEY = "BlockStates";

    private static final int BLOCK_ENTRIES = 16 * 16 * 16;

    /**
     * Minestom's {@code net.minestom.server.instance.palette.Palette.BLOCK_PALETTE_MIN_BITS}
     * (checked in the sources jar of {@code net.minestom:minestom}). This module cannot depend on
     * {@code net.minestom} — {@code falco-archunit}'s {@code migrationKnowsNoMinestom} forbids it —
     * so the constant is pinned here instead, with its source named, rather than imported.
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

        ListBinaryTag palette = section.getList(PALETTE_KEY, BinaryTagTypes.COMPOUND);
        int bitsPerEntry = BitPacker.bitsPerEntry(palette.size(), BLOCK_PALETTE_MIN_BITS);

        int[] indices = LegacyBitReader.unpack(legacy.value(), bitsPerEntry, BLOCK_ENTRIES);
        long[] repacked = BitPacker.pack(indices, bitsPerEntry);

        return section.putLongArray(BLOCK_STATES_KEY, repacked);
    }
}
