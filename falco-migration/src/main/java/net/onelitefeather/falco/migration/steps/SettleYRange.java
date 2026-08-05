package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

/**
 * Settles what {@code yPos} means for a converted chunk: the lowest {@code Y} a section already
 * present in the chunk's own {@code sections} list actually carries — never an invented floor.
 * <p>
 * <b>The question this step answers, and the two sources that answer it.</b> The field's own
 * documentation on minecraft.wiki's {@code Chunk format} article (fetched 2026-08-04) reads
 * "[Int] yPos: Lowest Y section position <em>in the chunk</em> (e.g. {@code -4} in 1.18)" — wording
 * that, read alone, is ambiguous between "the lowest section this chunk itself stores" and "the
 * bottom of the dimension's height range". The same article settles which reading is load-bearing,
 * in its description of the {@code sections} list itself: "All sections in the world's height are
 * present in this list, even those who are empty (filled with air)." Vanilla always writes every
 * section down to the dimension floor, so for a vanilla file the two readings coincide by
 * construction — but a chunk converted by this module does not invent sections it never had, which is
 * exactly the case where they would split if the field meant the dimension floor. The field's own
 * name and wording — "in the chunk" — settle it as the chunk's own lowest section, so this step
 * computes {@code yPos} from what the chunk's {@code sections} list actually contains.
 * </p>
 * <p>
 * <b>The second source — what the target actually does with the value — confirms this carries no
 * risk either way.</b> Minestom's own {@code AnvilLoader} (checked in the sources jar of
 * {@code net.minestom:minestom}, {@code loadSections}) never reads {@code yPos} at all: it derives
 * the section range purely from {@code chunk.getMinSection()} / {@code chunk.getMaxSection()} — the
 * running instance's own dimension type — and discards any section tag whose own {@code Y} falls
 * outside that range, with the comment "Vanilla stores a section below and above the world for
 * lighting, throw it out." It writes {@code yPos = chunk.getMinSection()} on save, but that is an
 * echo of its own understanding for other readers, never something it reads back. Falco's own
 * {@code FalcoAnvilLoader} mirrors this exactly. The two sources therefore do not disagree: the wiki
 * states the field's declared meaning, and the loader that actually reads the file confirms that
 * meaning is inert to it operationally, because it never consults the field at all. Writing anything
 * other than the chunk's own true lowest section would misdescribe the chunk to every reader that
 * <em>does</em> consult it, for zero benefit to the one reader this project controls.
 * </p>
 * <p>
 * Runs after {@link UnfoldLevel}, so it reads the root {@code sections} list. A chunk whose
 * {@code sections} list is empty, or absent, is returned unchanged rather than stamped with an
 * invented {@code yPos} — there is no section in the chunk for any such value to describe.
 * </p>
 * <p>
 * <b>Every remaining section counts, without a fixed range filter.</b> An earlier version of this
 * step ignored a section outside a fixed {@code Y} range of {@code 0}..{@code 15} on the theory that
 * only vanilla's own lighting-only border sections — one below and one above a chunk's real content,
 * discarded by {@link RebuildBiomes} earlier in the chain — could ever fall outside it. That theory
 * held only for a world at the pre-1.18 fixed height, {@code 0}..{@code 255}; see
 * {@link RebuildBiomes}'s own javadoc for why a world converted from a custom height (possible from
 * DataVersion 2685 onward, well below every source version this step runs for) can genuinely store
 * real content sections outside that range, and for why {@code Y} alone can no longer be trusted to
 * tell such a section apart from a lighting-only one. This step now trusts {@link RebuildBiomes} to
 * have already removed every section that carries no block data, and simply takes the lowest
 * {@code Y} among whatever sections remain — never filtering by, or falling back to, a fixed number
 * that might not correspond to any section the chunk actually has.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class SettleYRange implements MigrationStep {

    /**
     * DataVersion 2844 is the 21w43a snapshot that introduced {@code yPos} in the first place — the
     * same threshold {@link UnfoldLevel} uses for the {@code Level} removal it happened alongside.
     */
    private static final int APPLIES_BELOW = 2844;

    private static final String SECTIONS_KEY = "sections";
    private static final String SECTION_Y_KEY = "Y";
    private static final String Y_POS_KEY = "yPos";

    /**
     * Creates a new instance of this stateless step.
     */
    public SettleYRange() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return sourceVersion < APPLIES_BELOW;
    }

    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        ListBinaryTag sections = chunk.getList(SECTIONS_KEY, BinaryTagTypes.COMPOUND);

        int lowest = 0;
        boolean any = false;
        for (BinaryTag sectionTag : sections) {
            if (sectionTag instanceof CompoundBinaryTag section) {
                int sectionY = section.getInt(SECTION_Y_KEY);
                if (!any || sectionY < lowest) {
                    lowest = sectionY;
                    any = true;
                }
            }
        }

        return any ? chunk.putInt(Y_POS_KEY, lowest) : chunk;
    }
}
