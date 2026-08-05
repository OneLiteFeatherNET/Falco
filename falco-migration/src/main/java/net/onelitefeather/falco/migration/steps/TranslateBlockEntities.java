package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * Renames the {@code id} of every block entity a chunk carries, and nothing else.
 * <p>
 * A block entity is part of the chunk in every version this module supports and stays there, so
 * translating it is a rename applied to a tag that is already in the right place — unlike an entity,
 * which has to move to another file from 1.17 onwards (see {@link CountEntities}). Two things about a
 * block entity are explicitly <b>out of scope</b> for this step, and both are passed through
 * untouched rather than fixed: the <b>items inside</b> a block entity — a chest's contents carry item
 * ids that were renamed too, and this step does not look inside {@code Items} or any other nested
 * list — and <b>per-block-entity field changes that are not a rename of {@code id}</b>, of which the
 * sign text rework in Minecraft 1.20 (single {@code Text1}-{@code Text4} lines becoming
 * {@code front_text}/{@code back_text} components) is the largest. A converted block entity is in the
 * right place with the right {@code id}; what is inside it, and every field but {@code id}, has not
 * been looked at.
 * </p>
 * <p>
 * <b>No verified block-entity {@code id} rename exists for the whole 1.13-26.1 span, so
 * {@link #RENAMES} is empty.</b> The 1.13 mapping file this module's block rules are checked against
 * carries no {@code blockentities} list at all, so the difference that settles the block-rename
 * question cannot be computed for block entities the same way. The route taken instead: 26.1's
 * {@code blockentities} registry list has 49 entries, small enough to check exhaustively. Diffing that
 * list across every version between 1.18 (the earliest version whose mapping file carries one at all)
 * and 26.1 found not one name ever removed — every change across that whole span was an addition.
 * Separately, PaperMC/DataConverter — the GPL rebuild of Mojang's own converter this module already
 * treats as authoritative for structural questions no registry diff can answer — registers exactly one
 * block-entity rename in its entire fix history from V99 to V4661: {@code minecraft:suspicious_sand}
 * to {@code minecraft:brushable_block}, which cannot appear in a 1.13 world because
 * {@code suspicious_sand} did not exist before 1.20 snapshots. Both independent sources agree: nothing
 * a 1.13 world can contain ever had its block-entity {@code id} renamed on the way to 26.1.
 * </p>
 * <p>
 * <b>The block-entity list's own container key is renamed too, but not by this step.</b>
 * {@code Level.TileEntities} becomes the root-level {@code block_entities} in {@link UnfoldLevel} —
 * the same snapshot (21w43a, DataVersion 2844) that removed {@code Level} and renamed
 * {@code Sections} to {@code sections} also renamed this field, and {@code UnfoldLevel} handles both
 * renames identically. By the time a pre-2844 chunk reaches this step in the chain, its block entities
 * are therefore already under {@code block_entities}. This step still checks {@code TileEntities} as a
 * fallback — reading whichever of the two keys is actually present — purely for its own robustness
 * against being exercised outside the full chain (directly in a test, or ahead of a future reordering);
 * in the chain this module builds, that fallback is never actually reached.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class TranslateBlockEntities implements MigrationStep {

    private static final String MODERN_KEY = "block_entities";
    private static final String LEGACY_KEY = "TileEntities";
    private static final String ID_KEY = "id";

    /**
     * The block-entity {@code id} renames this module has verified for the 1.13-26.1 span. Empty —
     * see this class's Javadoc for why.
     */
    private static final Map<String, String> RENAMES = Map.of();

    /**
     * Creates a new instance of this stateless step.
     */
    public TranslateBlockEntities() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @param chunk   {@inheritDoc}
     * @param context {@inheritDoc}
     * @return {@code chunk} with every block entity's {@code id} translated through {@link #RENAMES};
     *         unchanged if neither {@code block_entities} nor {@code TileEntities} is present
     */
    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        String key = chunk.get(MODERN_KEY) instanceof ListBinaryTag ? MODERN_KEY
                : chunk.get(LEGACY_KEY) instanceof ListBinaryTag ? LEGACY_KEY
                : null;
        if (key == null) {
            return chunk;
        }

        ListBinaryTag blockEntities = (ListBinaryTag) chunk.get(key);
        if (blockEntities.isEmpty()) {
            return chunk;
        }

        ListBinaryTag translated = ListBinaryTag.empty();
        for (BinaryTag entry : blockEntities) {
            translated = translated.add(translateEntry(entry));
        }
        return chunk.put(key, translated);
    }

    private static BinaryTag translateEntry(BinaryTag entry) {
        if (!(entry instanceof CompoundBinaryTag compound) || !(compound.get(ID_KEY) instanceof StringBinaryTag idTag)) {
            return entry;
        }
        String translated = translate(idTag.value(), RENAMES);
        return translated.equals(idTag.value()) ? compound : compound.putString(ID_KEY, translated);
    }

    /**
     * The pure lookup {@link #apply} runs every block entity's {@code id} through.
     * <p>
     * Package-private so a test can exercise the substitution mechanism itself against a rename table
     * of its own, independent of {@link #RENAMES} — which was found to be empty for every {@code id}
     * a 1.13 world can actually contain; see this class's Javadoc.
     * </p>
     *
     * @param id      the block entity's current {@code id}
     * @param renames the rename table to look {@code id} up in
     * @return {@code renames.getOrDefault(id, id)}
     */
    static String translate(String id, Map<String, String> renames) {
        return renames.getOrDefault(id, id);
    }
}
