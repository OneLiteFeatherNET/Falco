package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationException;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * Moves every child of the pre-1.18 {@code Level} compound onto the chunk's root, the shape every
 * version from DataVersion 2844 onwards already stores a chunk in.
 * <p>
 * {@code Sections} is renamed to {@code sections} as it moves, because that is the one field name
 * that changed rather than only its position — every other child keeps its own name. A field that
 * would silently overwrite a same-named field already present at the root is refused with a
 * {@link MigrationException} instead: for every pre-1.18 format known to this module that case cannot
 * happen, since a chunk's own top-level keys and {@code Level}'s children never collide, but a silent
 * overwrite would contradict this project's fail-loud stance the moment some format this module has
 * not seen turns out to disagree.
 * </p>
 * <p>
 * <b>{@code yPos} itself is not set here.</b> DataVersion 2844 is also the version that introduced
 * the field, but this step hands the decision of what it should hold to {@link SettleYRange}, which
 * runs later in the chain, after biomes and block states have found their place in the unfolded
 * {@code sections} list. See that class's javadoc for the sourced answer — the chunk's own lowest
 * stored section, never an invented dimension floor — and why the two only diverge for a converted
 * chunk in the first place.
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

    /**
     * Creates a new instance of this stateless step.
     */
    public UnfoldLevel() {
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
     * @throws MigrationException if a child of {@code Level} — after the {@code Sections}-to-
     *                             {@code sections} rename, where applicable — has the same name as a
     *                             field already present at the chunk's root, naming that field
     */
    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        if (!(chunk.get(LEVEL_KEY) instanceof CompoundBinaryTag level)) {
            return chunk;
        }

        CompoundBinaryTag root = chunk.remove(LEVEL_KEY);
        for (Map.Entry<String, ? extends BinaryTag> child : level) {
            String key = SECTIONS_KEY.equals(child.getKey()) ? LOWERCASE_SECTIONS_KEY : child.getKey();
            if (root.get(key) != null) {
                throw new MigrationException(
                        "Unfolding 'Level' would silently overwrite the existing root field '" + key + "'");
            }
            root = root.put(key, child.getValue());
        }
        return root;
    }
}
