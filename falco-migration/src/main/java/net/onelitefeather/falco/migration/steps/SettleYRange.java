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
 * {@code sections} list is empty, or absent, gets {@code yPos = 0} — the floor every version below
 * 2844 in this module's range actually used (see {@code UnfoldLevel}'s own javadoc) — rather than an
 * arbitrary default.
 * </p>
 * <p>
 * <b>A section outside {@value #MIN_SECTION_Y}..{@value #MAX_SECTION_Y} does not count.</b> Vanilla
 * writes one extra section below and one above the real range purely to carry lighting data — see
 * {@link RebuildBiomes}'s own javadoc, which discards these before this step ever runs, for the
 * sourcing. This step re-checks the same range on its own rather than trusting that ordering: were it
 * ever exercised on a chunk {@code RebuildBiomes} had not already cleaned — directly, in a test, or
 * because the chain is reordered later — a lighting-only section at {@code Y = -1} would otherwise
 * compute {@code yPos = -1}, which is not a section this chunk has any real content for.
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
     * The fixed section range every pre-1.18 chunk in this module's range actually stores content
     * for — see this class's own javadoc for why a section outside it must not count.
     */
    private static final int MIN_SECTION_Y = 0;
    private static final int MAX_SECTION_Y = 15;

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
                if (sectionY < MIN_SECTION_Y || sectionY > MAX_SECTION_Y) {
                    continue;
                }
                if (!any || sectionY < lowest) {
                    lowest = sectionY;
                    any = true;
                }
            }
        }

        return chunk.putInt(Y_POS_KEY, lowest);
    }
}
