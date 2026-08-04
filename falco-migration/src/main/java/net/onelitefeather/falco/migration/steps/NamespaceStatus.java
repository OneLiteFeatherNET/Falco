package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

/**
 * Rewrites a chunk status without a namespace, such as {@code full}, into its namespaced form,
 * {@code minecraft:full}.
 * <p>
 * This step tests no {@code DataVersion} at all — {@link #appliesTo(int)} always returns
 * {@code true} — because the exact version that namespaced the chunk status could not be established;
 * the wiki's own change history for the field carries a notice that it is missing a significant number
 * of changes. Testing whether {@code Status} already carries a {@code ':'} is correct for every
 * version in this module's range regardless, and does not depend on a number nobody has actually
 * read: a status that already has a namespace is left alone, and a bare status is prefixed with
 * {@code minecraft:}.
 * </p>
 * <p>
 * A chunk with no {@code Status} field at all is returned unchanged.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class NamespaceStatus implements MigrationStep {

    private static final String STATUS_KEY = "Status";
    private static final String MINECRAFT_NAMESPACE = "minecraft:";

    /**
     * Creates a new instance of this stateless step.
     */
    public NamespaceStatus() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return true;
    }

    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        if (!(chunk.get(STATUS_KEY) instanceof StringBinaryTag status)) {
            return chunk;
        }

        String value = status.value();
        if (value.indexOf(':') >= 0) {
            return chunk;
        }
        return chunk.putString(STATUS_KEY, MINECRAFT_NAMESPACE + value);
    }
}
