package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * Moves every child of the pre-1.18 {@code Level} compound onto the chunk's root, the shape every
 * version from DataVersion 2844 onwards already stores a chunk in.
 * <p>
 * {@code Sections} is renamed to {@code sections} as it moves, because that is the one field name
 * that changed rather than only its position — every other child keeps its own name. A {@code yPos}
 * field is added at the root because 2844 is also the version that introduced it: it names the
 * lowest section index a chunk stores, which for every chunk below 2844 is {@code 0}, since the
 * pre-1.18 world height was fixed to sections {@code 0}–{@code 15} and never reached below the
 * bottom of that range.
 * </p>
 * <p>
 * The list of fields this step moves is deliberately not written down here: it moves whatever
 * {@code Level} actually holds, rather than a fixed set of names invented for this task. A chunk with
 * no {@code Level} compound at all — already unfolded, or never folded in the first place — is
 * returned unchanged.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class UnfoldLevel implements MigrationStep {

    /**
     * DataVersion 2844 is the 21w43a snapshot that removed the {@code Level} compound and moved its
     * contents to the chunk root.
     */
    private static final int APPLIES_BELOW = 2844;

    private static final String LEVEL_KEY = "Level";
    private static final String SECTIONS_KEY = "Sections";
    private static final String LOWERCASE_SECTIONS_KEY = "sections";
    private static final String Y_POS_KEY = "yPos";

    /**
     * Creates a new instance of this stateless step.
     */
    public UnfoldLevel() {
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

        CompoundBinaryTag root = chunk.remove(LEVEL_KEY);
        for (Map.Entry<String, ? extends BinaryTag> child : level) {
            String key = SECTIONS_KEY.equals(child.getKey()) ? LOWERCASE_SECTIONS_KEY : child.getKey();
            root = root.put(key, child.getValue());
        }
        return root.putInt(Y_POS_KEY, 0);
    }
}
