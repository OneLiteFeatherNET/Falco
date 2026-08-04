package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.onelitefeather.falco.anvil.BitPacker;
import net.onelitefeather.falco.migration.BlockState;
import net.onelitefeather.falco.migration.BlockStateRules;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationException;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks every section's block palette and puts each entry through
 * {@link BlockStateRules#translate(BlockState, int)}, carrying the chunk's source version.
 * <p>
 * Runs at every version — {@link #appliesTo(int)} always returns {@code true} — because a rename can
 * apply anywhere in this module's whole 1.13-to-today range, and because this step is also the one
 * that restructures a legacy section's block data into the modern container shape.
 * </p>
 * <p>
 * <b>A legacy section's block palette lives at the top level: {@code Palette} and
 * {@code BlockStates}, siblings of {@code Y}.</b> The nested {@code block_states:
 * \{palette, data\}} container only exists from DataVersion 2844 onward — the same version that
 * removed {@code Level} and introduced {@code yPos} — and no step earlier in the chain restructures
 * it: {@link NormaliseBitPacking} only fixes the bit layout of the legacy {@code BlockStates} array,
 * it does not rename or nest it. This step is therefore the one place that shape changes, for exactly
 * the versions that still have it; a section that already carries {@code block_states} is read from
 * there instead and always re-written as {@code block_states}, whether or not any rule fired.
 * </p>
 * <p>
 * A palette entry is a compound with {@code Name} and, if the block has any, {@code Properties} —
 * unchanged in shape across the whole range this module covers, only its container's position moved.
 * Translating a palette entry can change its name, its properties, or both, but never the number of
 * entries or which index of the packed data addresses which entry, so the packed indices themselves
 * are carried through unchanged; only the palette they address is rewritten.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class TranslateBlockStates implements MigrationStep {

    private static final String SECTIONS_KEY = "sections";
    private static final String BLOCK_STATES_KEY = "block_states";
    private static final String LEGACY_PALETTE_KEY = "Palette";
    private static final String LEGACY_BLOCK_STATES_KEY = "BlockStates";
    private static final String PALETTE_KEY = "palette";
    private static final String DATA_KEY = "data";
    private static final String NAME_KEY = "Name";
    private static final String PROPERTIES_KEY = "Properties";

    private static final int BLOCK_ENTRIES = 16 * 16 * 16;

    /**
     * Minestom's {@code net.minestom.server.instance.palette.Palette.BLOCK_PALETTE_MIN_BITS}
     * (checked in the sources jar of {@code net.minestom:minestom}). This module cannot depend on
     * {@code net.minestom} — {@code falco-archunit}'s {@code migrationKnowsNoMinestom} forbids it —
     * so the constant is pinned here instead, with its source named, rather than imported. Used only
     * when this step chooses its own bits-per-entry while <em>writing</em> a container — never while
     * reading one back; see {@link #readFrom} for why a palette-derived guess is not safe there.
     */
    private static final int BLOCK_PALETTE_MIN_BITS = 4;

    /**
     * Creates a new instance of this stateless step.
     */
    public TranslateBlockStates() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return true;
    }

    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        ListBinaryTag sections = chunk.getList(SECTIONS_KEY, BinaryTagTypes.COMPOUND);
        if (sections.size() == 0) {
            return chunk;
        }

        ListBinaryTag translated = ListBinaryTag.empty();
        for (BinaryTag sectionTag : sections) {
            translated = translated.add(sectionTag instanceof CompoundBinaryTag section
                    ? translate(section, context.sourceVersion())
                    : sectionTag);
        }
        return chunk.put(SECTIONS_KEY, translated);
    }

    private static CompoundBinaryTag translate(CompoundBinaryTag section, int sourceVersion) {
        boolean alreadyModernShape = section.get(BLOCK_STATES_KEY) instanceof CompoundBinaryTag;
        PaletteContainer container = readContainer(section);
        if (container == null) {
            return section;
        }

        List<BlockState> translatedPalette = new ArrayList<>(container.palette().size());
        boolean anyStateChanged = false;
        for (BlockState state : container.palette()) {
            BlockState translated = BlockStateRules.translate(state, sourceVersion);
            anyStateChanged |= !translated.equals(state);
            translatedPalette.add(translated);
        }

        if (alreadyModernShape && !anyStateChanged) {
            // Nothing to restructure (the container is already in the modern shape) and no rule
            // fired: unpacking and re-packing the section's data would only cost time and risk
            // re-encoding it differently for no reason, so the whole section is passed through
            // exactly as read, packed data included.
            return section;
        }

        CompoundBinaryTag rebuilt = writeContainer(translatedPalette, container.indices());
        return section.remove(LEGACY_PALETTE_KEY).remove(LEGACY_BLOCK_STATES_KEY).put(BLOCK_STATES_KEY, rebuilt);
    }

    private static @Nullable PaletteContainer readContainer(CompoundBinaryTag section) {
        if (section.get(BLOCK_STATES_KEY) instanceof CompoundBinaryTag modern) {
            return readFrom(modern.getList(PALETTE_KEY, BinaryTagTypes.COMPOUND), modern.get(DATA_KEY));
        }
        if (section.get(LEGACY_PALETTE_KEY) instanceof ListBinaryTag legacyPalette) {
            return readFrom(legacyPalette, section.get(LEGACY_BLOCK_STATES_KEY));
        }
        return null;
    }

    /**
     * Reads a palette and its packed indices, if any.
     * <p>
     * <b>The bits-per-entry a writer actually used is not assumed from the palette size.</b> The
     * format explicitly permits a writer to use more bits than a palette strictly needs, which is
     * exactly why {@code falco-anvil}'s own {@code PaletteData.read} resolves the width against the
     * packed array's own length instead of trusting the palette-derived minimum — and why this method
     * does the same, through {@code BitPacker.resolveBitsPerEntry}, rather than repeating the mistake
     * a palette-size guess would make: silently reading a 6-bit-packed palette of 17 entries as if it
     * were the 5 bits the palette alone would suggest, corrupting every block in the section without
     * throwing.
     * </p>
     */
    private static PaletteContainer readFrom(ListBinaryTag paletteTag, @Nullable BinaryTag dataTag) {
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (BinaryTag entryTag : paletteTag) {
            if (entryTag instanceof CompoundBinaryTag entry) {
                palette.add(readState(entry));
            }
        }

        int[] indices;
        if (dataTag instanceof LongArrayBinaryTag data) {
            long[] packed = data.value();
            int expected = BitPacker.bitsPerEntry(palette.size(), BLOCK_PALETTE_MIN_BITS);
            int bitsPerEntry = BitPacker.resolveBitsPerEntry(packed.length, BLOCK_ENTRIES, expected);
            if (bitsPerEntry == 0) {
                throw new MigrationException("A section's block data holds " + packed.length
                        + " longs, which matches no valid bits-per-entry for a palette of " + palette.size()
                        + " entries over " + BLOCK_ENTRIES + " block positions");
            }
            indices = BitPacker.unpack(packed, BLOCK_ENTRIES, bitsPerEntry);
        } else {
            indices = new int[0];
        }
        return new PaletteContainer(palette, indices);
    }

    private static BlockState readState(CompoundBinaryTag entry) {
        String name = entry.getString(NAME_KEY);
        Map<String, String> properties = new LinkedHashMap<>();
        if (entry.get(PROPERTIES_KEY) instanceof CompoundBinaryTag propertiesTag) {
            for (Map.Entry<String, ? extends BinaryTag> property : propertiesTag) {
                if (property.getValue() instanceof StringBinaryTag value) {
                    properties.put(property.getKey(), value.value());
                }
            }
        }
        return new BlockState(name, properties);
    }

    private static CompoundBinaryTag writeContainer(List<BlockState> palette, int[] indices) {
        ListBinaryTag paletteTag = ListBinaryTag.empty();
        for (BlockState state : palette) {
            paletteTag = paletteTag.add(writeState(state));
        }

        CompoundBinaryTag container = CompoundBinaryTag.builder().put(PALETTE_KEY, paletteTag).build();
        if (palette.size() > 1 && indices.length > 0) {
            int bitsPerEntry = BitPacker.bitsPerEntry(palette.size(), BLOCK_PALETTE_MIN_BITS);
            long[] packed = BitPacker.pack(indices, bitsPerEntry);
            container = container.put(DATA_KEY, LongArrayBinaryTag.longArrayBinaryTag(packed));
        }
        return container;
    }

    private static CompoundBinaryTag writeState(BlockState state) {
        CompoundBinaryTag entry = CompoundBinaryTag.builder().putString(NAME_KEY, state.name()).build();
        if (!state.properties().isEmpty()) {
            CompoundBinaryTag.Builder properties = CompoundBinaryTag.builder();
            state.properties().forEach(properties::putString);
            entry = entry.put(PROPERTIES_KEY, properties.build());
        }
        return entry;
    }

    private record PaletteContainer(List<BlockState> palette, int[] indices) {
    }
}
