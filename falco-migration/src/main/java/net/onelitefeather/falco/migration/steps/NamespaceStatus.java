package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import net.onelitefeather.falco.migration.MigrationStep;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * Rewrites a chunk status without a namespace into its namespaced form, translating the handful of
 * pre-1.14 values this module can prove a meaning for along the way — most importantly, a genuinely
 * complete 1.13 chunk's own value, which is <b>not</b> {@code full}.
 * <p>
 * This step tests no {@code DataVersion} at all — {@link #appliesTo(int)} always returns
 * {@code true} — because the exact version that namespaced the chunk status could not be established;
 * the wiki's own change history for the field carries a notice that it is missing a significant number
 * of changes. Testing whether {@code Status} already carries a {@code ':'} is correct for every
 * version in this module's range regardless, and does not depend on a number nobody has actually
 * read: a status that already has a namespace is left alone, and a bare status is looked up in
 * {@link #RENAMED_ON_NAMESPACE} before being prefixed with {@code minecraft:} — the value that lookup
 * does not recognize is prefixed exactly as it was read, unchanged, because this module encodes only
 * what it can source rather than guess a rename it cannot prove.
 * </p>
 * <p>
 * <b>A 1.13 chunk's own terminal status is not {@code full}.</b> Namespaced status ids, and
 * {@code full} as a name at all, did not exist until Minecraft 1.14's own development snapshots;
 * DataVersion 1519 (this module's floor) writes one of the ten values 1.13.2's own
 * {@code ChunkStatus} registers, in pipeline order: {@code empty}, {@code base}, {@code carved},
 * {@code liquid_carved}, {@code decorated}, {@code lighted}, {@code mobs_spawned}, {@code finalized},
 * {@code fullchunk}, {@code postprocessed} — confirmed directly against the decompiled 1.13.2 source,
 * {@code Akarin-project/Minecraft}'s {@code 1.13.2/spigot/net/minecraft/server/ChunkStatus.java},
 * 2026-08-05. Both {@code fullchunk} (a proto-chunk that has become a full, loaded chunk but has
 * never been postprocessed — generated terrain a player has simply never walked near) and
 * {@code postprocessed} (a full chunk that has) describe a completely generated chunk; the game
 * itself later folds the distinction away, confirmed by
 * {@code PaperMC/DataConverter}'s own two-stage fix at commit {@code dcde1f1f89dd6882b56246fe60233ed6a1cb5abb}:
 * {@code V1905.java} (DataVersion 1905, 18w43c+2) renames {@code postprocessed} to {@code fullchunk}
 * outright, and {@code V1911.java} (DataVersion 1911, 18w46a+1) then maps {@code fullchunk} — by then
 * the only surviving name for "complete" — to {@code full}, in the same table that renames every
 * other pipeline stage to its 1.14-development-era name ({@code base} to {@code surface},
 * {@code carved} to {@code carvers}, and so on). Both fetches checked 2026-08-05. This step therefore
 * maps {@code fullchunk} and {@code postprocessed} to {@code full} directly, the one edge this
 * module's own loader ({@code FalcoAnvilLoader.isFullyGenerated}) actually gates a chunk's load on;
 * see {@code NamespaceStatusTest.testAGenuineNineteenThirteenTerminalStatusBecomesMinecraftFull} for
 * the sourced fixture. The eight remaining, non-terminal 1.13 values are left namespaced but
 * otherwise unrenamed rather than guessed at: they describe an incomplete proto-chunk either way, and
 * {@code FalcoAnvilLoader} skips anything that is not exactly {@code minecraft:full} regardless of
 * which non-terminal name it carries, so a wrong (but still non-{@code full}) guess for one of them
 * would not silently corrupt a load the way the {@code full} case would have.
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
     * The only two bare, pre-namespace {@code Status} values this module can source a modern meaning
     * for — both the 1.13.2 terminal ("chunk is completely generated") status, under its two names
     * across that version's own chunk-loading pipeline. See this class's own javadoc for the sourced
     * chain ({@code fullchunk}/{@code postprocessed} to {@code full}) that justifies this table; every
     * bare value not in it is namespaced without being renamed, rather than guessed at.
     */
    private static final Map<String, String> RENAMED_ON_NAMESPACE = Map.of(
            "postprocessed", "full",
            "fullchunk", "full");

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
        String renamed = RENAMED_ON_NAMESPACE.getOrDefault(value, value);
        return chunk.putString(STATUS_KEY, MINECRAFT_NAMESPACE + renamed);
    }
}
