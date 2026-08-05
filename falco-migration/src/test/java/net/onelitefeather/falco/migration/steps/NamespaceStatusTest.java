package net.onelitefeather.falco.migration.steps;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.onelitefeather.falco.migration.MigrationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins {@link NamespaceStatus} down against the real, sourced pre-1.14 status values rather than the
 * literal {@code "full"} every fixture elsewhere in this module used to write by hand — a value a
 * 1.13 chunk never actually produces, which is exactly how the missing value translation this class
 * fixes went unnoticed until the final review measured it directly against
 * {@code FalcoAnvilLoader}. See {@link NamespaceStatus}'s own javadoc for the sourced chain
 * ({@code postprocessed}/{@code fullchunk} to {@code full}, via
 * {@code PaperMC/DataConverter}'s {@code V1905}/{@code V1911}, cross-checked against 1.13.2's own
 * decompiled {@code ChunkStatus}).
 */
class NamespaceStatusTest {

    private static final MigrationContext ANY_CONTEXT = new MigrationContext(1519, 4790);

    @Test
    void testAGenuineNineteenThirteenTerminalStatusBecomesMinecraftFull() {
        // "postprocessed": a 1.13 chunk that has been loaded at least once and had its queued block
        // updates run — the terminal status a chunk which has actually been played near carries.
        CompoundBinaryTag chunk = CompoundBinaryTag.builder().putString("Status", "postprocessed").build();

        CompoundBinaryTag namespaced = new NamespaceStatus().apply(chunk, ANY_CONTEXT);

        assertEquals("minecraft:full", namespaced.getString("Status"));
    }

    @Test
    void testAGenuineNineteenThirteenFullChunkStatusAlsoBecomesMinecraftFull() {
        // "fullchunk": a 1.13 chunk whose terrain, decoration and lighting are all complete but which
        // has never been loaded as a full, ticking chunk — equally "fully generated" as
        // "postprocessed" for this module's purposes (FalcoAnvilLoader gates only on completeness),
        // just never queued for the block updates postprocessing runs.
        CompoundBinaryTag chunk = CompoundBinaryTag.builder().putString("Status", "fullchunk").build();

        CompoundBinaryTag namespaced = new NamespaceStatus().apply(chunk, ANY_CONTEXT);

        assertEquals("minecraft:full", namespaced.getString("Status"));
    }

    @Test
    void testANonTerminalNineteenThirteenStatusIsNamespacedButNotRenamedToFull() {
        // "finalized": a real 1.13 pipeline stage (mobs have spawned, lighting still pending -
        // actually the stage right before fullchunk), but not the terminal one. This module cannot
        // source a modern name for it, so it is namespaced and left alone rather than guessed at -
        // which FalcoAnvilLoader still correctly treats as "not fully generated", the same outcome an
        // exact modern name would have produced for this step's own purposes.
        CompoundBinaryTag chunk = CompoundBinaryTag.builder().putString("Status", "finalized").build();

        CompoundBinaryTag namespaced = new NamespaceStatus().apply(chunk, ANY_CONTEXT);

        assertEquals("minecraft:finalized", namespaced.getString("Status"));
    }

    @Test
    void testAnAlreadyNamespacedStatusIsLeftCompletelyAlone() {
        CompoundBinaryTag chunk = CompoundBinaryTag.builder().putString("Status", "minecraft:carvers").build();

        CompoundBinaryTag namespaced = new NamespaceStatus().apply(chunk, ANY_CONTEXT);

        assertEquals("minecraft:carvers", namespaced.getString("Status"));
    }

    @Test
    void testAChunkWithNoStatusFieldIsReturnedUnchanged() {
        CompoundBinaryTag chunk = CompoundBinaryTag.builder().putInt("DataVersion", 1519).build();

        CompoundBinaryTag result = new NamespaceStatus().apply(chunk, ANY_CONTEXT);

        assertSame(chunk, result);
        assertNull(result.get("Status"));
    }
}
