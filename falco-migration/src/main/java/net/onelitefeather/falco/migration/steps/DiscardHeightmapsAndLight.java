package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

/**
 * Deletes the per-chunk heightmaps and the per-section light data instead of converting them.
 * <p>
 * This is a deliberate deletion, not a shortcut taken for lack of time. A wrongly ported heightmap
 * never announces itself: it is a plain long array the server trusts on faith, so a bug in a bit-width
 * or coordinate conversion here would sit silently in every converted world until something built on
 * top of it looked wrong for an unrelated reason. A missing heightmap has no such failure mode — the
 * server rebuilds it the moment it needs one. Light is discarded for the same reason and because
 * {@code falco-light} already computes it from scratch; recomputing was never this module's job to
 * begin with. Runs at every version, because a chunk from any point in this module's range can carry
 * either field.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class DiscardHeightmapsAndLight implements MigrationStep {

    private static final String HEIGHTMAPS_KEY = "Heightmaps";
    private static final String IS_LIGHT_ON_KEY = "isLightOn";
    private static final String SECTIONS_KEY = "sections";
    private static final String BLOCK_LIGHT_KEY = "BlockLight";
    private static final String SKY_LIGHT_KEY = "SkyLight";

    /**
     * Creates a new instance of this stateless step.
     */
    public DiscardHeightmapsAndLight() {
    }

    @Override
    public boolean appliesTo(int sourceVersion) {
        return true;
    }

    @Override
    public CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context) {
        CompoundBinaryTag result = chunk.remove(HEIGHTMAPS_KEY).remove(IS_LIGHT_ON_KEY);

        if (!(result.get(SECTIONS_KEY) instanceof ListBinaryTag sections) || sections.isEmpty()) {
            return result;
        }

        ListBinaryTag cleanedSections = ListBinaryTag.empty();
        for (BinaryTag section : sections) {
            BinaryTag cleaned = section instanceof CompoundBinaryTag sectionCompound
                    ? sectionCompound.remove(BLOCK_LIGHT_KEY).remove(SKY_LIGHT_KEY)
                    : section;
            cleanedSections = cleanedSections.add(cleaned);
        }
        return result.put(SECTIONS_KEY, cleanedSections);
    }
}
