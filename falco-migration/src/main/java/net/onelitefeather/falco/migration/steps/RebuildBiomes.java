package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.onelitefeather.falco.anvil.BitPacker;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationException;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rebuilds the whole-chunk {@code Biomes} array every version below Minecraft 1.18 (DataVersion 2844)
 * wrote into the palettised, per-section {@code biomes} container the target format uses.
 * <p>
 * Runs after {@link UnfoldLevel} in {@code ChunkMigration}'s chain, so it reads the root
 * {@code sections} list and the root {@code Biomes} field {@code UnfoldLevel} already moved there
 * for a pre-1.18 chunk; a chunk with no {@code Biomes} field is returned unchanged.
 * </p>
 * <p>
 * <b>Two source shapes.</b> Below DataVersion 2203 (snapshot 19w36a — confirmed against that
 * snapshot's own changelog and infobox on minecraft.wiki, 2026-08-04) {@code Biomes} holds 256
 * entries, one per column of the chunk's 16-by-16 footprint, with no variance by height. From 2203
 * up to 2844 it holds 1024 entries: the same footprint split into 4-by-4 columns, crossed with 16
 * four-block-tall layers, still stored for the whole chunk rather than per section. Both shapes are
 * converted into the same per-section, 64-entry (4x4x4) form before palettising.
 * </p>
 * <p>
 * <b>Widening a 256-entry array is not an average or a guess.</b> It reproduces
 * <a href="https://github.com/PaperMC/DataConverter">PaperMC/DataConverter</a>'s own
 * {@code V2202} (commit {@code 0782df72}, GPL-3.0, DataVersion 2203) bit for bit: for each 4x4
 * quadrant of the 16x16 grid it samples the single column at the quadrant's centre — not an average
 * of the four — and then repeats that resulting 4x4 layer across every one of the 64 four-block-tall
 * layers a 1024-entry array holds, because a pre-1.15 chunk has no biome variance by height to
 * preserve in the first place.
 * </p>
 * <p>
 * <b>Every entry, in either shape, is a legacy numeric biome id, not a name.</b> String biome names
 * only exist in chunk data from 1.18 onward. Resolving a numeric id therefore needs a per-version
 * table, and — unlike blocks — this project's own vendored source for such tables, the ViaVersion
 * {@code mapping-<version>.json} registry lists Task 3 built {@code BlockStateRules} from, does not
 * carry a {@code biomes} list at all (see the design document's "registry lists" section). The table
 * below is instead sourced from PaperMC/DataConverter's own {@code V2832} (commit {@code 0782df72},
 * GPL-3.0, DataVersion 2832 — the fix that performs this exact conversion for the real 1.18 upgrade),
 * whose {@code BIOMES_BY_ID} array is reproduced here as a set of id-to-name facts, not copied as
 * code. Two things about it matter for correctness:
 * </p>
 * <ul>
 *     <li>The ids were allocated once and never reused — {@code V2832} itself leaves gaps in the
 *     table for ids that were never assigned rather than compacting them, so the same id names the
 *     same biome across every version in this module's 1.13-1.17 range.</li>
 *     <li>The table already carries each biome's <em>final</em> name (for example id {@code 8} is
 *     {@code minecraft:nether_wastes}, not the older {@code minecraft:nether} a 1.13-1.15 world's own
 *     files would have called it by name if names existed yet) — correct here because this module
 *     only ever converts up to a target version at or after 1.18, where the final name is the only
 *     one that is still valid.</li>
 * </ul>
 * <p>
 * <b>An id this table does not know throws, rather than substituting {@code minecraft:plains} the
 * way DataConverter itself does.</b> That is a deliberate divergence from the upstream source of the
 * table's facts, made to keep faith with this project's own stance on an unmappable value: silently
 * inventing a biome is the same silent corruption an unmappable block is refused for elsewhere in
 * this module.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class RebuildBiomes implements MigrationStep {

    private static final int APPLIES_BELOW = 2844;

    private static final String SECTIONS_KEY = "sections";
    private static final String BIOMES_KEY = "Biomes";
    private static final String SECTION_BIOMES_KEY = "biomes";
    private static final String PALETTE_KEY = "palette";
    private static final String DATA_KEY = "data";
    private static final String SECTION_Y_KEY = "Y";

    private static final int PRE_WIDENING_ENTRIES = 256;
    private static final int WIDENED_ENTRIES = 1024;
    private static final int SECTION_BIOME_ENTRIES = 4 * 4 * 4;

    /**
     * Minestom's {@code net.minestom.server.instance.palette.Palette.BIOME_PALETTE_MIN_BITS}
     * (checked in the sources jar of {@code net.minestom:minestom}), pinned here for the same reason
     * {@link NormaliseBitPacking#BLOCK_PALETTE_MIN_BITS} is: this module cannot depend on
     * {@code net.minestom}.
     */
    private static final int BIOME_PALETTE_MIN_BITS = 1;

    private static final Map<Integer, String> LEGACY_BIOME_NAMES = buildLegacyBiomeNames();

    /**
     * Creates a new instance of this stateless step.
     */
    public RebuildBiomes() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return sourceVersion < APPLIES_BELOW;
    }

    /**
     * {@inheritDoc}
     *
     * @param chunk   {@inheritDoc}
     * @param context {@inheritDoc}
     * @return {@inheritDoc}
     * @throws MigrationException if {@code Biomes} holds neither 256 nor 1024 entries, or holds a
     *                             legacy numeric id this step's table does not know
     */
    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        if (!(chunk.get(BIOMES_KEY) instanceof IntArrayBinaryTag biomesTag)) {
            return chunk;
        }

        int[] legacy = biomesTag.value();
        int[] widened = legacy.length == PRE_WIDENING_ENTRIES ? widen(legacy) : legacy;
        if (widened.length != WIDENED_ENTRIES) {
            throw new MigrationException("The chunk's 'Biomes' array holds " + legacy.length
                    + " entries, which matches neither the pre-1.15 shape (" + PRE_WIDENING_ENTRIES
                    + ") nor the 1.15-1.17 shape (" + WIDENED_ENTRIES + ")");
        }

        ListBinaryTag sections = chunk.getList(SECTIONS_KEY, BinaryTagTypes.COMPOUND);
        ListBinaryTag rebuilt = ListBinaryTag.empty();
        for (BinaryTag sectionTag : sections) {
            if (!(sectionTag instanceof CompoundBinaryTag section)) {
                rebuilt = rebuilt.add(sectionTag);
                continue;
            }
            int sectionY = section.getInt(SECTION_Y_KEY);
            rebuilt = rebuilt.add(section.put(SECTION_BIOMES_KEY, biomeContainer(widened, sectionY)));
        }

        return chunk.remove(BIOMES_KEY).put(SECTIONS_KEY, rebuilt);
    }

    private static int[] widen(int[] legacy) {
        int[] widened = new int[WIDENED_ENTRIES];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int k = (j << 2) + 2;
                int l = (i << 2) + 2;
                widened[(i << 2) | j] = legacy[(l << 4) | k];
            }
        }
        for (int i = 1; i < 64; i++) {
            System.arraycopy(widened, 0, widened, i * 16, 16);
        }
        return widened;
    }

    private static CompoundBinaryTag biomeContainer(int[] widened, int sectionY) {
        int offset = sectionY * SECTION_BIOME_ENTRIES;
        int[] localIndices = new int[SECTION_BIOME_ENTRIES];
        Map<Integer, Integer> firstSeenAt = new LinkedHashMap<>();

        for (int cell = 0; cell < SECTION_BIOME_ENTRIES; cell++) {
            int legacyId = widened[offset + cell];
            localIndices[cell] = firstSeenAt.computeIfAbsent(legacyId, id -> firstSeenAt.size());
        }

        ListBinaryTag palette = ListBinaryTag.empty();
        for (int legacyId : firstSeenAt.keySet()) {
            palette = palette.add(StringBinaryTag.stringBinaryTag(nameOf(legacyId)));
        }

        CompoundBinaryTag container = CompoundBinaryTag.builder().put(PALETTE_KEY, palette).build();
        if (firstSeenAt.size() > 1) {
            int bitsPerEntry = BitPacker.bitsPerEntry(firstSeenAt.size(), BIOME_PALETTE_MIN_BITS);
            long[] packed = BitPacker.pack(localIndices, bitsPerEntry);
            container = container.put(DATA_KEY, LongArrayBinaryTag.longArrayBinaryTag(packed));
        }
        return container;
    }

    private static String nameOf(int legacyId) {
        String name = LEGACY_BIOME_NAMES.get(legacyId);
        if (name == null) {
            throw new MigrationException("Legacy biome id " + legacyId + " has no known name in this "
                    + "module's sourced table (PaperMC/DataConverter's V2832, commit 0782df72); a "
                    + "converted chunk must not silently receive an invented biome");
        }
        return name;
    }

    /**
     * The legacy numeric-id-to-name table, sourced from PaperMC/DataConverter's {@code V2832}
     * (commit {@code 0782df72}, {@code BIOMES_BY_ID}). See this class's own javadoc for what the
     * table means and why it, rather than a computed or vendored per-version list, is the source.
     */
    private static Map<Integer, String> buildLegacyBiomeNames() {
        Map<Integer, String> names = new HashMap<>();
        names.put(0, "minecraft:ocean");
        names.put(1, "minecraft:plains");
        names.put(2, "minecraft:desert");
        names.put(3, "minecraft:mountains");
        names.put(4, "minecraft:forest");
        names.put(5, "minecraft:taiga");
        names.put(6, "minecraft:swamp");
        names.put(7, "minecraft:river");
        names.put(8, "minecraft:nether_wastes");
        names.put(9, "minecraft:the_end");
        names.put(10, "minecraft:frozen_ocean");
        names.put(11, "minecraft:frozen_river");
        names.put(12, "minecraft:snowy_tundra");
        names.put(13, "minecraft:snowy_mountains");
        names.put(14, "minecraft:mushroom_fields");
        names.put(15, "minecraft:mushroom_field_shore");
        names.put(16, "minecraft:beach");
        names.put(17, "minecraft:desert_hills");
        names.put(18, "minecraft:wooded_hills");
        names.put(19, "minecraft:taiga_hills");
        names.put(20, "minecraft:mountain_edge");
        names.put(21, "minecraft:jungle");
        names.put(22, "minecraft:jungle_hills");
        names.put(23, "minecraft:jungle_edge");
        names.put(24, "minecraft:deep_ocean");
        names.put(25, "minecraft:stone_shore");
        names.put(26, "minecraft:snowy_beach");
        names.put(27, "minecraft:birch_forest");
        names.put(28, "minecraft:birch_forest_hills");
        names.put(29, "minecraft:dark_forest");
        names.put(30, "minecraft:snowy_taiga");
        names.put(31, "minecraft:snowy_taiga_hills");
        names.put(32, "minecraft:giant_tree_taiga");
        names.put(33, "minecraft:giant_tree_taiga_hills");
        names.put(34, "minecraft:wooded_mountains");
        names.put(35, "minecraft:savanna");
        names.put(36, "minecraft:savanna_plateau");
        names.put(37, "minecraft:badlands");
        names.put(38, "minecraft:wooded_badlands_plateau");
        names.put(39, "minecraft:badlands_plateau");
        names.put(40, "minecraft:small_end_islands");
        names.put(41, "minecraft:end_midlands");
        names.put(42, "minecraft:end_highlands");
        names.put(43, "minecraft:end_barrens");
        names.put(44, "minecraft:warm_ocean");
        names.put(45, "minecraft:lukewarm_ocean");
        names.put(46, "minecraft:cold_ocean");
        names.put(47, "minecraft:deep_warm_ocean");
        names.put(48, "minecraft:deep_lukewarm_ocean");
        names.put(49, "minecraft:deep_cold_ocean");
        names.put(50, "minecraft:deep_frozen_ocean");
        names.put(127, "minecraft:the_void");
        names.put(129, "minecraft:sunflower_plains");
        names.put(130, "minecraft:desert_lakes");
        names.put(131, "minecraft:gravelly_mountains");
        names.put(132, "minecraft:flower_forest");
        names.put(133, "minecraft:taiga_mountains");
        names.put(134, "minecraft:swamp_hills");
        names.put(140, "minecraft:ice_spikes");
        names.put(149, "minecraft:modified_jungle");
        names.put(151, "minecraft:modified_jungle_edge");
        names.put(155, "minecraft:tall_birch_forest");
        names.put(156, "minecraft:tall_birch_hills");
        names.put(157, "minecraft:dark_forest_hills");
        names.put(158, "minecraft:snowy_taiga_mountains");
        names.put(160, "minecraft:giant_spruce_taiga");
        names.put(161, "minecraft:giant_spruce_taiga_hills");
        names.put(162, "minecraft:modified_gravelly_mountains");
        names.put(163, "minecraft:shattered_savanna");
        names.put(164, "minecraft:shattered_savanna_plateau");
        names.put(165, "minecraft:eroded_badlands");
        names.put(166, "minecraft:modified_wooded_badlands_plateau");
        names.put(167, "minecraft:modified_badlands_plateau");
        names.put(168, "minecraft:bamboo_jungle");
        names.put(169, "minecraft:bamboo_jungle_hills");
        names.put(170, "minecraft:soul_sand_valley");
        names.put(171, "minecraft:crimson_forest");
        names.put(172, "minecraft:warped_forest");
        names.put(173, "minecraft:basalt_deltas");
        names.put(174, "minecraft:dripstone_caves");
        names.put(175, "minecraft:lush_caves");
        names.put(177, "minecraft:meadow");
        names.put(178, "minecraft:grove");
        names.put(179, "minecraft:snowy_slopes");
        names.put(180, "minecraft:snowcapped_peaks");
        names.put(181, "minecraft:lofty_peaks");
        names.put(182, "minecraft:stony_peaks");
        return Map.copyOf(names);
    }
}
